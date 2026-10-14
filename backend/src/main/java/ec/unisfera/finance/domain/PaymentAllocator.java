package ec.unisfera.finance.domain;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RN-25: aplica un abono a las cuotas pendientes según el orden de prelación parametrizado.
 * <ul>
 *   <li>LATE_FEE: primero la mora de todas las cuotas, de la más antigua a la más reciente.</li>
 *   <li>OLDEST_PRINCIPAL: luego el capital, de la cuota más antigua a la más reciente.</li>
 * </ul>
 */
public final class PaymentAllocator {

    public record OpenInstallment(UUID id, int number, LocalDate dueDate, BigDecimal amountDue, BigDecimal lateFee,
                                  BigDecimal feePaid, BigDecimal principalPaid) {

        public BigDecimal outstanding() {
            return amountDue.add(lateFee).subtract(feePaid).subtract(principalPaid);
        }
    }

    public record Allocation(UUID installmentId, String kind, BigDecimal amount) {
    }

    private PaymentAllocator() {
    }

    public static List<Allocation> allocate(BigDecimal amount, List<OpenInstallment> installments, List<String> order) {
        if (amount.signum() <= 0) {
            throw new BusinessRuleException("RN-25", "El monto del abono debe ser positivo");
        }
        BigDecimal outstanding = installments.stream().map(OpenInstallment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessRuleException("RN-25", "El abono (" + amount + ") supera el saldo pendiente (" + outstanding + ")");
        }
        List<OpenInstallment> byAge = installments.stream()
                .sorted(Comparator.comparing(OpenInstallment::dueDate).thenComparingInt(OpenInstallment::number)).toList();
        Map<UUID, BigDecimal> feePaid = new HashMap<>();
        Map<UUID, BigDecimal> principalPaid = new HashMap<>();
        byAge.forEach(i -> {
            feePaid.put(i.id(), i.feePaid());
            principalPaid.put(i.id(), i.principalPaid());
        });
        BigDecimal remaining = amount;
        List<Allocation> result = new ArrayList<>();
        for (String step : order) {
            for (OpenInstallment i : byAge) {
                if (remaining.signum() <= 0) {
                    break;
                }
                boolean fee = switch (step) {
                    case "LATE_FEE" -> true;
                    case "OLDEST_PRINCIPAL" -> false;
                    default -> throw new IllegalArgumentException("Orden de prelación desconocido: " + step);
                };
                BigDecimal pending = fee ? i.lateFee().subtract(feePaid.get(i.id())) : i.amountDue().subtract(principalPaid.get(i.id()));
                if (pending.signum() <= 0) {
                    continue;
                }
                BigDecimal applied = pending.min(remaining);
                remaining = remaining.subtract(applied);
                (fee ? feePaid : principalPaid).merge(i.id(), applied, BigDecimal::add);
                result.add(new Allocation(i.id(), fee ? "LATE_FEE" : "PRINCIPAL", applied));
            }
        }
        if (remaining.signum() > 0) {
            throw new BusinessRuleException("RN-25", "La prelación configurada no cubre todo el saldo; revise finance.payment_allocation_order");
        }
        return List.copyOf(result);
    }
}
