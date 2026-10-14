package ec.unisfera.academic.application;

import ec.unisfera.academic.domain.EnrollmentPolicy;
import ec.unisfera.academic.domain.EnrollmentWindow;
import ec.unisfera.academic.domain.TimeBlock;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Lecturas que alimentan las reglas académicas (fotografía del estado del estudiante). */
@Component
public class AcademicQueries {

    public record StudentRow(UUID id, UUID personId, String code, String status, UUID careerId, UUID curriculumId, String careerName,
                             String fullName, String idNumber) {
    }

    public record PeriodRow(UUID id, String code, String name, String status, EnrollmentWindow window, LocalDate classesStart,
                            LocalDate classesEnd) {
    }

    public record SubjectRow(UUID id, String code, String name, int level, int totalHours, BigDecimal credits, UUID curriculumId) {
    }

    public record KardexRow(UUID subjectId, String result, BigDecimal grade, String periodCode, Integer attempt) {
    }

    private final JdbcClient jdbc;
    private final ParameterService parameters;

    public AcademicQueries(JdbcClient jdbc, ParameterService parameters) {
        this.jdbc = jdbc;
        this.parameters = parameters;
    }

    public StudentRow student(UUID studentId) {
        return jdbc.sql("""
                        select s.id, s.person_id, s.code, s.status, s.career_id, s.curriculum_id, c.name career_name,
                               p.first_names || ' ' || p.last_names full_name, p.id_number
                        from student s join career c on c.id = s.career_id join person p on p.id = s.person_id where s.id = :id""")
                .param("id", studentId)
                .query((rs, n) -> new StudentRow(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("code"),
                        rs.getString("status"), rs.getObject("career_id", UUID.class), rs.getObject("curriculum_id", UUID.class),
                        rs.getString("career_name"), rs.getString("full_name"), rs.getString("id_number")))
                .optional().orElseThrow(() -> new NotFoundException("El estudiante"));
    }

    public PeriodRow period(UUID periodId) {
        return jdbc.sql("select * from academic_period where id = :id").param("id", periodId)
                .query((rs, n) -> new PeriodRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("status"),
                        new EnrollmentWindow(rs.getDate("ordinary_enrollment_from").toLocalDate(), rs.getDate("ordinary_enrollment_to").toLocalDate(),
                                rs.getDate("extraordinary_enrollment_to").toLocalDate(), rs.getDate("special_enrollment_to").toLocalDate()),
                        rs.getDate("classes_start").toLocalDate(), rs.getDate("classes_end").toLocalDate()))
                .optional().orElseThrow(() -> new NotFoundException("El período"));
    }

    public UUID currentPeriodId() {
        return jdbc.sql("select id from academic_period where status in ('ENROLLMENT', 'IN_PROGRESS') order by classes_start desc limit 1")
                .query(UUID.class).optional().orElseThrow(() -> new NotFoundException("Un período abierto"));
    }

    public List<SubjectRow> subjects(UUID curriculumId) {
        return jdbc.sql("""
                        select s.id, s.code, s.name, l.number, s.total_hours, s.credits, s.curriculum_id
                        from subject s join curriculum_level l on l.id = s.level_id where s.curriculum_id = :c order by l.number, s.code""")
                .param("c", curriculumId)
                .query((rs, n) -> new SubjectRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getInt("number"),
                        rs.getInt("total_hours"), rs.getBigDecimal("credits"), rs.getObject("curriculum_id", UUID.class)))
                .list();
    }

    public Map<UUID, EnrollmentPolicy.SubjectRules> rules(UUID curriculumId) {
        Map<UUID, Set<UUID>> pre = new HashMap<>();
        Map<UUID, Set<UUID>> co = new HashMap<>();
        jdbc.sql("select p.subject_id, p.required_subject_id from subject_prerequisite p join subject s on s.id = p.subject_id where s.curriculum_id = :c")
                .param("c", curriculumId)
                .query((rs, n) -> pre.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>()).add(rs.getObject(2, UUID.class))).list();
        jdbc.sql("select q.subject_id, q.corequisite_subject_id from subject_corequisite q join subject s on s.id = q.subject_id where s.curriculum_id = :c")
                .param("c", curriculumId)
                .query((rs, n) -> co.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>()).add(rs.getObject(2, UUID.class))).list();
        Map<UUID, EnrollmentPolicy.SubjectRules> result = new HashMap<>();
        for (SubjectRow s : subjects(curriculumId)) {
            result.put(s.id(), new EnrollmentPolicy.SubjectRules(s.id(), s.code(), s.name(), s.curriculumId(),
                    pre.getOrDefault(s.id(), Set.of()), co.getOrDefault(s.id(), Set.of())));
        }
        return result;
    }

    /** Kardex vigente (solo actas cerradas y homologaciones aprobadas: RN-17). */
    public List<KardexRow> kardex(UUID studentId) {
        return jdbc.sql("select subject_id, result, final_grade, period_code, attempt_number from v_kardex where student_id = :s order by recorded_at")
                .param("s", studentId)
                .query((rs, n) -> new KardexRow(rs.getObject("subject_id", UUID.class), rs.getString("result"), rs.getBigDecimal("final_grade"),
                        rs.getString("period_code"), (Integer) rs.getObject("attempt_number")))
                .list();
    }

    public Set<UUID> approvedSubjects(UUID studentId) {
        return Set.copyOf(jdbc.sql("select distinct subject_id from v_kardex where student_id = :s and result = 'APPROVED'")
                .param("s", studentId).query(UUID.class).list());
    }

    /** Intentos que cuentan para RN-07 (los retiros y anulaciones no cuentan), excluyendo un período. */
    public Map<UUID, Integer> countedAttempts(UUID studentId, UUID excludingPeriodId) {
        Map<UUID, Integer> result = new HashMap<>();
        jdbc.sql("""
                        select subject_id, count(*) from enrollment_detail
                        where student_id = :s and status = 'ENROLLED' and period_id <> :p group by subject_id""")
                .param("s", studentId).param("p", excludingPeriodId)
                .query((rs, n) -> result.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
        return result;
    }

    public Map<String, List<UUID>> openAuthorizations(UUID studentId, UUID periodId) {
        Map<String, List<UUID>> result = new HashMap<>();
        jdbc.sql("""
                        select type, coalesce(subject_id, id) from exception_authorization
                        where student_id = :s and (period_id is null or period_id = :p) and used_at is null and annulled_at is null
                          and (expires_at is null or expires_at > now())""")
                .param("s", studentId).param("p", periodId)
                .query((rs, n) -> result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class))).list();
        return result;
    }

    public UUID openAuthorizationId(UUID studentId, UUID periodId, String type, UUID subjectId) {
        return jdbc.sql("""
                        select id from exception_authorization
                        where student_id = :s and type = :t and (period_id is null or period_id = :p)
                          and (cast(:sub as uuid) is null or subject_id = :sub) and used_at is null and annulled_at is null
                          and (expires_at is null or expires_at > now())
                        order by authorized_at desc limit 1""")
                .param("s", studentId).param("t", type).param("p", periodId).param("sub", subjectId)
                .query(UUID.class).optional().orElse(null);
    }

    /** RN-08: cuotas vencidas más allá de los días de gracia vigentes al vencimiento de cada cuota (RN-26). */
    public boolean hasOverdueDebt(UUID studentId, LocalDate today) {
        return jdbc.sql("""
                        select count(*) from installment i
                        where i.student_id = :s and i.status = 'PENDING'
                          and i.due_date + param_numeric('finance.grace_days', i.due_date::timestamptz)::int < :today
                          and i.amount_due + i.late_fee_accrued - i.amount_paid > 0""")
                .param("s", studentId).param("today", today).query(Long.class).single() > 0;
    }

    public List<EnrollmentPolicy.Selection> selections(List<UUID> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<TimeBlock>> blocks = new HashMap<>();
        jdbc.sql("select section_id, weekday, lower(minutes) s, upper(minutes) e from schedule_block where section_id in (:ids)")
                .param("ids", sectionIds)
                .query((rs, n) -> blocks.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new TimeBlock(rs.getInt(2), rs.getInt(3), rs.getInt(4)))).list();
        return jdbc.sql("select id, subject_id, code, capacity, enrolled_count, status from section where id in (:ids)")
                .param("ids", sectionIds)
                .query((rs, n) -> new EnrollmentPolicy.Selection(rs.getObject("id", UUID.class), rs.getObject("subject_id", UUID.class),
                        rs.getString("code"), rs.getInt("capacity"), rs.getInt("enrolled_count"), "OPEN".equals(rs.getString("status")),
                        blocks.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    public UUID periodOfSections(List<UUID> sectionIds) {
        List<UUID> periods = jdbc.sql("select distinct period_id from section where id in (:ids)").param("ids", sectionIds).query(UUID.class).list();
        if (periods.size() != 1) {
            throw new ec.unisfera.shared.domain.BusinessRuleException("RN-01", "Todos los paralelos deben pertenecer al mismo período");
        }
        return periods.getFirst();
    }

    public int maxSubjects() {
        return parameters.integer(ParameterKeys.MAX_SUBJECTS);
    }
}
