package ec.unisfera.reporting.application;

import ec.unisfera.audit.application.AuditService;
import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.reporting.domain.ReportTable;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reportes pesados en segundo plano: la petición web solo encola (background_job) y responde;
 * los trabajadores de cualquier instancia toman trabajos con FOR UPDATE SKIP LOCKED, generan el
 * archivo y notifican al usuario. Encolar es transaccional: no se pierde un trabajo.
 */
@Service
public class ReportJobService {

    public record JobView(UUID id, String report, String format, String status, Instant createdAt, Instant finishedAt, UUID fileId, String error) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReportJobService.class);

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final ExportService export;
    private final NotificationService notifications;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Clock clock;

    public ReportJobService(JdbcClient jdbc, ReportService reports, ExportService export, NotificationService notifications, AuditService audit,
                            TransactionTemplate tx, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.export = export;
        this.notifications = notifications;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public UUID enqueue(String report, String format, UUID periodId, LocalDate cutoff) {
        UUID id = UuidV7.next(clock);
        String payload = "{\"report\":\"" + report + "\",\"format\":\"" + format + "\",\"periodId\":" + (periodId == null ? "null" : "\"" + periodId + "\"")
                + ",\"cutoff\":" + (cutoff == null ? "null" : "\"" + cutoff + "\"") + "}";
        jdbc.sql("insert into background_job (id, job_type, payload, requested_by) values (:id, 'REPORT', cast(:p as jsonb), :by)")
                .param("id", id).param("p", payload).param("by", RequestContext.actorId().orElse(null)).update();
        audit.recordExport(report, "Exportación " + format + " encolada");
        return id;
    }

    @Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT10S")
    public void poll() {
        if (!props.workersEnabled()) {
            return;
        }
        try {
            while (processNext()) {
                log.debug("Trabajo de reporte procesado");
            }
        } catch (RuntimeException e) {
            log.warn("Error al procesar trabajos: {}", e.getMessage());
        }
    }

    /** Toma un trabajo pendiente sin bloquear a los demás trabajadores. */
    public boolean processNext() {
        Object[] job = tx.execute(status -> jdbc.sql("""
                        update background_job set status = 'RUNNING', locked_at = now(), attempts = attempts + 1
                        where id = (select id from background_job where status = 'QUEUED' and run_after <= now()
                                    order by run_after for update skip locked limit 1)
                        returning id, payload->>'report', payload->>'format', payload->>'periodId', payload->>'cutoff', requested_by""")
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class)})
                .optional().orElse(null));
        if (job == null) {
            return false;
        }
        UUID jobId = (UUID) job[0];
        try {
            ReportTable table = reports.run((String) job[1], job[3] == null ? null : UUID.fromString((String) job[3]),
                    job[4] == null ? null : LocalDate.parse((String) job[4]));
            boolean pdf = "PDF".equals(job[2]);
            byte[] content = pdf ? export.pdf(table) : export.xlsx(table);
            UUID fileId = UuidV7.next(clock);
            String stored = fileId + (pdf ? ".pdf" : ".xlsx");
            Path dir = Path.of(props.storagePath()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Files.write(dir.resolve(stored), content);
            tx.executeWithoutResult(status -> {
                jdbc.sql("""
                                insert into stored_file (id, original_name, stored_name, content_type, size_bytes, sha256, purpose)
                                values (:id, :o, :s, :t, :size, :sha, 'REPORT')""")
                        .param("id", fileId).param("o", job[1] + (pdf ? ".pdf" : ".xlsx")).param("s", stored)
                        .param("t", pdf ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .param("size", (long) content.length).param("sha", sha256(content)).update();
                jdbc.sql("update background_job set status = 'SUCCEEDED', finished_at = now(), result_file_id = :f where id = :id")
                        .param("f", fileId).param("id", jobId).update();
                if (job[5] != null) {
                    notifications.notifyUser((UUID) job[5], "REPORT", "Reporte listo", "Su reporte '" + table.title() + "' ya se puede descargar.",
                            "/admin/reportes");
                }
            });
        } catch (Exception e) {
            tx.executeWithoutResult(status -> jdbc.sql("update background_job set status = 'FAILED', finished_at = now(), error = :e where id = :id")
                    .param("e", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).param("id", jobId).update());
        }
        return true;
    }

    @Transactional(readOnly = true)
    public JobView job(UUID id) {
        return jdbc.sql("""
                        select id, payload->>'report', payload->>'format', status, created_at, finished_at, result_file_id, error
                        from background_job where id = :id and requested_by = :by""")
                .param("id", id).param("by", RequestContext.actorId().orElse(null))
                .query((rs, n) -> new JobView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class),
                        rs.getString(8)))
                .optional().orElseThrow(() -> new NotFoundException("El trabajo"));
    }

    @Transactional(readOnly = true)
    public java.util.List<JobView> myJobs() {
        return jdbc.sql("""
                        select id, payload->>'report', payload->>'format', status, created_at, finished_at, result_file_id, error
                        from background_job where requested_by = :by and job_type = 'REPORT' order by created_at desc limit 20""")
                .param("by", RequestContext.actorId().orElse(null))
                .query((rs, n) -> new JobView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class),
                        rs.getString(8)))
                .list();
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
