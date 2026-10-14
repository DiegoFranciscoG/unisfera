package ec.unisfera.audit.application;

import ec.unisfera.shared.web.RequestContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registros de auditoría que no nacen de un trigger: intentos rechazados (LEY-2, RN-14, RN-28),
 * lecturas de datos sensibles (RN-23) y exportaciones. Corren en su propia transacción para
 * sobrevivir al rollback de la operación que falló.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(String target, String recordId, String detail) {
        insert("DENIED", target, recordId, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSensitiveRead(String table, UUID recordId, String detail) {
        insert("READ_SENSITIVE", table, recordId == null ? null : recordId.toString(), detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExport(String report, String detail) {
        insert("EXPORT", report, null, detail);
    }

    private void insert(String action, String table, String recordId, String detail) {
        jdbc.sql("""
                        insert into audit_log (table_name, record_id, action, actor_id, actor_ip, request_id, detail)
                        values (:table, :record, :action, :actor, :ip, :request, :detail)
                        """)
                .param("table", table)
                .param("record", recordId)
                .param("action", action)
                .param("actor", RequestContext.actorId().orElse(null))
                .param("ip", RequestContext.ip())
                .param("request", RequestContext.requestId())
                .param("detail", ec.unisfera.shared.domain.TechnicalLimits.truncate(detail, ec.unisfera.shared.domain.TechnicalLimits.AUDIT_DETAIL))
                .update();
    }

    public record AuditEntry(java.util.UUID id, java.time.Instant occurredAt, String tableName, String recordId, String action, String oldValues,
                             String newValues, java.util.UUID actorId, String actorName, String actorIp, String requestId, String detail) {
    }

    @Transactional(readOnly = true)
    public ec.unisfera.shared.web.PageResponse<AuditEntry> search(String table, String recordId, String action, java.util.UUID actor,
                                                                  ec.unisfera.shared.web.PageQuery q) {
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
                .query((rs, n) -> new AuditEntry(rs.getObject("id", java.util.UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("table_name"), rs.getString("record_id"), rs.getString("action"), rs.getString("old_values"),
                        rs.getString("new_values"), rs.getObject("actor_id", java.util.UUID.class), rs.getString("actor_name"),
                        rs.getString("actor_ip"), rs.getString("request_id"), rs.getString("detail")))
                .list();
        long total = jdbc.sql("select count(*) from audit_log a " + where)
                .param("table", table).param("record", recordId).param("action", action).param("actor", actor)
                .query(Long.class).single();
        return ec.unisfera.shared.web.PageResponse.of(items, q, total);
    }
}
