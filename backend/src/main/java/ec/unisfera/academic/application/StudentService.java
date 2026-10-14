package ec.unisfera.academic.application;

import ec.unisfera.academic.domain.StudentStateMachine;
import ec.unisfera.identity.application.AccountService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.persistence.DbSession;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta de estudiantes por la institución (nunca auto-registro): aspirante → admisión →
 * Secretaría crea al estudiante y su cuenta → se entrega una credencial temporal.
 */
@Service
public class StudentService {

    public record StudentSummary(UUID id, String code, String fullName, String idNumber, String careerName, String status,
                                 String cohort, String institutionalEmail) {
    }

    public record AdmissionResult(UUID studentId, String code, AccountService.ProvisionedAccount account) {
    }

    public record StatusEvent(String fromStatus, String toStatus, String process, Instant changedAt) {
    }

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final DbSession db;
    private final Clock clock;

    public StudentService(JdbcClient jdbc, AccountService accounts, DbSession db, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.db = db;
        this.clock = clock;
    }

    /** Admite a un aspirante y crea su registro de estudiante y su cuenta con credencial temporal. */
    @Transactional
    public AdmissionResult admitApplicant(UUID applicantId, UUID curriculumId, java.math.BigDecimal score) {
        var applicant = jdbc.sql("""
                        select a.person_id, ap.career_id, ap.period_id, ap.min_score, a.status from applicant a
                        join admission_process ap on ap.id = a.process_id where a.id = :id for update""")
                .param("id", applicantId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getBigDecimal(4), rs.getString(5)})
                .optional().orElseThrow(() -> new NotFoundException("El aspirante"));
        if (!List.of("REGISTERED", "EVALUATED").contains((String) applicant[4])) {
            throw new BusinessRuleException("RN-30", "El aspirante ya fue resuelto");
        }
        boolean admitted = score.compareTo((java.math.BigDecimal) applicant[3]) >= 0;
        jdbc.sql("update applicant set status = :st, score = :sc, decided_at = now(), decided_by = app_actor() where id = :id")
                .param("st", admitted ? "ADMITTED" : "NOT_ADMITTED").param("sc", score).param("id", applicantId).update();
        if (!admitted) {
            return new AdmissionResult(null, null, null);
        }
        return createStudent((UUID) applicant[0], (UUID) applicant[1], curriculumId, (UUID) applicant[2], applicantId);
    }

    /** Alta directa (por ejemplo, reingreso o cambio de carrera con homologación). */
    @Transactional
    public AdmissionResult createStudent(UUID personId, UUID careerId, UUID curriculumId, UUID cohortPeriodId, UUID applicantId) {
        String status = jdbc.sql("select status from curriculum where id = :c and career_id = :car").param("c", curriculumId).param("car", careerId)
                .query(String.class).optional().orElseThrow(() -> new BusinessRuleException("RN-03", "La malla no pertenece a la carrera"));
        if (!"ACTIVE".equals(status)) {
            throw new BusinessRuleException("RN-03", "Solo se admite en una malla vigente");
        }
        String year = jdbc.sql("select left(code, 4) from academic_period where id = :p").param("p", cohortPeriodId).query(String.class).single();
        long seq = jdbc.sql("select nextval('student_code_seq')").query(Long.class).single();
        String code = "E" + year + String.format("%06d", seq);
        String domain = jdbc.sql("select email_domain from institution limit 1").query(String.class).optional().orElse("unisfera.edu.ec");
        String email = code.toLowerCase() + "@" + domain;
        UUID studentId = UuidV7.next(clock);
        jdbc.sql("""
                        insert into student (id, person_id, code, career_id, curriculum_id, cohort_period_id, applicant_id, institutional_email)
                        values (:id, :p, :code, :car, :cur, :coh, :app, :mail)""")
                .param("id", studentId).param("p", personId).param("code", code).param("car", careerId).param("cur", curriculumId)
                .param("coh", cohortPeriodId).param("app", applicantId).param("mail", email).update();
        boolean hasAccount = jdbc.sql("select count(*) from user_account where person_id = :p").param("p", personId).query(Long.class).single() > 0;
        AccountService.ProvisionedAccount account = null;
        if (hasAccount) {
            accounts.grant(accounts.userIdOfPerson(personId), "STUDENT");
        } else {
            account = accounts.provision(personId, List.of("STUDENT"), email);
        }
        return new AdmissionResult(studentId, code, account);
    }

    /** RN-30: transición explícita (retiro, suspensión, reingreso, egreso) con el proceso que la justifica. */
    @Transactional
    public void changeStatus(UUID studentId, String toStatus, String process) {
        String from = jdbc.sql("select status from student where id = :id for update").param("id", studentId).query(String.class)
                .optional().orElseThrow(() -> new NotFoundException("El estudiante"));
        machine().check(from, toStatus, process);
        if (process != null && !process.isBlank()) {
            db.declareProcess(process);
        }
        jdbc.sql("update student set status = :s where id = :id").param("s", toStatus).param("id", studentId).update();
    }

    @Transactional(readOnly = true)
    public PageResponse<StudentSummary> search(String q, UUID careerId, String status, PageQuery page) {
        String where = """
                where (cast(:q as text) is null or lower(p.first_names || ' ' || p.last_names) like :like or p.id_number like :like or s.code like :like)
                  and (cast(:career as uuid) is null or s.career_id = :career)
                  and (cast(:status as text) is null or s.status = :status)""";
        String like = q == null ? null : "%" + q.toLowerCase() + "%";
        var items = jdbc.sql("""
                        select s.id, s.code, p.first_names || ' ' || p.last_names full_name, p.id_number, c.name career, s.status,
                               ap.code cohort, s.institutional_email
                        from student s join person p on p.id = s.person_id join career c on c.id = s.career_id
                        join academic_period ap on ap.id = s.cohort_period_id
                        """ + where + " order by p.last_names, p.first_names limit :l offset :o")
                .param("q", q).param("like", like).param("career", careerId).param("status", status)
                .param("l", page.size()).param("o", page.offset())
                .query((rs, n) -> new StudentSummary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("full_name"),
                        rs.getString("id_number"), rs.getString("career"), rs.getString("status"), rs.getString("cohort"),
                        rs.getString("institutional_email")))
                .list();
        long total = jdbc.sql("select count(*) from student s join person p on p.id = s.person_id " + where)
                .param("q", q).param("like", like).param("career", careerId).param("status", status).query(Long.class).single();
        return PageResponse.of(items, page, total);
    }

    @Transactional(readOnly = true)
    public List<StatusEvent> history(UUID studentId) {
        return jdbc.sql("select from_status, to_status, process_code, changed_at from student_status_history where student_id = :s order by changed_at")
                .param("s", studentId)
                .query((rs, n) -> new StatusEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                .list();
    }

    private StudentStateMachine machine() {
        return new StudentStateMachine(Set.copyOf(jdbc.sql("select from_status, to_status, requires_process from student_status_transition")
                .query((rs, n) -> new StudentStateMachine.Transition(rs.getString(1), rs.getString(2), rs.getBoolean(3))).list()));
    }
}
