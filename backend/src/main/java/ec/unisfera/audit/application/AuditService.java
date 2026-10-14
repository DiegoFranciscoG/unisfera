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
                .param("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)))
                .update();
    }
}
