package ec.unisfera.requests.application;

import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Solicitudes del estudiante con plazo de respuesta parametrizado y flujo de aprobación con historial. */
@Service
public class StudentRequestService {

    public record RequestView(UUID id, UUID studentId, String studentName, String type, String subject, String detail, String status, Instant createdAt,
                              Instant dueAt, boolean overdue, String resolution, List<EventView> events) {
    }

    public record EventView(String fromStatus, String toStatus, String note, Instant occurredAt) {
    }

    private static final Map<String, Set<String>> FLOW = Map.of(
            "SUBMITTED", Set.of("IN_REVIEW", "APPROVED", "REJECTED", "CANCELLED"),
            "IN_REVIEW", Set.of("APPROVED", "REJECTED"));

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final NotificationService notifications;
    private final Clock clock;

    public StudentRequestService(JdbcClient jdbc, ParameterService parameters, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public UUID create(UUID studentId, String type, String subject, String detail) {
        UUID id = UuidV7.next(clock);
        Instant due = clock.instant().plus(Duration.ofDays(parameters.integer(ParameterKeys.REQUEST_DUE_DAYS)));
        jdbc.sql("insert into student_request (id, student_id, type, subject, detail, due_at) values (:id, :s, :t, :sub, :d, :due)")
                .param("id", id).param("s", studentId).param("t", type).param("sub", subject).param("d", detail).param("due", Timestamp.from(due)).update();
        return id;
    }

    @Transactional
    public void cancel(UUID studentId, UUID requestId) {
        int n = jdbc.sql("update student_request set status = 'CANCELLED' where id = :id and student_id = :s and status = 'SUBMITTED'")
                .param("id", requestId).param("s", studentId).update();
        if (n == 0) {
            throw new BusinessRuleException("REQUEST", "Solo se cancela una solicitud propia que aún no está en revisión");
        }
    }

    @Transactional
    public void transition(UUID requestId, String to, String resolution) {
        var current = jdbc.sql("select status, student_id from student_request where id = :id for update").param("id", requestId)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getObject(2, UUID.class)}).optional()
                .orElseThrow(() -> new NotFoundException("La solicitud"));
        if (!FLOW.getOrDefault((String) current[0], Set.of()).contains(to)) {
            throw new BusinessRuleException("REQUEST", "Transición no permitida (" + current[0] + " → " + to + ")");
        }
        boolean final_ = Set.of("APPROVED", "REJECTED").contains(to);
        jdbc.sql("""
                        update student_request set status = :to, resolution = :r, resolved_at = case when :final then now() end,
                               resolved_by = case when :final then app_actor() end where id = :id""")
                .param("to", to).param("r", resolution).param("final", final_).param("id", requestId).update();
        if (final_) {
            jdbc.sql("select u.id from student s join user_account u on u.person_id = s.person_id where s.id = :s").param("s", current[1])
                    .query(UUID.class).optional()
                    .ifPresent(u -> notifications.notifyUser(u, "REQUEST", "Su solicitud fue " + ("APPROVED".equals(to) ? "aprobada" : "negada"),
                            resolution == null ? "" : resolution, "/estudiante/solicitudes"));
        }
    }

    @Transactional(readOnly = true)
    public List<RequestView> mine(UUID studentId) {
        return jdbc.sql("select id from student_request where student_id = :s order by created_at desc limit 50").param("s", studentId)
                .query(UUID.class).list().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestView> queue(String status, PageQuery page) {
        List<UUID> ids = jdbc.sql("""
                        select id from student_request where (cast(:st as text) is null or status = :st) order by due_at limit :l offset :o""")
                .param("st", status).param("l", page.size()).param("o", page.offset()).query(UUID.class).list();
        long total = jdbc.sql("select count(*) from student_request where (cast(:st as text) is null or status = :st)").param("st", status)
                .query(Long.class).single();
        return PageResponse.of(ids.stream().map(this::view).toList(), page, total);
    }

    public RequestView view(UUID id) {
        List<EventView> events = jdbc.sql("select from_status, to_status, note, occurred_at from student_request_event where request_id = :r order by occurred_at")
                .param("r", id).query((rs, n) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())).list();
        Instant now = clock.instant();
        return jdbc.sql("""
                        select r.id, r.student_id, p.first_names || ' ' || p.last_names, r.type, r.subject, r.detail, r.status, r.created_at, r.due_at, r.resolution
                        from student_request r join student s on s.id = r.student_id join person p on p.id = s.person_id where r.id = :id""")
                .param("id", id)
                .query((rs, n) -> new RequestView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant(),
                        Set.of("SUBMITTED", "IN_REVIEW").contains(rs.getString(7)) && rs.getTimestamp(9).toInstant().isBefore(now),
                        rs.getString(10), events))
                .optional().orElseThrow(() -> new NotFoundException("La solicitud"));
    }
}
