package ec.unisfera.finance.domain;

import java.math.BigDecimal;
import java.util.List;

/** RN-29: libro mayor del estudiante. El saldo es la suma de movimientos con su signo. */
public final class Ledger {

    public enum EntryType { CHARGE, DISCOUNT, PAYMENT, LATE_FEE, REVERSAL, CREDIT_NOTE, ADJUSTMENT }

    public record Entry(EntryType type, int direction, BigDecimal amount) {

        public Entry {
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("El monto de un movimiento es siempre positivo");
            }
            if (direction != 1 && direction != -1) {
                throw new IllegalArgumentException("La dirección es +1 o -1");
            }
        }

        public BigDecimal signed() {
            return direction > 0 ? amount : amount.negate();
        }
    }

    public record Statement(BigDecimal charges, BigDecimal lateFees, BigDecimal discounts, BigDecimal payments, BigDecimal reversals,
                            BigDecimal balance) {

        /** cargos + mora − descuentos − pagos ± reversos = saldo */
        public boolean balances() {
            return charges.add(lateFees).subtract(discounts).subtract(payments).add(reversals).compareTo(balance) == 0;
        }
    }

    private Ledger() {
    }

    public static Statement statement(List<Entry> entries) {
        BigDecimal charges = sum(entries, EntryType.CHARGE);
        BigDecimal lateFees = sum(entries, EntryType.LATE_FEE);
        BigDecimal discounts = sum(entries, EntryType.DISCOUNT);
        BigDecimal payments = sum(entries, EntryType.PAYMENT).add(sum(entries, EntryType.CREDIT_NOTE));
        BigDecimal reversals = entries.stream().filter(e -> e.type() == EntryType.REVERSAL || e.type() == EntryType.ADJUSTMENT)
                .map(Entry::signed).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = entries.stream().map(Entry::signed).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Statement(charges, lateFees, discounts, payments, reversals, balance);
    }

    private static BigDecimal sum(List<Entry> entries, EntryType type) {
        return entries.stream().filter(e -> e.type() == type).map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
