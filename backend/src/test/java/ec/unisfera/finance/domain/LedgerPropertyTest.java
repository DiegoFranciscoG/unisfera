package ec.unisfera.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-04 · RN-29: 10.000 movimientos aleatorios (cargos, descuentos, abonos, moras y reversos) por
 * semilla y el estado de cuenta cuadra al centavo en cada paso: cargos + mora − descuentos −
 * pagos ± reversos = saldo = lo pendiente en las cuotas. Prueba por propiedades con semillas fijas
 * (reproducible); jqwik no se usa por incompatibilidad con JUnit Platform 6 (ver dependencias.md).
 */
class LedgerPropertyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final int MOVEMENTS = 10_000;

    private static final class Installment {
        final UUID id = UUID.randomUUID();
        final int number;
        final LocalDate due;
        BigDecimal amountDue = BigDecimal.ZERO;
        BigDecimal lateFee = BigDecimal.ZERO;
        BigDecimal feePaid = BigDecimal.ZERO;
        BigDecimal principalPaid = BigDecimal.ZERO;

        Installment(int number, LocalDate due) {
            this.number = number;
            this.due = due;
        }

        BigDecimal outstanding() {
            return amountDue.add(lateFee).subtract(feePaid).subtract(principalPaid);
        }
    }

    @ParameterizedTest(name = "semilla {0}")
    @ValueSource(longs = {1L, 20261013L, 777L, 31415926L, 2718281828L})
    void rn29_statementAlwaysBalancesAfter10000RandomMovements(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Ledger.Entry> entries = new ArrayList<>();
        List<Installment> installments = new ArrayList<>();
        Map<Integer, Ledger.Entry> reversible = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            installments.add(new Installment(i + 1, LocalDate.of(2026, 1, 1).plusMonths(i)));
        }

        for (int step = 0; step < MOVEMENTS; step++) {
            int kind = random.nextInt(100);
            Installment target = installments.get(random.nextInt(installments.size()));
            if (kind < 30) {
                BigDecimal amount = money(random, 1, 50_000);
                target.amountDue = target.amountDue.add(amount);
                Ledger.Entry e = new Ledger.Entry(Ledger.EntryType.CHARGE, 1, amount);
                entries.add(e);
                reversible.put(step, e);
            } else if (kind < 45) {
                BigDecimal room = target.amountDue.subtract(target.principalPaid);
                if (room.signum() > 0) {
                    BigDecimal amount = room.min(money(random, 1, 20_000));
                    target.amountDue = target.amountDue.subtract(amount);
                    entries.add(new Ledger.Entry(Ledger.EntryType.DISCOUNT, -1, amount));
                }
            } else if (kind < 55) {
                BigDecimal fee = money(random, 1, 3_000);
                target.lateFee = target.lateFee.add(fee);
                entries.add(new Ledger.Entry(Ledger.EntryType.LATE_FEE, 1, fee));
            } else if (kind < 90) {
                BigDecimal outstanding = installments.stream().map(Installment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (outstanding.signum() > 0) {
                    BigDecimal pay = outstanding.min(money(random, 1, 60_000));
                    var open = installments.stream().filter(x -> x.outstanding().signum() > 0)
                            .map(x -> new PaymentAllocator.OpenInstallment(x.id, x.number, x.due, x.amountDue, x.lateFee, x.feePaid, x.principalPaid))
                            .toList();
                    for (var a : PaymentAllocator.allocate(pay, open, List.of("LATE_FEE", "OLDEST_PRINCIPAL"))) {
                        Installment x = installments.stream().filter(it -> it.id.equals(a.installmentId())).findFirst().orElseThrow();
                        if ("LATE_FEE".equals(a.kind())) {
                            x.feePaid = x.feePaid.add(a.amount());
                        } else {
                            x.principalPaid = x.principalPaid.add(a.amount());
                        }
                    }
                    entries.add(new Ledger.Entry(Ledger.EntryType.PAYMENT, -1, pay));
                }
            } else if (!reversible.isEmpty()) {
                // Reverso de un cargo no pagado: se registra el movimiento contrario (nunca se borra el original)
                Integer key = reversible.keySet().iterator().next();
                Ledger.Entry original = reversible.remove(key);
                Installment x = installments.stream().filter(it -> it.amountDue.subtract(it.principalPaid).compareTo(original.amount()) >= 0)
                        .findFirst().orElse(null);
                if (x != null) {
                    x.amountDue = x.amountDue.subtract(original.amount());
                    entries.add(new Ledger.Entry(Ledger.EntryType.REVERSAL, -original.direction(), original.amount()));
                }
            }

            if (step % 97 == 0 || step == MOVEMENTS - 1) {
                Ledger.Statement s = Ledger.statement(entries);
                BigDecimal pending = installments.stream().map(Installment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(s.balances()).as("cuadre en el paso " + step).isTrue();
                assertThat(s.balance()).as("saldo = pendiente de cuotas en el paso " + step).isEqualByComparingTo(pending);
                assertThat(installments).allSatisfy(x -> assertThat(x.outstanding().signum()).isGreaterThanOrEqualTo(0));
            }
        }
        assertThat(entries.size()).isGreaterThan(MOVEMENTS / 2);
    }

    private static BigDecimal money(SplittableRandom random, int minCents, int maxCents) {
        return BigDecimal.valueOf(random.nextInt(minCents, maxCents), USD.getDefaultFractionDigits());
    }
}
