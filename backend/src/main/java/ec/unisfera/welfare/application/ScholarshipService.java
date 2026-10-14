package ec.unisfera.welfare.application;

import ec.unisfera.evaluation.domain.GradeMath;
import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.file.FileStorageService;
import ec.unisfera.welfare.domain.WelfareRules;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Becas de punta a punta: tipos, convocatorias con documentos y criterios, postulación en
 * borrador → enviada (RN-18/19), evaluación con puntaje y resolución del comité (RN-20),
 * adjudicación sin retroactividad (RN-21), seguimiento por período y apelación (RN-22).
 */
@Service
public class ScholarshipService {

    public record TypeInput(String code, String name, String description, String mode, BigDecimal value, BigDecimal minAverage,
                            BigDecimal minAttendance, BigDecimal partialMinAverage, BigDecimal partialValue, boolean renewable,
                            List<String> coveredConcepts) {
    }

    public record DocumentInput(String code, String name, boolean mandatory) {
    }

    public record CriterionInput(String name, BigDecimal maxScore, BigDecimal weight) {
    }

    public record CallView(UUID id, String typeCode, String typeName, String mode, BigDecimal value, String periodCode, String name, int quota,
                           Instant opensAt, Instant closesAt, LocalDate resultsBy, String status, List<DocumentInput> documents, long applications) {
    }

    public record ApplicationView(UUID id, UUID callId, String callName, String typeName, UUID studentId, String studentName, String status,
                                  String statement, Instant submittedAt, BigDecimal totalScore, List<UploadedDoc> documents, List<String> missingDocuments) {
    }

    public record UploadedDoc(UUID requiredDocumentId, String code, String name, UUID fileId, String fileName) {
    }

    public record AwardView(UUID id, String typeName, String mode, BigDecimal value, String startPeriod, String status, String conditions,
                            List<FollowupView> followups) {
    }

    public record FollowupView(UUID id, String periodCode, BigDecimal average, BigDecimal attendance, String outcome, String reason, String appealStatus) {
    }

    public record Member(String name, String role, UUID userId) {
    }

    public record TypeView(UUID id, String code, String name, String mode, BigDecimal value) {
    }

    public record CriterionView(UUID id, String name, BigDecimal maxScore, BigDecimal weight) {
    }

    private final JdbcClient jdbc;
    private final FileStorageService files;
    private final NotificationService notifications;
    private final Clock clock;

    public ScholarshipService(JdbcClient jdbc, FileStorageService files, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.files = files;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ------------------------------------------------------------ catálogo
    @Transactional
    public UUID createType(TypeInput t) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into scholarship_type (id, code, name, description, mode, value, min_average, min_attendance_pct,
                                                      partial_min_average, partial_value, renewable)
                        values (:id, :c, :n, :d, :m, :v, :ma, :mat, :pma, :pv, :r)""")
                .param("id", id).param("c", t.code()).param("n", t.name()).param("d", t.description()).param("m", t.mode()).param("v", t.value())
                .param("ma", t.minAverage()).param("mat", t.minAttendance()).param("pma", t.partialMinAverage()).param("pv", t.partialValue())
                .param("r", t.renewable()).update();
        for (String concept : t.coveredConcepts()) {
            jdbc.sql("insert into scholarship_type_concept (scholarship_type_id, concept_id) select :t, id from charge_concept where code = :c")
                    .param("t", id).param("c", concept).update();
        }
        return id;
    }

    @Transactional
    public UUID createCall(UUID typeId, UUID periodId, String name, int quota, Instant opensAt, Instant closesAt, LocalDate resultsBy,
                           List<DocumentInput> documents, List<CriterionInput> criteria) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into scholarship_call (id, type_id, period_id, name, quota, opens_at, closes_at, results_by)
                        values (:id, :t, :p, :n, :q, :o, :c, :r)""")
                .param("id", id).param("t", typeId).param("p", periodId).param("n", name).param("q", quota).param("o", Timestamp.from(opensAt))
                .param("c", Timestamp.from(closesAt)).param("r", resultsBy).update();
        for (DocumentInput d : documents) {
            jdbc.sql("insert into call_required_document (id, call_id, code, name, mandatory) values (:id, :c, :code, :n, :m)")
                    .param("id", UuidV7.next(clock)).param("c", id).param("code", d.code()).param("n", d.name()).param("m", d.mandatory()).update();
        }
        int order = 1;
        for (CriterionInput c : criteria) {
            jdbc.sql("insert into evaluation_criterion (id, call_id, name, max_score, weight, sort_order) values (:id, :c, :n, :m, :w, :o)")
                    .param("id", UuidV7.next(clock)).param("c", id).param("n", c.name()).param("m", c.maxScore()).param("w", c.weight())
                    .param("o", order++).update();
        }
        return id;
    }

    @Transactional(readOnly = true)
    public List<TypeView> types() {
        return jdbc.sql("select id, code, name, mode, value from scholarship_type order by name")
                .query((rs, n) -> new TypeView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5))).list();
    }

    public List<CriterionView> criteria(UUID callId) {
        return jdbc.sql("select id, name, max_score, weight from evaluation_criterion where call_id = :c order by sort_order").param("c", callId)
                .query((rs, n) -> new CriterionView(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4))).list();
    }

    public List<CallView> calls(boolean onlyOpen) {
        return jdbc.sql("""
                        select c.id, t.code, t.name, t.mode, t.value, p.code, c.name, c.quota, c.opens_at, c.closes_at, c.results_by, c.status,
                               (select count(*) from scholarship_application a where a.call_id = c.id and a.status <> 'DRAFT')
                        from scholarship_call c join scholarship_type t on t.id = c.type_id join academic_period p on p.id = c.period_id
                        where not :open or (c.status = 'OPEN' and now() between c.opens_at and c.closes_at)
                        order by c.closes_at desc limit 50""")
                .param("open", onlyOpen)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new CallView(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7),
                            rs.getInt(8), rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant(), rs.getDate(11).toLocalDate(),
                            rs.getString(12), documents(id), rs.getLong(13));
                })
                .list();
    }

    private List<DocumentInput> documents(UUID callId) {
        return jdbc.sql("select code, name, mandatory from call_required_document where call_id = :c order by code").param("c", callId)
                .query((rs, n) -> new DocumentInput(rs.getString(1), rs.getString(2), rs.getBoolean(3))).list();
    }

    // ------------------------------------------------------- estudiante
    @Transactional
    public UUID draft(UUID studentId, UUID callId, String statement) {
        boolean open = jdbc.sql("select count(*) from scholarship_call where id = :c and status = 'OPEN' and now() between opens_at and closes_at")
                .param("c", callId).query(Long.class).single() > 0;
        if (!open) {
            throw new BusinessRuleException("RN-18", "La convocatoria no está abierta");
        }
        UUID id = UuidV7.next(clock);
        jdbc.sql("insert into scholarship_application (id, call_id, student_id, statement) values (:id, :c, :s, :st)")
                .param("id", id).param("c", callId).param("s", studentId).param("st", statement).update();
        return id;
    }

    @Transactional
    public void updateDraft(UUID studentId, UUID applicationId, String statement) {
        requireOwn(studentId, applicationId);
        jdbc.sql("update scholarship_application set statement = :st where id = :id").param("st", statement).param("id", applicationId).update();
    }

    @Transactional
    public void attach(UUID studentId, UUID personId, UUID applicationId, String documentCode, byte[] content, String fileName) {
        requireOwn(studentId, applicationId);
        UUID required = jdbc.sql("""
                        select r.id from call_required_document r join scholarship_application a on a.call_id = r.call_id
                        where a.id = :a and r.code = :code""")
                .param("a", applicationId).param("code", documentCode).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("El documento requerido " + documentCode));
        FileStorageService.StoredFile file = files.store(content, fileName, "SCHOLARSHIP", personId);
        jdbc.sql("update application_document set status = 'REPLACED' where application_id = :a and required_document_id = :r and status = 'ACTIVE'")
                .param("a", applicationId).param("r", required).update();
        jdbc.sql("insert into application_document (id, application_id, required_document_id, file_id) values (:id, :a, :r, :f)")
                .param("id", UuidV7.next(clock)).param("a", applicationId).param("r", required).param("f", file.id()).update();
    }

    /** RN-18: la base verifica los documentos obligatorios; sin ellos la postulación sigue en borrador. */
    @Transactional
    public void submit(UUID studentId, UUID applicationId) {
        requireOwn(studentId, applicationId);
        List<String> missing = missingDocuments(applicationId);
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("RN-18", "Faltan documentos obligatorios: " + String.join(", ", missing));
        }
        jdbc.sql("update scholarship_application set status = 'SUBMITTED', submitted_at = now() where id = :id").param("id", applicationId).update();
    }

    /** RN-19: el estudiante puede desistir en cualquier momento antes de la resolución; queda registrado. */
    @Transactional
    public void withdraw(UUID studentId, UUID applicationId, String reason) {
        requireOwn(studentId, applicationId);
        jdbc.sql("update scholarship_application set status = 'WITHDRAWN', withdrawn_at = now(), withdrawal_reason = :r where id = :id")
                .param("r", reason).param("id", applicationId).update();
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> applicationsOf(UUID studentId) {
        return jdbc.sql("select id from scholarship_application where student_id = :s order by created_at desc limit 30").param("s", studentId)
                .query(UUID.class).list().stream().map(this::application).toList();
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> applicationsOfCall(UUID callId) {
        return jdbc.sql("select id from scholarship_application where call_id = :c and status <> 'DRAFT' order by total_score desc nulls last limit 200")
                .param("c", callId).query(UUID.class).list().stream().map(this::application).toList();
    }

    public ApplicationView application(UUID id) {
        List<UploadedDoc> docs = jdbc.sql("""
                        select r.id, r.code, r.name, d.file_id, f.original_name from application_document d
                        join call_required_document r on r.id = d.required_document_id join stored_file f on f.id = d.file_id
                        where d.application_id = :a and d.status = 'ACTIVE' order by r.code""")
                .param("a", id)
                .query((rs, n) -> new UploadedDoc(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5)))
                .list();
        return jdbc.sql("""
                        select a.id, a.call_id, c.name, t.name, a.student_id, p.first_names || ' ' || p.last_names, a.status, a.statement,
                               a.submitted_at, a.total_score
                        from scholarship_application a join scholarship_call c on c.id = a.call_id join scholarship_type t on t.id = c.type_id
                        join student s on s.id = a.student_id join person p on p.id = s.person_id where a.id = :a""")
                .param("a", id)
                .query((rs, n) -> new ApplicationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getBigDecimal(10), docs, missingDocuments(id)))
                .optional().orElseThrow(() -> new NotFoundException("La postulación"));
    }

    private List<String> missingDocuments(UUID applicationId) {
        return jdbc.sql("""
                        select r.name from call_required_document r join scholarship_application a on a.call_id = r.call_id
                        where a.id = :a and r.mandatory and not exists (
                          select 1 from application_document d where d.application_id = a.id and d.required_document_id = r.id and d.status = 'ACTIVE')
                        order by r.name""")
                .param("a", applicationId).query(String.class).list();
    }

    // ---------------------------------------------------------- evaluación
    @Transactional
    public BigDecimal score(UUID applicationId, Map<UUID, BigDecimal> scores, UUID evaluatorId) {
        jdbc.sql("update scholarship_application set status = 'UNDER_REVIEW' where id = :id and status = 'SUBMITTED'").param("id", applicationId).update();
        for (Map.Entry<UUID, BigDecimal> e : scores.entrySet()) {
            jdbc.sql("insert into application_score (id, application_id, criterion_id, score, evaluator_id) values (:id, :a, :c, :s, :e)")
                    .param("id", UuidV7.next(clock)).param("a", applicationId).param("c", e.getKey()).param("s", e.getValue()).param("e", evaluatorId).update();
        }
        List<WelfareRules.Criterion> criteria = jdbc.sql("""
                        select c.id, c.max_score, c.weight from evaluation_criterion c join scholarship_application a on a.call_id = c.call_id where a.id = :a""")
                .param("a", applicationId).query((rs, n) -> new WelfareRules.Criterion(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3)))
                .list();
        Map<UUID, BigDecimal> all = new HashMap<>();
        jdbc.sql("select criterion_id, score from application_score where application_id = :a").param("a", applicationId)
                .query((rs, n) -> all.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
        BigDecimal total = WelfareRules.totalScore(criteria, all);
        jdbc.sql("update scholarship_application set total_score = :t where id = :id").param("t", total).param("id", applicationId).update();
        return total;
    }

    /** RN-20: resolución del comité con acta, fecha y miembros; RN-21: la beca aprobada rige desde el período de la convocatoria. */
    @Transactional
    public UUID resolve(UUID callId, String actNumber, LocalDate sessionDate, String summary, List<Member> members, List<UUID> approved, List<UUID> rejected) {
        UUID resolution = UuidV7.next(clock);
        jdbc.sql("insert into committee_resolution (id, call_id, act_number, session_date, summary) values (:id, :c, :a, :d, :s)")
                .param("id", resolution).param("c", callId).param("a", actNumber).param("d", sessionDate).param("s", summary).update();
        for (Member m : members) {
            jdbc.sql("insert into committee_member (id, resolution_id, member_name, member_role, user_id) values (:id, :r, :n, :role, :u)")
                    .param("id", UuidV7.next(clock)).param("r", resolution).param("n", m.name()).param("role", m.role()).param("u", m.userId()).update();
        }
        for (UUID app : approved) {
            decide(app, resolution, "APPROVED");
            jdbc.sql("""
                            insert into scholarship_award (id, application_id, student_id, type_id, start_period_id, mode, value, conditions)
                            select :id, a.id, a.student_id, c.type_id, c.period_id, t.mode, t.value,
                                   'Mantener promedio ≥ ' || coalesce(t.min_average::text, '—') || ' y asistencia ≥ ' || coalesce(t.min_attendance_pct::text, '—') || ' %'
                            from scholarship_application a join scholarship_call c on c.id = a.call_id join scholarship_type t on t.id = c.type_id
                            where a.id = :a""")
                    .param("id", UuidV7.next(clock)).param("a", app).update();
            notifyStudent(app, "Beca aprobada", "El comité aprobó su postulación. El descuento se aplicará en sus rubros desde el período de la convocatoria.");
        }
        for (UUID app : rejected) {
            decide(app, resolution, "REJECTED");
            notifyStudent(app, "Resultado de su postulación", "El comité no aprobó su postulación en esta convocatoria. Puede revisar el acta en Bienestar.");
        }
        jdbc.sql("update scholarship_call set status = 'RESOLVED' where id = :c").param("c", callId).update();
        return resolution;
    }

    private void decide(UUID applicationId, UUID resolution, String status) {
        jdbc.sql("update scholarship_application set status = 'UNDER_REVIEW' where id = :id and status = 'SUBMITTED'").param("id", applicationId).update();
        jdbc.sql("update scholarship_application set status = :st, committee_resolution_id = :r, decided_at = now() where id = :id")
                .param("st", status).param("r", resolution).param("id", applicationId).update();
    }

    // --------------------------------------------------------- seguimiento
    /** RN-22: evalúa el mantenimiento de cada beca activa con el promedio y la asistencia del período (actas cerradas). */
    @Transactional
    public int followup(UUID periodId) {
        List<Object[]> awards = jdbc.sql("""
                        select a.id, a.student_id, t.min_average, t.min_attendance_pct, t.partial_min_average, t.partial_value
                        from scholarship_award a join scholarship_type t on t.id = a.type_id
                        where a.status = 'ACTIVE' and not exists (select 1 from scholarship_followup f where f.award_id = a.id and f.period_id = :p)""")
                .param("p", periodId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        new WelfareRules.MaintenanceRule(rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6))})
                .list();
        int count = 0;
        for (Object[] a : awards) {
            UUID awardId = (UUID) a[0];
            UUID studentId = (UUID) a[1];
            List<GradeMath.HoursGrade> grades = jdbc.sql("""
                            select s.total_hours, l.final_grade from grade_act_line l join grade_act g on g.id = l.act_id and g.status = 'CLOSED'
                            join subject s on s.id = l.subject_id where l.student_id = :s and g.period_id = :p""")
                    .param("s", studentId).param("p", periodId)
                    .query((rs, n) -> new GradeMath.HoursGrade(rs.getInt(1), rs.getBigDecimal(2))).list();
            if (grades.isEmpty()) {
                continue;
            }
            BigDecimal average = GradeMath.weightedByHours(grades).orElse(null);
            BigDecimal attendance = jdbc.sql("""
                            select round(avg(l.attendance_pct), 2) from grade_act_line l join grade_act g on g.id = l.act_id and g.status = 'CLOSED'
                            where l.student_id = :s and g.period_id = :p""")
                    .param("s", studentId).param("p", periodId).query(BigDecimal.class).single();
            WelfareRules.Maintenance m = WelfareRules.maintenance((WelfareRules.MaintenanceRule) a[2], average, attendance);
            jdbc.sql("""
                            insert into scholarship_followup (id, award_id, period_id, average, attendance_pct, complies, outcome, reason, evaluated_by)
                            values (:id, :a, :p, :avg, :att, :c, :o, :r, app_actor())""")
                    .param("id", UuidV7.next(clock)).param("a", awardId).param("p", periodId).param("avg", average).param("att", attendance)
                    .param("c", m.complies()).param("o", m.outcome().name()).param("r", m.reason()).update();
            if (m.outcome() == WelfareRules.Outcome.SUSPENDED) {
                jdbc.sql("update scholarship_award set status = 'SUSPENDED' where id = :a").param("a", awardId).update();
                notifyStudentOfAward(awardId, "Beca suspendida", m.reason() + ". Puede presentar una apelación desde 'Mis becas'.");
            } else if (m.outcome() == WelfareRules.Outcome.REDUCED) {
                jdbc.sql("update scholarship_award a set value = t.partial_value from scholarship_type t where t.id = a.type_id and a.id = :a")
                        .param("a", awardId).update();
                notifyStudentOfAward(awardId, "Beca reducida", m.reason());
            }
            count++;
        }
        return count;
    }

    @Transactional
    public UUID appeal(UUID studentId, UUID followupId, String statement) {
        boolean own = jdbc.sql("""
                        select count(*) from scholarship_followup f join scholarship_award a on a.id = f.award_id
                        where f.id = :f and a.student_id = :s and f.outcome <> 'MAINTAINED'""")
                .param("f", followupId).param("s", studentId).query(Long.class).single() > 0;
        if (!own) {
            throw new BusinessRuleException("RN-22", "Solo se apela un seguimiento propio que no mantuvo la beca");
        }
        UUID id = UuidV7.next(clock);
        jdbc.sql("insert into scholarship_appeal (id, followup_id, statement) values (:id, :f, :s)").param("id", id).param("f", followupId)
                .param("s", statement).update();
        return id;
    }

    @Transactional
    public void decideAppeal(UUID appealId, boolean accept, String note) {
        UUID award = jdbc.sql("""
                        update scholarship_appeal ap set status = :st, decided_at = now(), decided_by = app_actor(), decision_note = :n
                        from scholarship_followup f where f.id = ap.followup_id and ap.id = :id and ap.status = 'FILED' returning f.award_id""")
                .param("st", accept ? "ACCEPTED" : "REJECTED").param("n", note).param("id", appealId).query(UUID.class).optional()
                .orElseThrow(() -> new BusinessRuleException("RN-22", "La apelación no existe o ya fue resuelta"));
        if (accept) {
            jdbc.sql("update scholarship_award set status = 'ACTIVE' where id = :a and status = 'SUSPENDED'").param("a", award).update();
        }
    }

    @Transactional(readOnly = true)
    public List<AwardView> awardsOf(UUID studentId) {
        return jdbc.sql("""
                        select a.id, t.name, a.mode, a.value, p.code, a.status, a.conditions from scholarship_award a
                        join scholarship_type t on t.id = a.type_id join academic_period p on p.id = a.start_period_id
                        where a.student_id = :s order by p.classes_start desc""")
                .param("s", studentId)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new AwardView(id, rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            followups(id));
                })
                .list();
    }

    private List<FollowupView> followups(UUID awardId) {
        return jdbc.sql("""
                        select f.id, p.code, f.average, f.attendance_pct, f.outcome, f.reason, ap.status from scholarship_followup f
                        join academic_period p on p.id = f.period_id left join scholarship_appeal ap on ap.followup_id = f.id
                        where f.award_id = :a order by p.classes_start""")
                .param("a", awardId)
                .query((rs, n) -> new FollowupView(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)))
                .list();
    }

    private void requireOwn(UUID studentId, UUID applicationId) {
        boolean own = jdbc.sql("select count(*) from scholarship_application where id = :id and student_id = :s").param("id", applicationId)
                .param("s", studentId).query(Long.class).single() > 0;
        if (!own) {
            throw new ec.unisfera.shared.domain.ForbiddenException("application:" + applicationId);
        }
    }

    private void notifyStudent(UUID applicationId, String title, String body) {
        jdbc.sql("""
                        select u.id from scholarship_application a join student s on s.id = a.student_id
                        join user_account u on u.person_id = s.person_id where a.id = :a""")
                .param("a", applicationId).query(UUID.class).optional()
                .ifPresent(u -> notifications.notifyUser(u, "SCHOLARSHIP", title, body, "/estudiante/becas"));
    }

    private void notifyStudentOfAward(UUID awardId, String title, String body) {
        jdbc.sql("""
                        select u.id from scholarship_award a join student s on s.id = a.student_id
                        join user_account u on u.person_id = s.person_id where a.id = :a""")
                .param("a", awardId).query(UUID.class).optional()
                .ifPresent(u -> notifications.notifyUser(u, "SCHOLARSHIP", title, body, "/estudiante/becas"));
    }
}
