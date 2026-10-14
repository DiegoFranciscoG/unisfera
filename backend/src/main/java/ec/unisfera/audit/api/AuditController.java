package ec.unisfera.audit.api;

import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auditoría")
@RestController
public class AuditController {

    public record AuditEntry(UUID id, Instant occurredAt, String tableName, String recordId, String action, String oldValues,
                             String newValues, UUID actorId, String actorName, String actorIp, String requestId, String detail) {
    }

    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @RequiresPermission("AUDIT_READ")
    @GetMapping("/api/audit")
    public PageResponse<AuditEntry> search(@RequestParam(required = false) String table, @RequestParam(required = false) String recordId,
                                           @RequestParam(required = false) String action, @RequestParam(required = false) UUID actor,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageQuery q = PageQuery.of(page, size);
        String where = """
                where (cast(:table as text) is null or a.table_name = :table)
                  and (cast(:record as text) is null or a.record_id = :record)
                  and (cast(:action as text) is null or a.action = :action)
                  and (cast(:actor as uuid) is null or a.actor_id = :actor)""";
        var items = jdbc.sql("""
                        select a.id, a.occurred_at, a.table_name, a.record_id, a.action, a.old_values::text old_values,
                               a.new_values::text new_values, a.actor_id, p.first_names || ' ' || p.last_names actor_name,
                               a.actor_ip, a.request_id, a.detail
                        from audit_log a left join user_account u on u.id = a.actor_id left join person p on p.id = u.person_id
                        """ + where + " order by a.occurred_at desc limit :l offset :o")
                .param("table", table).param("record", recordId).param("action", action).param("actor", actor)
                .param("l", q.size()).param("o", q.offset())
                .query((rs, n) -> new AuditEntry(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("table_name"), rs.getString("record_id"), rs.getString("action"), rs.getString("old_values"),
                        rs.getString("new_values"), rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                        rs.getString("actor_ip"), rs.getString("request_id"), rs.getString("detail")))
                .list();
        long total = jdbc.sql("select count(*) from audit_log a " + where)
                .param("table", table).param("record", recordId).param("action", action).param("actor", actor)
                .query(Long.class).single();
        return PageResponse.of(items, q, total);
    }
}
