package ec.unisfera.academic.application;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Compuerta (bulkhead) del día de matrícula: limita cuántas matrículas se procesan a la vez en
 * esta instancia para no agotar el pool de conexiones; el resto espera en una cola justa (FIFO).
 * La corrección del cupo no depende de esto (la garantiza la base); esto protege la latencia.
 */
@Component
public class EnrollmentGate {

    private final Semaphore permits;
    private final long waitMillis;
    private final EnrollmentService enrollment;

    public EnrollmentGate(EnrollmentService enrollment, @Value("${app.enrollment.max-concurrent:24}") int maxConcurrent,
                          @Value("${app.enrollment.max-wait-ms:30000}") long waitMillis) {
        this.enrollment = enrollment;
        this.permits = new Semaphore(maxConcurrent, true);
        this.waitMillis = waitMillis;
    }

    public EnrollmentService.Confirmation enroll(UUID studentId, List<UUID> sectionIds) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessRuleException("BUSY", "La solicitud se interrumpió; intente de nuevo");
        }
        if (!acquired) {
            throw new BusinessRuleException("BUSY", "Hay mucha demanda en este momento; intente de nuevo en unos segundos");
        }
        try {
            return enrollment.enroll(studentId, sectionIds);
        } finally {
            permits.release();
        }
    }
}
