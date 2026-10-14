package ec.unisfera.evaluation.application;

import ec.unisfera.evaluation.domain.ActHasher;
import ec.unisfera.evaluation.domain.GradeMath;
import ec.unisfera.identity.security.CryptoService;
import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal docente: notas por componente con guardado parcial y bloqueo optimista, asistencia por
 * sesión, vista previa y cierre del acta (hash + firma), y rectificaciones autorizadas.
 */
@Service
public class TeachingService {

    public record SectionCard(UUID id, String subjectCode, String subjectName, String sectionCode, String periodCode, int enrolled,
                              int capacity, String actStatus, int sessions) {
    }

    public record GradeCell(UUID componentId, BigDecimal value, long version) {
    }

    public record RosterRow(UUID detailId, UUID studentId, String studentCode, String fullName, int attempt, List<GradeCell> grades,
                            BigDecimal accumulated, BigDecimal finalGrade, BigDecimal attendancePct, String provisionalResult, boolean attendanceRisk) {
    }

    public record Roster(UUID sectionId, String subjectName, String sectionCode, SchemeService.SchemeView scheme, String actStatus, int sessions,
                         List<RosterRow> rows) {
    }

    public record GradeInput(UUID detailId, UUID componentId, BigDecimal value, Long version) {
    }

    public record AttendanceInput(UUID detailId, String status) {
    }

    public record ActLine(UUID detailId, String studentCode, String fullName, BigDecimal finalGrade, BigDecimal attendancePct, String result) {
    }

    public record ActPreview(UUID sectionId, int students, int missingGrades, long approved, long failed, long failedByAttendance,
                             List<ActLine> lines) {
    }

    public record ActView(UUID id, UUID sectionId, String subjectName, String sectionCode, String periodCode, String type, String status,
                          String contentHash, String signature, Instant closedAt, UUID rectifiesActId, UUID supersededById, String reason,
                          List<ActLine> lines) {
    }

    private final JdbcClient jdbc;
    private final SchemeService schemes;
    private final ParameterService parameters;
    private final CryptoService crypto;
    private final NotificationService notifications;
    private final Clock clock;

    public TeachingService(JdbcClient jdbc, SchemeService schemes, ParameterService parameters, CryptoService crypto,
                           NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.schemes = schemes;
        this.parameters = parameters;
        this.crypto = crypto;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SectionCard> sectionsOf(UUID teacherId) {
        return jdbc.sql("""
                        select s.id, sb.code, sb.name, s.code sec, p.code period, s.enrolled_count, s.capacity,
                               coalesce((select a.status from grade_act a where a.section_id = s.id and a.type = 'ORIGINAL'), 'OPEN') act,
                               (select count(*) from class_session cs where cs.section_id = s.id) sessions
                        from section s join subject sb on sb.id = s.subject_id join academic_period p on p.id = s.period_id
                        where s.teacher_id = :t order by p.classes_start desc, sb.code limit 60""")
                .param("t", teacherId)
                .query((rs, n) -> new SectionCard(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getInt(9)))
                .list();
    }

    @Transactional(readOnly = true)
    public Roster roster(UUID sectionId) {
        SchemeService.SchemeView scheme = schemes.forSection(sectionId);
        var header = jdbc.sql("""
                        select sb.name, s.code, coalesce((select a.status from grade_act a where a.section_id = s.id and a.type = 'ORIGINAL'), 'OPEN'),
                               (select count(*) from class_session cs where cs.section_id = s.id)
                        from section s join subject sb on sb.id = s.subject_id where s.id = :s""")
                .param("s", sectionId).query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)})
                .optional().orElseThrow(() -> new NotFoundException("El paralelo"));
        Map<UUID, List<GradeCell>> cells = new HashMap<>();
        jdbc.sql("""
                        select g.enrollment_detail_id, g.component_id, g.value, g.version from grade g
                        join enrollment_detail d on d.id = g.enrollment_detail_id where d.section_id = :s""")
                .param("s", sectionId)
                .query((rs, n) -> cells.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new GradeCell(rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getLong(4)))).list();
        Map<UUID, BigDecimal> attendance = attendanceBySection(sectionId);
        BigDecimal margin = parameters.decimal(ParameterKeys.ATTENDANCE_WARNING_MARGIN);
        List<RosterRow> rows = jdbc.sql("""
                        select d.id, st.id student_id, st.code, p.last_names || ' ' || p.first_names, d.attempt_number
                        from enrollment_detail d join student st on st.id = d.student_id join person p on p.id = st.person_id
                        where d.section_id = :s and d.status = 'ENROLLED' order by p.last_names, p.first_names""")
                .param("s", sectionId)
                .query((rs, n) -> {
                    UUID detail = rs.getObject(1, UUID.class);
                    List<GradeCell> g = cells.getOrDefault(detail, List.of());
                    Map<UUID, BigDecimal> values = new HashMap<>();
                    g.forEach(c -> values.put(c.componentId(), c.value()));
                    BigDecimal att = attendance.getOrDefault(detail, GradeMath.attendancePct(0, 0));
                    var fin = GradeMath.finalGrade(scheme.components(), values);
                    return new RosterRow(detail, rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5), g,
                            GradeMath.weighted(scheme.components(), values), fin.orElse(null), att,
                            fin.map(f -> GradeMath.result(f, att, scheme.scale()).name()).orElse(null),
                            att.compareTo(scheme.scale().minAttendance().add(margin)) < 0);
                })
                .list();
        return new Roster(sectionId, (String) header[0], (String) header[1], scheme, (String) header[2], (int) header[3], rows);
    }

    /** Guardado parcial con bloqueo optimista: si otra persona cambió la nota, responde conflicto (409). */
    @Transactional
    public int saveGrades(UUID sectionId, List<GradeInput> inputs) {
        SchemeService.SchemeView scheme = schemes.forSection(sectionId);
        int saved = 0;
        for (GradeInput in : inputs) {
            GradeMath.validateValue(in.value(), scheme.scale());
            boolean belongs = jdbc.sql("select count(*) from enrollment_detail where id = :d and section_id = :s").param("d", in.detailId())
                    .param("s", sectionId).query(Long.class).single() > 0;
            if (!belongs || scheme.components().stream().noneMatch(c -> c.id().equals(in.componentId()))) {
                throw new BusinessRuleException("RN-11", "La nota no corresponde a este paralelo o a su esquema");
            }
            int n;
            if (in.version() == null) {
                n = jdbc.sql("""
                                insert into grade (id, enrollment_detail_id, component_id, value, recorded_by) values (:id, :d, :c, :v, :by)
                                on conflict (enrollment_detail_id, component_id) do nothing""")
                        .param("id", UuidV7.next(clock)).param("d", in.detailId()).param("c", in.componentId()).param("v", in.value())
                        .param("by", RequestContext.actorId().orElse(null)).update();
            } else {
                n = jdbc.sql("""
                                update grade set value = :v, version = version + 1, recorded_by = :by, recorded_at = now()
                                where enrollment_detail_id = :d and component_id = :c and version = :ver""")
                        .param("v", in.value()).param("by", RequestContext.actorId().orElse(null)).param("d", in.detailId())
                        .param("c", in.componentId()).param("ver", in.version()).update();
            }
            if (n == 0) {
                throw new org.springframework.dao.OptimisticLockingFailureException("La nota cambió mientras la editaba");
            }
            saved++;
        }
        return saved;
    }

    @Transactional
    public UUID createSession(UUID sectionId, LocalDate date, LocalTime startsAt, String topic) {
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into class_session (id, section_id, period_id, session_date, starts_at, topic, recorded_by)
                        select :id, s.id, s.period_id, :d, :t, :topic, :by from section s where s.id = :s""")
                .param("id", id).param("s", sectionId).param("d", Date.valueOf(date)).param("t", Time.valueOf(startsAt)).param("topic", topic)
                .param("by", RequestContext.actorId().orElse(null)).update();
        return id;
    }

    @Transactional
    public int markAttendance(UUID sectionId, UUID sessionId, List<AttendanceInput> marks) {
        UUID periodId = jdbc.sql("select period_id from class_session where id = :id and section_id = :s").param("id", sessionId).param("s", sectionId)
                .query(UUID.class).optional().orElseThrow(() -> new NotFoundException("La sesión de clase"));
        for (AttendanceInput m : marks) {
            jdbc.sql("""
                            insert into attendance (id, period_id, class_session_id, enrollment_detail_id, status, recorded_by)
                            select :id, :p, :ses, d.id, :st, :by from enrollment_detail d where d.id = :d and d.section_id = :s
                            on conflict (class_session_id, enrollment_detail_id, period_id)
                            do update set status = excluded.status, recorded_at = now(), version = attendance.version + 1""")
                    .param("id", UuidV7.next(clock)).param("p", periodId).param("ses", sessionId).param("st", m.status())
                    .param("by", RequestContext.actorId().orElse(null)).param("d", m.detailId()).param("s", sectionId).update();
        }
        return marks.size();
    }

    @Transactional(readOnly = true)
    public ActPreview preview(UUID sectionId) {
        Roster roster = roster(sectionId);
        int components = roster.scheme().components().size();
        int missing = roster.rows().stream().mapToInt(r -> components - r.grades().size()).sum();
        List<ActLine> lines = roster.rows().stream().map(r -> new ActLine(r.detailId(), r.studentCode(), r.fullName(), r.finalGrade(),
                r.attendancePct(), r.provisionalResult())).toList();
        return new ActPreview(sectionId, lines.size(), missing,
                lines.stream().filter(l -> "APPROVED".equals(l.result())).count(),
                lines.stream().filter(l -> "FAILED".equals(l.result())).count(),
                lines.stream().filter(l -> "FAILED_ATTENDANCE".equals(l.result())).count(), lines);
    }

    /** RN-13: solo con todas las notas; calcula resultados, guarda hash y firma. Desde ahí el acta es inmutable (RN-14). */
    @Transactional
    public ActView close(UUID sectionId, String confirmation) {
        if (!parameters.text("evaluation.close_confirmation_phrase").equalsIgnoreCase(confirmation == null ? "" : confirmation.trim())) {
            throw new BusinessRuleException("RN-13", "Escriba la frase de confirmación para cerrar el acta");
        }
        Roster roster = roster(sectionId);
        if (!"OPEN".equals(roster.actStatus())) {
            throw new BusinessRuleException("RN-14", "El acta de este paralelo ya está cerrada");
        }
        List<ActHasher.Line> lines = new ArrayList<>();
        for (RosterRow r : roster.rows()) {
            if (r.finalGrade() == null) {
                throw new BusinessRuleException("RN-13", "Faltan notas de " + r.fullName() + "; el acta solo se cierra completa");
            }
            Map<UUID, BigDecimal> grades = new HashMap<>();
            r.grades().forEach(g -> grades.put(g.componentId(), g.value()));
            lines.add(new ActHasher.Line(r.detailId(), grades, r.finalGrade(), r.attendancePct(),
                    GradeMath.result(r.finalGrade(), r.attendancePct(), roster.scheme().scale()).name()));
        }
        return persistClosedAct(sectionId, roster, lines, "ORIGINAL", null, null, null);
    }

    /** RN-14: el acta cerrada no se edita; se emite una rectificación autorizada y ambas quedan visibles. */
    @Transactional
    public ActView rectify(UUID actId, UUID authorizationId, String reason, Map<UUID, Map<UUID, BigDecimal>> corrections) {
        var original = jdbc.sql("select section_id, status from grade_act where id = :id for update").param("id", actId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}).optional()
                .orElseThrow(() -> new NotFoundException("El acta"));
        if (!"CLOSED".equals(original[1])) {
            throw new BusinessRuleException("RN-14", "Solo se rectifica un acta cerrada y vigente");
        }
        UUID sectionId = (UUID) original[0];
        Roster roster = roster(sectionId);
        SchemeService.SchemeView scheme = roster.scheme();
        Map<UUID, Map<UUID, BigDecimal>> current = new HashMap<>();
        Map<UUID, BigDecimal> attendanceByDetail = new HashMap<>();
        jdbc.sql("select enrollment_detail_id, component_grades::text, attendance_pct from grade_act_line where act_id = :a").param("a", actId)
                .query((rs, n) -> {
                    UUID d = rs.getObject(1, UUID.class);
                    current.put(d, parseGrades(rs.getString(2)));
                    attendanceByDetail.put(d, rs.getBigDecimal(3));
                    return d;
                }).list();
        List<ActHasher.Line> lines = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, BigDecimal>> e : current.entrySet()) {
            Map<UUID, BigDecimal> grades = new HashMap<>(e.getValue());
            Map<UUID, BigDecimal> fix = corrections.getOrDefault(e.getKey(), Map.of());
            fix.values().forEach(v -> GradeMath.validateValue(v, scheme.scale()));
            grades.putAll(fix);
            BigDecimal fin = GradeMath.finalGrade(scheme.components(), grades).orElseThrow();
            BigDecimal att = attendanceByDetail.get(e.getKey());
            lines.add(new ActHasher.Line(e.getKey(), grades, fin, att, GradeMath.result(fin, att, scheme.scale()).name()));
        }
        return persistClosedAct(sectionId, roster, lines, "RECTIFICATION", actId, authorizationId, reason);
    }

    private ActView persistClosedAct(UUID sectionId, Roster roster, List<ActHasher.Line> lines, String type, UUID rectifies, UUID authorization,
                                     String reason) {
        UUID actId = UuidV7.next(clock);
        UUID actor = RequestContext.actorId().orElseThrow(() -> new BusinessRuleException("RN-13", "El cierre exige un responsable"));
        jdbc.sql("""
                        insert into grade_act (id, section_id, period_id, type, rectifies_act_id, authorization_id, reason)
                        select :id, s.id, s.period_id, :t, :r, :auth, :reason from section s where s.id = :s""")
                .param("id", actId).param("s", sectionId).param("t", type).param("r", rectifies).param("auth", authorization).param("reason", reason).update();
        for (ActHasher.Line l : lines) {
            jdbc.sql("""
                            insert into grade_act_line (id, act_id, enrollment_detail_id, student_id, subject_id, component_grades, final_grade,
                                                        attendance_pct, passing_grade, min_attendance_pct, result)
                            select :id, :a, d.id, d.student_id, d.subject_id, cast(:cg as jsonb), :f, :att, :pass, :minAtt, :res
                            from enrollment_detail d where d.id = :d""")
                    .param("id", UuidV7.next(clock)).param("a", actId).param("d", l.enrollmentDetailId()).param("cg", json(l.componentGrades()))
                    .param("f", l.finalGrade()).param("att", l.attendancePct()).param("pass", roster.scheme().scale().passing())
                    .param("minAtt", roster.scheme().scale().minAttendance()).param("res", l.result()).update();
        }
        Instant closedAt = clock.instant();
        String hash = ActHasher.hash(sectionId, lines);
        String signature = crypto.sign(hash + "|" + actor + "|" + closedAt);
        jdbc.sql("""
                        update grade_act set status = 'CLOSED', content_hash = :h, teacher_signature = :sig, closed_by = :by, closed_at = :at
                        where id = :id""")
                .param("h", hash).param("sig", signature).param("by", actor).param("at", Timestamp.from(closedAt)).param("id", actId).update();
        notifyStudents(actId, roster.subjectName());
        return act(actId);
    }

    private void notifyStudents(UUID actId, String subjectName) {
        jdbc.sql("""
                        select u.id, l.result from grade_act_line l join student s on s.id = l.student_id
                        join user_account u on u.person_id = s.person_id where l.act_id = :a""")
                .param("a", actId).query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}).list()
                .forEach(r -> notifications.notifyUser((UUID) r[0], "GRADES", "Acta cerrada: " + subjectName,
                        "Su resultado definitivo ya consta en el kardex: " + ("APPROVED".equals(r[1]) ? "aprobada" : "reprobada"), "/estudiante/kardex"));
    }

    @Transactional(readOnly = true)
    public ActView act(UUID actId) {
        List<ActLine> lines = jdbc.sql("""
                        select l.enrollment_detail_id, st.code, p.last_names || ' ' || p.first_names, l.final_grade, l.attendance_pct, l.result
                        from grade_act_line l join student st on st.id = l.student_id join person p on p.id = st.person_id
                        where l.act_id = :a order by p.last_names""")
                .param("a", actId)
                .query((rs, n) -> new ActLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getString(6)))
                .list();
        return jdbc.sql("""
                        select a.id, a.section_id, sb.name, s.code, p.code, a.type, a.status, a.content_hash, a.teacher_signature, a.closed_at,
                               a.rectifies_act_id, a.superseded_by_id, a.reason
                        from grade_act a join section s on s.id = a.section_id join subject sb on sb.id = s.subject_id
                        join academic_period p on p.id = a.period_id where a.id = :a""")
                .param("a", actId)
                .query((rs, n) -> new ActView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getObject(11, UUID.class), rs.getObject(12, UUID.class),
                        rs.getString(13), lines))
                .optional().orElseThrow(() -> new NotFoundException("El acta"));
    }

    @Transactional(readOnly = true)
    public List<ActView> actsOfSection(UUID sectionId) {
        return jdbc.sql("select id from grade_act where section_id = :s order by created_at").param("s", sectionId).query(UUID.class).list()
                .stream().map(this::act).toList();
    }

    @Transactional(readOnly = true)
    public List<ActView> actsOfTeacher(UUID teacherId) {
        return jdbc.sql("""
                        select a.id from grade_act a join section s on s.id = a.section_id where s.teacher_id = :t
                        order by a.created_at desc limit 40""").param("t", teacherId).query(UUID.class).list()
                .stream().map(this::act).toList();
    }

    public UUID sectionOfAct(UUID actId) {
        return jdbc.sql("select section_id from grade_act where id = :id").param("id", actId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("El acta"));
    }

    Map<UUID, BigDecimal> attendanceBySection(UUID sectionId) {
        List<String> counted = parameters.list("attendance.counted_statuses");
        long total = jdbc.sql("select count(*) from class_session where section_id = :s").param("s", sectionId).query(Long.class).single();
        Map<UUID, BigDecimal> result = new HashMap<>();
        jdbc.sql("""
                        select d.id, count(a.id) filter (where a.status in (:counted))
                        from enrollment_detail d left join attendance a on a.enrollment_detail_id = d.id and a.period_id = d.period_id
                        where d.section_id = :s group by d.id""")
                .param("s", sectionId).param("counted", counted)
                .query((rs, n) -> result.put(rs.getObject(1, UUID.class), GradeMath.attendancePct(rs.getLong(2), total))).list();
        return result;
    }

    private static String json(Map<UUID, BigDecimal> grades) {
        StringBuilder sb = new StringBuilder("{");
        grades.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue().toPlainString());
        });
        return sb.append('}').toString();
    }

    private static Map<UUID, BigDecimal> parseGrades(String json) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([0-9a-f-]{36})\"\\s*:\\s*([0-9.]+)").matcher(json);
        while (m.find()) {
            result.put(UUID.fromString(m.group(1)), new BigDecimal(m.group(2)));
        }
        return result;
    }
}
