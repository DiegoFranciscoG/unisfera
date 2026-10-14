package ec.unisfera.finance.domain;

import ec.unisfera.shared.domain.MoneyMath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;

/**
 * RN-26: interés por mora con la regla vigente AL VENCIMIENTO (tasa y días de gracia que regían
 * ese día), no con la de hoy. Se cobra por mes o fracción de mes vencido sobre el capital pendiente.
 */
public final class LateFeeCalculator {

    private static final int DAYS_PER_MONTH = 30;

    public record RuleAtDueDate(BigDecimal monthlyRatePct, int graceDays) {
    }

    private LateFeeCalculator() {
    }

    /** Mora total devengada a la fecha (nunca negativa). */
    public static BigDecimal accruedAt(LocalDate dueDate, LocalDate asOf, BigDecimal outstandingPrincipal, RuleAtDueDate rule, Currency currency) {
        LocalDate lateFrom = dueDate.plusDays(rule.graceDays());
        if (!asOf.isAfter(lateFrom) || outstandingPrincipal.signum() <= 0) {
            return MoneyMath.round(BigDecimal.ZERO, currency);
        }
        long daysLate = ChronoUnit.DAYS.between(dueDate, asOf);
        long months = (daysLate + DAYS_PER_MONTH - 1) / DAYS_PER_MONTH;
        return MoneyMath.percentOf(outstandingPrincipal.multiply(BigDecimal.valueOf(months)), rule.monthlyRatePct(), currency);
    }

    /** Incremento a registrar: la mora nunca baja por cálculo (solo por un reverso explícito). */
    public static BigDecimal increment(BigDecimal alreadyAccrued, BigDecimal accruedNow) {
        BigDecimal diff = accruedNow.subtract(alreadyAccrued);
        return diff.signum() > 0 ? diff : BigDecimal.ZERO.setScale(accruedNow.scale());
    }
}
