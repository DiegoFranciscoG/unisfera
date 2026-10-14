package ec.unisfera.academic.application;

import ec.unisfera.identity.application.AccountService;
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
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Docentes, admisión, autorizaciones de excepción, retiros y homologaciones. */
@Service
public class AcademicAdminService {

    public record TeacherCommand(UUID personId, String highestDegree, String dedication, String category) {
    }

    public record AuthorizationCommand(String type, UUID studentId, UUID periodId, UUID subjectId, UUID sectionId, String causeCode,
                                       String reason, String resolutionRef) {
    }

    public record HomologationCommand(UUID studentId, UUID targetSubjectId, String method, String sourceInstitution, String sourceSubjectName,
                                      Integer sourceHours, BigDecimal sourceGrade, BigDecimal sourceScaleMax, LocalDate sourceApprovedOn,
                                      BigDecimal coveragePercentage, BigDecimal grade) {
    }

    public record WithdrawalView(UUID id, UUID detailId, String subjectName, String type, String reason, String status) {
    }

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final ParameterService parameters;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AppProperties props;

    public AcademicAdminService(JdbcClient jdbc, AccountService accounts, ParameterService parameters, ApplicationEventPublisher events,
                                Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.parameters = parameters;
        this.events = events;
        this.clock = clock;
        this.props = props;
    }

    @Transactional
    public UUID createTeacher(TeacherCommand c) {
        UUID id = UuidV7.next(clock);
        long seq = jdbc.sql("select nextval('teacher_code_seq')").query(Long.class).single();
        jdbc.sql("insert into teacher (id, person_id, code, highest_degree, dedication, category) values (:id, :p, :c, :d, :ded, :cat)")
                .param("id", id).param("p", c.personId()).param("c", "D" + String.format("%05d", seq)).param("d", c.highestDegree())
                .param("ded", c.dedication()).param("cat", c.category()).update();
        boolean hasAccount = jdbc.sql("select count(*) from user_account where person_id = :p").param("p", c.personId()).query(Long.class).single() > 0;
        if (hasAccount) {
            accounts.grant(accounts.userIdOfPerson(c.personId()), "TEACHER");
        } else {
            accounts.provision(c.personId(), List.of("TEACHER"), null);
        }
        return id;
    }

    @Transactional
    public UUID createAdmissionProcess(UUID periodId, UUID careerId, int quota, LocalDate opensOn, LocalDate closesOn, BigDecimal minScore) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into admission_process (id, period_id, career_id, quota, opens_on, closes_on, min_score)
                        values (:id, :p, :c, :q, :o, :cl, :m)""")
                .param("id", id).param("p", periodId).param("c", careerId).param("q", quota).param("o", opensOn).param("cl", closesOn)
                .param("m", minScore).update();
        return id;
    }

    @Transactional
    public UUID registerApplicant(UUID processId, UUID personId) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("insert into applicant (id, process_id, person_id) values (:id, :p, :per)")
                .param("id", id).param("p", processId).param("per", personId).update();
        return id;
    }

    /** Excepción autorizada; la base verifica que quien autoriza tenga el permiso exigido por el tipo. */
    @Transactional
    public UUID authorize(AuthorizationCommand c) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into exception_authorization (id, type, student_id, period_id, subject_id, section_id, cause_code, reason,
                                                             resolution_ref, authorized_by)
                        values (:id, :t, :s, :p, :sub, :sec, :cause, :r, :res, :by)""")
                .param("id", id).param("t", c.type()).param("s", c.studentId()).param("p", c.periodId()).param("sub", c.subjectId())
                .param("sec", c.sectionId()).param("cause", c.causeCode()).param("r", c.reason()).param("res", c.resolutionRef())
                .param("by", RequestContext.actorId().orElseThrow()).update();
        return id;
    }

    /** Solicitud de retiro del propio estudiante (RRA art. 73). */
    @Transactional
    public UUID requestWithdrawal(UUID studentId, UUID detailId, String type, String reason, UUID documentFileId) {
        var detail = jdbc.sql("""
                        select d.status, p.classes_start from enrollment_detail d join academic_period p on p.id = d.period_id
                        where d.id = :d and d.student_id = :s""")
                .param("d", detailId).param("s", studentId)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getDate(2).toLocalDate()})
                .optional().orElseThrow(() -> new NotFoundException("La asignatura matriculada"));
        if (!"ENROLLED".equals(detail[0])) {
            throw new BusinessRuleException("RN-07", "La asignatura no está activa en su matrícula");
        }
        if ("VOLUNTARY".equals(type)) {
            LocalDate deadline = ((LocalDate) detail[1]).plusDays(parameters.integer(ParameterKeys.WITHDRAWAL_DEADLINE_DAYS));
            boolean late = LocalDate.now(clock.withZone(props.zoneId())).isAfter(deadline);
            if (late && jdbc.sql("""
                            select count(*) from exception_authorization where type = 'LATE_WITHDRAWAL' and student_id = :s
                            and used_at is null and annulled_at is null""").param("s", studentId).query(Long.class).single() == 0) {
                throw new BusinessRuleException("RN-07", "El plazo de retiro voluntario venció el " + deadline
                        + "; solicite un retiro por caso fortuito o fuerza mayor con su respaldo");
            }
        }
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into withdrawal (id, enrollment_detail_id, type, reason, document_file_id, requested_by)
                        values (:id, :d, :t, :r, :f, app_actor())""")
                .param("id", id).param("d", detailId).param("t", type).param("r", reason).param("f", documentFileId).update();
        return id;
    }

    /** Aprobar el retiro anula el intento (no cuenta para tercera matrícula) y libera el cupo. */
    @Transactional
    public void decideWithdrawal(UUID withdrawalId, boolean approve, String note) {
        var w = jdbc.sql("""
                        select w.enrollment_detail_id, w.status, d.student_id, d.period_id, w.type from withdrawal w
                        join enrollment_detail d on d.id = w.enrollment_detail_id where w.id = :id for update""")
                .param("id", withdrawalId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5)})
                .optional().orElseThrow(() -> new NotFoundException("El retiro"));
        if (!"REQUESTED".equals(w[1])) {
            throw new BusinessRuleException("RN-07", "El retiro ya fue resuelto");
        }
        jdbc.sql("update withdrawal set status = :s, decided_at = now(), decided_by = app_actor(), decision_note = :n where id = :id")
                .param("s", approve ? "APPROVED" : "REJECTED").param("n", note).param("id", withdrawalId).update();
        if (approve) {
            jdbc.sql("update enrollment_detail set status = 'WITHDRAWN' where id = :d").param("d", w[0]).update();
            events.publishEvent(new AcademicEvents.SubjectWithdrawn((UUID) w[0], (UUID) w[2], (UUID) w[3], (String) w[4]));
        }
    }

    @Transactional(readOnly = true)
    public List<WithdrawalView> withdrawals(UUID studentId) {
        return jdbc.sql("""
                        select w.id, w.enrollment_detail_id, s.name, w.type, w.reason, w.status from withdrawal w
                        join enrollment_detail d on d.id = w.enrollment_detail_id join subject s on s.id = d.subject_id
                        where d.student_id = :s order by w.requested_at desc limit 50""")
                .param("s", studentId)
                .query((rs, n) -> new WithdrawalView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    @Transactional
    public UUID registerHomologation(HomologationCommand c) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into homologation (id, student_id, target_subject_id, method, source_institution, source_subject_name,
                                                  source_hours, source_grade, source_scale_max, source_approved_on, coverage_percentage, grade)
                        values (:id, :s, :t, :m, :si, :ss, :sh, :sg, :sx, :sa, :cov, :g)""")
                .param("id", id).param("s", c.studentId()).param("t", c.targetSubjectId()).param("m", c.method())
                .param("si", c.sourceInstitution()).param("ss", c.sourceSubjectName()).param("sh", c.sourceHours())
                .param("sg", c.sourceGrade()).param("sx", c.sourceScaleMax()).param("sa", c.sourceApprovedOn())
                .param("cov", c.coveragePercentage()).param("g", c.grade()).update();
        return id;
    }

    /** La base aplica el 80 % de cobertura y los 10 años de antigüedad (RRA art. 82) con los parámetros vigentes. */
    @Transactional
    public void approveHomologation(UUID homologationId, String resolutionRef) {
        int n = jdbc.sql("""
                        update homologation set status = 'APPROVED', resolution_ref = :r, decided_at = now(), decided_by = app_actor()
                        where id = :id and status = 'DRAFT'""")
                .param("r", resolutionRef).param("id", homologationId).update();
        if (n == 0) {
            throw new BusinessRuleException("RN-02", "La homologación no existe o ya fue resuelta");
        }
    }
}
