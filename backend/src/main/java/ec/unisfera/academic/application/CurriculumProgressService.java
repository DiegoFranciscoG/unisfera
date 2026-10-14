package ec.unisfera.academic.application;

import ec.unisfera.academic.domain.AttemptPolicy;
import ec.unisfera.academic.domain.EnrollmentPolicy;
import ec.unisfera.academic.domain.EnrollmentWindow;
import ec.unisfera.academic.domain.PrerequisiteChecker;
import ec.unisfera.institution.application.CatalogViews;
import ec.unisfera.institution.application.SectionService;
import ec.unisfera.shared.config.AppProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Mi malla con avance": el estado de cada asignatura (aprobada, en curso, disponible,
 * bloqueada por prerrequisito, requiere autorización, agotada) y la oferta del período.
 */
@Service
public class CurriculumProgressService {

    public enum SubjectStatus { APPROVED, IN_PROGRESS, AVAILABLE, BLOCKED, NEEDS_AUTHORIZATION, EXHAUSTED }

    public record SubjectProgress(UUID id, String code, String name, int level, int totalHours, BigDecimal credits, SubjectStatus status,
                                  BigDecimal grade, int attemptsUsed, List<String> missingPrerequisites, List<UUID> prerequisites,
                                  List<UUID> corequisites) {
    }

    public record Progress(UUID studentId, String studentCode, String fullName, String careerName, String status, BigDecimal approvedCredits,
                           BigDecimal totalCredits, BigDecimal weightedAverage, int approvedSubjects, int totalSubjects,
                           List<SubjectProgress> subjects) {
    }

    public record SectionOffer(UUID sectionId, String code, int capacity, int seatsLeft, String teacherName, List<CatalogViews.BlockView> blocks) {
    }

    public record SubjectOffer(SubjectProgress subject, List<SectionOffer> sections) {
    }

    public record Offer(UUID periodId, String periodCode, String periodName, String typeToday, LocalDate ordinaryFrom, LocalDate ordinaryTo,
                        LocalDate extraordinaryTo, LocalDate specialTo, int maxSubjects, boolean alreadyEnrolled, List<SubjectOffer> subjects) {
    }

    private final JdbcClient jdbc;
    private final AcademicQueries queries;
    private final SectionService sections;
    private final Clock clock;
    private final AppProperties props;

    public CurriculumProgressService(JdbcClient jdbc, AcademicQueries queries, SectionService sections, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.sections = sections;
        this.clock = clock;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Progress progress(UUID studentId) {
        AcademicQueries.StudentRow student = queries.student(studentId);
        Map<UUID, EnrollmentPolicy.SubjectRules> rules = queries.rules(student.curriculumId());
        List<AcademicQueries.KardexRow> kardex = queries.kardex(studentId);
        Map<UUID, BigDecimal> approvedGrades = kardex.stream().filter(k -> "APPROVED".equals(k.result()))
                .collect(Collectors.toMap(AcademicQueries.KardexRow::subjectId, AcademicQueries.KardexRow::grade, (a, b) -> a));
        Set<UUID> inProgress = Set.copyOf(jdbc.sql("""
                        select d.subject_id from enrollment_detail d
                        where d.student_id = :s and d.status = 'ENROLLED'
                          and not exists (select 1 from grade_act a where a.section_id = d.section_id and a.status in ('CLOSED', 'RECTIFIED'))""")
                .param("s", studentId).query(UUID.class).list());
        Map<UUID, Integer> attempts = queries.countedAttempts(studentId, UUID.randomUUID());
        Map<String, List<UUID>> auth = queries.openAuthorizations(studentId, UUID.randomUUID());
        Set<UUID> thirdAuthorized = Set.copyOf(auth.getOrDefault("THIRD_ENROLLMENT", List.of()));

        List<SubjectProgress> subjects = new ArrayList<>();
        BigDecimal approvedCredits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (AcademicQueries.SubjectRow s : queries.subjects(student.curriculumId())) {
            EnrollmentPolicy.SubjectRules r = rules.get(s.id());
            totalCredits = totalCredits.add(s.credits());
            Set<UUID> missing = PrerequisiteChecker.missingPrerequisites(r.prerequisites(), approvedGrades.keySet());
            int used = attempts.getOrDefault(s.id(), 0);
            SubjectStatus status;
            if (approvedGrades.containsKey(s.id())) {
                status = SubjectStatus.APPROVED;
                approvedCredits = approvedCredits.add(s.credits());
            } else if (inProgress.contains(s.id())) {
                status = SubjectStatus.IN_PROGRESS;
            } else if (!missing.isEmpty()) {
                status = SubjectStatus.BLOCKED;
            } else {
                status = switch (AttemptPolicy.evaluate(used, thirdAuthorized.contains(s.id()))) {
                    case EXHAUSTED -> SubjectStatus.EXHAUSTED;
                    case NEEDS_AUTHORIZATION -> SubjectStatus.NEEDS_AUTHORIZATION;
                    case ALLOWED -> SubjectStatus.AVAILABLE;
                };
            }
            subjects.add(new SubjectProgress(s.id(), s.code(), s.name(), s.level(), s.totalHours(), s.credits(), status,
                    approvedGrades.get(s.id()), used, missing.stream().map(id -> rules.get(id).name()).sorted().toList(),
                    List.copyOf(r.prerequisites()), List.copyOf(r.corequisites())));
        }
        BigDecimal average = jdbc.sql("select weighted_average from v_student_average where student_id = :s").param("s", studentId)
                .query(BigDecimal.class).optional().orElse(null);
        long approvedCount = subjects.stream().filter(x -> x.status() == SubjectStatus.APPROVED).count();
        return new Progress(studentId, student.code(), student.fullName(), student.careerName(), student.status(),
                approvedCredits.setScale(2, RoundingMode.HALF_UP), totalCredits.setScale(2, RoundingMode.HALF_UP), average,
                (int) approvedCount, subjects.size(), subjects);
    }

    public record ScheduleEntry(UUID detailId, UUID sectionId, String subjectCode, String subjectName, String sectionCode, int attempt,
                                String teacherName, List<CatalogViews.BlockView> blocks) {
    }

    /** Horario del período vigente (solo asignaturas activas en la matrícula). */
    @Transactional(readOnly = true)
    public List<ScheduleEntry> schedule(UUID studentId) {
        UUID periodId = queries.currentPeriodId();
        List<Object[]> rows = jdbc.sql("""
                        select d.id, d.section_id, sb.code, sb.name, s.code, d.attempt_number, p.first_names || ' ' || p.last_names
                        from enrollment_detail d join section s on s.id = d.section_id join subject sb on sb.id = d.subject_id
                        left join teacher t on t.id = s.teacher_id left join person p on p.id = t.person_id
                        where d.student_id = :st and d.period_id = :p and d.status = 'ENROLLED' order by sb.code""")
                .param("st", studentId).param("p", periodId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getString(7)})
                .list();
        Map<UUID, List<CatalogViews.BlockView>> blocks = sections.blocksOf(rows.stream().map(r -> (UUID) r[1]).toList());
        return rows.stream().map(r -> new ScheduleEntry((UUID) r[0], (UUID) r[1], (String) r[2], (String) r[3], (String) r[4], (int) r[5],
                (String) r[6], blocks.getOrDefault((UUID) r[1], List.of()))).toList();
    }

    /** Oferta del período vigente para el estudiante: solo asignaturas que puede tomar, con cupos en vivo. */
    @Transactional(readOnly = true)
    public Offer offer(UUID studentId) {
        UUID periodId = queries.currentPeriodId();
        AcademicQueries.PeriodRow period = queries.period(periodId);
        Progress progress = progress(studentId);
        Map<String, List<UUID>> auth = queries.openAuthorizations(studentId, periodId);
        LocalDate today = LocalDate.now(clock.withZone(props.zoneId()));
        Optional<EnrollmentWindow.Type> type = period.window().typeOn(today, auth.containsKey("SPECIAL_ENROLLMENT"));
        boolean enrolled = jdbc.sql("select count(*) from enrollment where student_id = :s and period_id = :p and status = 'CONFIRMED'")
                .param("s", studentId).param("p", periodId).query(Long.class).single() > 0;
        List<SubjectProgress> eligible = progress.subjects().stream()
                .filter(s -> s.status() == SubjectStatus.AVAILABLE || s.status() == SubjectStatus.NEEDS_AUTHORIZATION)
                .toList();
        List<UUID> subjectIds = eligible.stream().map(SubjectProgress::id).toList();
        Map<UUID, List<SectionOffer>> bySubject = new java.util.HashMap<>();
        if (!subjectIds.isEmpty()) {
            List<Object[]> rows = jdbc.sql("""
                            select s.id, s.subject_id, s.code, s.capacity, s.enrolled_count, p.first_names || ' ' || p.last_names
                            from section s left join teacher t on t.id = s.teacher_id left join person p on p.id = t.person_id
                            where s.period_id = :p and s.status = 'OPEN' and s.subject_id in (:subs) order by s.code""")
                    .param("p", periodId).param("subs", subjectIds)
                    .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getString(6)})
                    .list();
            Map<UUID, List<CatalogViews.BlockView>> blocks = sections.blocksOf(rows.stream().map(r -> (UUID) r[0]).toList());
            for (Object[] r : rows) {
                bySubject.computeIfAbsent((UUID) r[1], k -> new ArrayList<>()).add(new SectionOffer((UUID) r[0], (String) r[2], (int) r[3],
                        (int) r[3] - (int) r[4], (String) r[5], blocks.getOrDefault((UUID) r[0], List.of())));
            }
        }
        List<SubjectOffer> offers = eligible.stream().filter(s -> bySubject.containsKey(s.id()))
                .map(s -> new SubjectOffer(s, bySubject.get(s.id()))).toList();
        return new Offer(periodId, period.code(), period.name(), type.map(Enum::name).orElse(null), period.window().ordinaryFrom(),
                period.window().ordinaryTo(), period.window().extraordinaryTo(), period.window().specialTo(), queries.maxSubjects(),
                enrolled, offers);
    }
}
