package ec.unisfera.academic.application;

import java.util.List;
import java.util.UUID;

/**
 * Eventos de dominio del módulo académico. Se publican dentro de la transacción: si un
 * oyente (por ejemplo, Financiero al generar rubros) falla, la matrícula entera se revierte.
 */
public final class AcademicEvents {

    private AcademicEvents() {
    }

    public record EnrollmentConfirmed(UUID enrollmentId, UUID studentId, UUID periodId, String type, List<UUID> subjectIds) {
    }

    public record EnrollmentAnnulled(UUID enrollmentId, UUID studentId, UUID periodId, String reason) {
    }

    public record SubjectWithdrawn(UUID enrollmentDetailId, UUID studentId, UUID periodId, String type) {
    }
}
