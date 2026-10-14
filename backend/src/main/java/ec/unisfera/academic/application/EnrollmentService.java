package ec.unisfera.academic.application;

import ec.unisfera.academic.domain.EnrollmentPolicy;
import ec.unisfera.academic.domain.EnrollmentWindow;
import ec.unisfera.academic.domain.Violation;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Motor de matrícula. Arma una fotografía del estado, evalúa todas las reglas en el dominio
 * (validación en vivo) y luego inserta: los triggers vuelven a validar y reservan el cupo con
 * una actualización atómica. Todo ocurre en una sola transacción, incluida la generación de
 * rubros: o se completa entera o no ocurre (Ley 3).
 */
@Service
public class EnrollmentService {

    public record PreviewLine(UUID sectionId, UUID subjectId, String subjectName, String sectionCode, int attempt, int seatsLeft) {
    }

    public record Preview(UUID periodId, String type, List<Violation> violations, List<PreviewLine> lines, EnrollmentQuotePort.Quote quote) {
    }

    public record DetailLine(UUID detailId, UUID subjectId, String subjectName, String sectionCode, int attempt) {
    }

    public record Confirmation(UUID enrollmentId, String periodCode, String type, String studentStatus, List<DetailLine> details) {
    }

    private final JdbcClient jdbc;
    private final AcademicQueries queries;
    private final ParameterService parameters;
    private final ApplicationEventPublisher events;
    private final EnrollmentQuotePort quotes;
    private final Clock clock;
    private final AppProperties props;

    public EnrollmentService(JdbcClient jdbc, AcademicQueries queries, ParameterService parameters, ApplicationEventPublisher events,
                             EnrollmentQuotePort quotes, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.parameters = parameters;
        this.events = events;
        this.quotes = quotes;
        this.clock = clock;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID studentId, List<UUID> sectionIds) {
        UUID periodId = sectionIds.isEmpty() ? queries.currentPeriodId() : queries.periodOfSections(sectionIds);
        EnrollmentPolicy.Context context = context(studentId, periodId, sectionIds);
        EnrollmentPolicy.Evaluation evaluation = EnrollmentPolicy.evaluate(context);
        List<PreviewLine> lines = context.selections().stream()
                .map(s -> new PreviewLine(s.sectionId(), s.subjectId(),
                        context.subjects().containsKey(s.subjectId()) ? context.subjects().get(s.subjectId()).name() : "?",
                        s.sectionCode(), evaluation.attempts().getOrDefault(s.subjectId(), 1), s.capacity() - s.enrolled()))
                .toList();
        String type = evaluation.type().map(Enum::name).orElse(null);
        EnrollmentQuotePort.Quote quote = type == null || context.selections().isEmpty() ? null
                : quotes.quote(studentId, periodId, type, context.selections().stream().map(EnrollmentPolicy.Selection::subjectId).toList());
        return new Preview(periodId, type, evaluation.violations(), lines, quote);
    }

    @Transactional
    public Confirmation enroll(UUID studentId, List<UUID> sectionIds) {
        if (sectionIds.isEmpty()) {
            throw new BusinessRuleException("RN-04", "Seleccione al menos una asignatura");
        }
        UUID periodId = queries.periodOfSections(sectionIds);
        EnrollmentPolicy.Context context = context(studentId, periodId, sectionIds);
        EnrollmentPolicy.Evaluation evaluation = EnrollmentPolicy.evaluate(context);
        if (!evaluation.ok()) {
            Violation first = evaluation.violations().getFirst();
            throw new BusinessRuleException(first.code(), first.message(), evaluation.violations());
        }
        EnrollmentWindow.Type type = evaluation.type().orElseThrow();
        UUID enrollmentId = UuidV7.next(clock);
        UUID special = type == EnrollmentWindow.Type.SPECIAL ? queries.openAuthorizationId(studentId, periodId, "SPECIAL_ENROLLMENT", null) : null;
        UUID load = sectionIds.size() > context.maxSubjects() ? queries.openAuthorizationId(studentId, periodId, "LOAD_EXCEPTION", null) : null;
        UUID debt = context.hasOverdueDebt() ? queries.openAuthorizationId(studentId, periodId, "DEBT_EXCEPTION", null) : null;
        jdbc.sql("""
                        insert into enrollment (id, student_id, period_id, type, registered_by, special_authorization_id,
                                                load_authorization_id, debt_authorization_id)
                        values (:id, :s, :p, :t, :by, :sp, :ld, :db)""")
                .param("id", enrollmentId).param("s", studentId).param("p", periodId).param("t", type.name())
                .param("by", RequestContext.actorId().orElse(null)).param("sp", special).param("ld", load).param("db", debt).update();

        // Orden fijo por paralelo: evita interbloqueos cuando dos estudiantes eligen los mismos paralelos.
        List<EnrollmentPolicy.Selection> ordered = context.selections().stream()
                .sorted(Comparator.comparing(EnrollmentPolicy.Selection::sectionId)).toList();
        for (EnrollmentPolicy.Selection s : ordered) {
            int attempt = evaluation.attempts().getOrDefault(s.subjectId(), 1);
            UUID third = attempt == ec.unisfera.academic.domain.AttemptPolicy.LEGAL_MAX_ATTEMPTS
                    ? queries.openAuthorizationId(studentId, periodId, "THIRD_ENROLLMENT", s.subjectId()) : null;
            jdbc.sql("""
                            insert into enrollment_detail (id, enrollment_id, student_id, period_id, subject_id, section_id,
                                                           attempt_number, third_enrollment_authorization_id)
                            values (:id, :e, :s, :p, :sub, :sec, :a, :third)""")
                    .param("id", UuidV7.next(clock)).param("e", enrollmentId).param("s", studentId).param("p", periodId)
                    .param("sub", s.subjectId()).param("sec", s.sectionId()).param("a", attempt).param("third", third).update();
            if (third != null) {
                markUsed(third);
            }
        }
        for (UUID used : new UUID[]{special, load, debt}) {
            if (used != null) {
                markUsed(used);
            }
        }
        String status = advanceStatus(studentId, sectionIds.size(), context.maxSubjects());
        events.publishEvent(new AcademicEvents.EnrollmentConfirmed(enrollmentId, studentId, periodId, type.name(),
                ordered.stream().map(EnrollmentPolicy.Selection::subjectId).toList()));
        return confirmation(enrollmentId, status);
    }

    /** RN-10: la anulación marca, revierte rubros (oyente financiero) y libera cupos; nada se borra. */
    @Transactional
    public void annul(UUID enrollmentId, String reason, String legalBasis) {
        var row = jdbc.sql("select student_id, period_id, status from enrollment where id = :id for update").param("id", enrollmentId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)})
                .optional().orElseThrow(() -> new NotFoundException("La matrícula"));
        if (!"CONFIRMED".equals(row[2])) {
            throw new BusinessRuleException("RN-10", "La matrícula ya está anulada");
        }
        UUID actor = RequestContext.actorId().orElseThrow(() -> new BusinessRuleException("RN-10", "La anulación exige un responsable"));
        jdbc.sql("update enrollment set status = 'ANNULLED', annulled_at = now(), annulled_by = :by, annulment_reason = :r where id = :id")
                .param("by", actor).param("r", reason).param("id", enrollmentId).update();
        jdbc.sql("insert into enrollment_annulment (id, enrollment_id, legal_basis, reason, annulled_by) values (:id, :e, :l, :r, :by)")
                .param("id", UuidV7.next(clock)).param("e", enrollmentId).param("l", legalBasis).param("r", reason).param("by", actor).update();
        events.publishEvent(new AcademicEvents.EnrollmentAnnulled(enrollmentId, (UUID) row[0], (UUID) row[1], reason));
    }

    @Transactional(readOnly = true)
    public Confirmation current(UUID studentId) {
        UUID periodId = queries.currentPeriodId();
        UUID enrollmentId = jdbc.sql("select id from enrollment where student_id = :s and period_id = :p and status = 'CONFIRMED'")
                .param("s", studentId).param("p", periodId).query(UUID.class).optional().orElse(null);
        if (enrollmentId == null) {
            return null;
        }
        return confirmation(enrollmentId, queries.student(studentId).status());
    }

    private Confirmation confirmation(UUID enrollmentId, String status) {
        var header = jdbc.sql("select p.code, e.type from enrollment e join academic_period p on p.id = e.period_id where e.id = :id")
                .param("id", enrollmentId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        List<DetailLine> details = jdbc.sql("""
                        select d.id, d.subject_id, s.name, sec.code, d.attempt_number from enrollment_detail d
                        join subject s on s.id = d.subject_id join section sec on sec.id = d.section_id
                        where d.enrollment_id = :e and d.status = 'ENROLLED' order by s.code""")
                .param("e", enrollmentId)
                .query((rs, n) -> new DetailLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5)))
                .list();
        return new Confirmation(enrollmentId, header[0], header[1], status, details);
    }

    /** RN-30: primera matrícula → ENROLLED; con carga suficiente (RRA art. 76) → REGULAR. */
    private String advanceStatus(UUID studentId, int subjects, int maxSubjects) {
        String status = queries.student(studentId).status();
        if ("ADMITTED".equals(status)) {
            jdbc.sql("update student set status = 'ENROLLED' where id = :id").param("id", studentId).update();
            status = "ENROLLED";
        }
        BigDecimal loadPct = BigDecimal.valueOf(subjects).multiply(ec.unisfera.shared.domain.MoneyMath.hundred())
                .divide(BigDecimal.valueOf(maxSubjects), 2, java.math.RoundingMode.HALF_UP);
        if ("ENROLLED".equals(status) && loadPct.compareTo(parameters.decimal(ParameterKeys.REGULAR_MIN_LOAD)) >= 0) {
            jdbc.sql("update student set status = 'REGULAR' where id = :id").param("id", studentId).update();
            status = "REGULAR";
        }
        return status;
    }

    private void markUsed(UUID authorizationId) {
        jdbc.sql("update exception_authorization set used_at = now() where id = :id").param("id", authorizationId).update();
    }

    EnrollmentPolicy.Context context(UUID studentId, UUID periodId, List<UUID> sectionIds) {
        AcademicQueries.StudentRow student = queries.student(studentId);
        AcademicQueries.PeriodRow period = queries.period(periodId);
        LocalDate today = LocalDate.now(clock.withZone(props.zoneId()));
        Map<String, List<UUID>> auth = queries.openAuthorizations(studentId, periodId);
        return new EnrollmentPolicy.Context(
                today, period.window(), auth.containsKey("SPECIAL_ENROLLMENT"), student.status(), student.curriculumId(),
                queries.rules(student.curriculumId()), queries.approvedSubjects(studentId), queries.countedAttempts(studentId, periodId),
                Set.copyOf(auth.getOrDefault("THIRD_ENROLLMENT", List.of())), queries.selections(sectionIds), queries.maxSubjects(),
                auth.containsKey("LOAD_EXCEPTION"), parameters.bool(ParameterKeys.BLOCK_ON_DEBT), queries.hasOverdueDebt(studentId, today),
                auth.containsKey("DEBT_EXCEPTION"));
    }
}
