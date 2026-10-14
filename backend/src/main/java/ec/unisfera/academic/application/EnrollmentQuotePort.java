package ec.unisfera.academic.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Puerto que el módulo financiero implementa para mostrar el resumen de costos antes de
 * confirmar la matrícula (inversión de dependencias: Académico no conoce a Financiero).
 */
public interface EnrollmentQuotePort {

    record QuoteLine(String concept, String description, BigDecimal base, BigDecimal discount, BigDecimal net) {
    }

    record Quote(String currency, List<QuoteLine> lines, BigDecimal total, int installments, BigDecimal firstInstallment) {
    }

    Quote quote(UUID studentId, UUID periodId, String enrollmentType, List<UUID> subjectIds);
}
