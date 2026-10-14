package ec.unisfera.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/** Divide el total en cuotas iguales; los centavos sobrantes van a la última. Nunca se pierde un centavo. */
public final class InstallmentPlanner {

    public record Planned(int number, LocalDate dueDate, BigDecimal amount) {
    }

    private InstallmentPlanner() {
    }

    public static List<Planned> plan(BigDecimal total, int count, LocalDate firstDue, int intervalDays, Currency currency) {
        if (count <= 0) {
            throw new IllegalArgumentException("El número de cuotas debe ser positivo");
        }
        int scale = currency.getDefaultFractionDigits();
        BigDecimal regular = total.divide(BigDecimal.valueOf(count), scale, RoundingMode.DOWN);
        List<Planned> result = new ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 1; i <= count; i++) {
            BigDecimal amount = i == count ? total.subtract(assigned) : regular;
            assigned = assigned.add(amount);
            result.add(new Planned(i, firstDue.plusDays((long) intervalDays * (i - 1)), amount.setScale(scale, RoundingMode.UNNECESSARY)));
        }
        return List.copyOf(result);
    }
}
