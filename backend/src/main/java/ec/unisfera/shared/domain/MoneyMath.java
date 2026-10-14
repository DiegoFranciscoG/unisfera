package ec.unisfera.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Dinero siempre en BigDecimal, con los decimales que define la moneda (USD → 2). */
public final class MoneyMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MoneyMath() {
    }

    public static BigDecimal round(BigDecimal amount, Currency currency) {
        return amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    /** amount × percentage / 100, redondeado a la moneda. */
    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percentage, Currency currency) {
        return round(amount.multiply(percentage).divide(HUNDRED, currency.getDefaultFractionDigits() + 4, RoundingMode.HALF_UP), currency);
    }

    public static BigDecimal hundred() {
        return HUNDRED;
    }
}
