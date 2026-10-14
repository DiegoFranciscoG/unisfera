package ec.unisfera.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinanceDomainTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final List<String> PRECEDENCE = List.of("SCHOLARSHIP", "FINANCIAL_AID", "SIBLING", "AGREEMENT", "EARLY_PAYMENT");

    private static ChargeCalculator.Tariff tariff(String code, String kind, ChargeCalculator.CalcMode mode, String amount, boolean discountable) {
        return new ChargeCalculator.Tariff(UUID.randomUUID(), UUID.randomUUID(), code, code, kind, mode, new BigDecimal(amount), discountable, BigDecimal.ZERO);
    }

    @Test
    void rn09_chargesFromTariffWithDiscountsInPrecedenceOrder() {
        var enrollment = tariff("MATRICULA", "ENROLLMENT", ChargeCalculator.CalcMode.FLAT, "198.00", true);
        var tuition = tariff("ARANCEL", "TUITION", ChargeCalculator.CalcMode.PER_CREDIT, "45.00", true);
        var surcharge = tariff("RECARGO_EXTRA", "SURCHARGE", ChargeCalculator.CalcMode.FLAT, "0", false);
        var scholarship = new ChargeCalculator.DiscountSource("SCHOLARSHIP", UUID.randomUUID(), "PERCENT", new BigDecimal("50"), Set.of("ARANCEL"));
        var agreement = new ChargeCalculator.DiscountSource("AGREEMENT", UUID.randomUUID(), "PERCENT", new BigDecimal("10"), Set.of("ARANCEL", "MATRICULA"));
        var lines = ChargeCalculator.calculate(new ChargeCalculator.Input(List.of(enrollment, tuition), new BigDecimal("15"), 5, true,
                new BigDecimal("10"), surcharge, List.of(agreement, scholarship), PRECEDENCE, USD));

        var arancel = lines.stream().filter(l -> l.conceptCode().equals("ARANCEL")).findFirst().orElseThrow();
        assertThat(arancel.base()).isEqualByComparingTo("675.00");
        // 50 % de beca primero (337.50) y luego 10 % de convenio sobre el saldo (33.75): la prelación importa
        assertThat(arancel.discounts()).extracting(ChargeCalculator.DiscountLine::amount).containsExactly(new BigDecimal("337.50"), new BigDecimal("33.75"));
        assertThat(arancel.net()).isEqualByComparingTo("303.75");
        var matricula = lines.stream().filter(l -> l.conceptCode().equals("MATRICULA")).findFirst().orElseThrow();
        assertThat(matricula.net()).isEqualByComparingTo("178.20");
        var recargo = lines.stream().filter(l -> l.conceptCode().equals("RECARGO_EXTRA")).findFirst().orElseThrow();
        assertThat(recargo.base()).isEqualByComparingTo("19.80");
        assertThat(recargo.discounts()).as("el recargo no es descontable").isEmpty();
    }

    @Test
    void rn09_fixedAmountDiscountNeverExceedsTheCharge() {
        var tuition = tariff("ARANCEL", "TUITION", ChargeCalculator.CalcMode.PER_SUBJECT, "100.00", true);
        var aid = new ChargeCalculator.DiscountSource("FINANCIAL_AID", UUID.randomUUID(), "AMOUNT", new BigDecimal("1000"), Set.of("ARANCEL"));
        var lines = ChargeCalculator.calculate(new ChargeCalculator.Input(List.of(tuition), BigDecimal.ZERO, 3, false, BigDecimal.ZERO, null,
                List.of(aid), PRECEDENCE, USD));
        assertThat(lines.getFirst().net()).isEqualByComparingTo("0.00");
    }

    @Test
    void installmentsAlwaysAddUpToTheTotal() {
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 1_000; i++) {
            BigDecimal total = BigDecimal.valueOf(random.nextLong(1, 500_000), 2);
            int count = random.nextInt(1, 12);
            var plan = InstallmentPlanner.plan(total, count, LocalDate.of(2026, 3, 1), 30, USD);
            assertThat(plan).hasSize(count);
            assertThat(plan.stream().map(InstallmentPlanner.Planned::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
            assertThat(plan.getLast().dueDate()).isEqualTo(LocalDate.of(2026, 3, 1).plusDays(30L * (count - 1)));
        }
    }

    @Test
    void rn25_lateFeesFirstThenOldestPrincipal() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var open = List.of(
                new PaymentAllocator.OpenInstallment(second, 2, LocalDate.of(2026, 4, 1), new BigDecimal("100"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO),
                new PaymentAllocator.OpenInstallment(first, 1, LocalDate.of(2026, 3, 1), new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO));
        var allocations = PaymentAllocator.allocate(new BigDecimal("130"), open, List.of("LATE_FEE", "OLDEST_PRINCIPAL"));
        assertThat(allocations).containsExactly(
                new PaymentAllocator.Allocation(first, "LATE_FEE", new BigDecimal("10")),
                new PaymentAllocator.Allocation(second, "LATE_FEE", new BigDecimal("5")),
                new PaymentAllocator.Allocation(first, "PRINCIPAL", new BigDecimal("100")),
                new PaymentAllocator.Allocation(second, "PRINCIPAL", new BigDecimal("15")));
    }

    @Test
    void rn25_rejectsOverpaymentAndUnknownOrder() {
        var open = List.of(new PaymentAllocator.OpenInstallment(UUID.randomUUID(), 1, LocalDate.of(2026, 3, 1), new BigDecimal("50"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
        assertThatThrownBy(() -> PaymentAllocator.allocate(new BigDecimal("60"), open, List.of("LATE_FEE", "OLDEST_PRINCIPAL")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> PaymentAllocator.allocate(new BigDecimal("10"), open, List.of("WHATEVER"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentAllocator.allocate(new BigDecimal("50"), open, List.of("LATE_FEE")))
                .hasMessageContaining("prelación");
    }

    @Test
    void rn26_lateFeeUsesTheRuleInForceAtTheDueDate() {
        LocalDate due = LocalDate.of(2026, 1, 10);
        var oldRule = new LateFeeCalculator.RuleAtDueDate(new BigDecimal("1.10"), 5);
        assertThat(LateFeeCalculator.accruedAt(due, due.plusDays(5), new BigDecimal("500"), oldRule, USD)).isEqualByComparingTo("0.00");
        assertThat(LateFeeCalculator.accruedAt(due, due.plusDays(6), new BigDecimal("500"), oldRule, USD)).isEqualByComparingTo("5.50");
        assertThat(LateFeeCalculator.accruedAt(due, due.plusDays(61), new BigDecimal("500"), oldRule, USD)).isEqualByComparingTo("16.50");
        var newRule = new LateFeeCalculator.RuleAtDueDate(new BigDecimal("2.00"), 0);
        assertThat(LateFeeCalculator.accruedAt(due, due.plusDays(6), new BigDecimal("500"), newRule, USD))
                .as("con la regla de hoy el resultado sería otro: por eso se usa la del vencimiento").isEqualByComparingTo("10.00");
        assertThat(LateFeeCalculator.increment(new BigDecimal("16.50"), new BigDecimal("5.50"))).isEqualByComparingTo("0.00");
    }

    @Test
    void sriAccessKeyHas49DigitsAndAValidModulo11CheckDigit() {
        String key = AccessKeyGenerator.generate(LocalDate.of(2026, 10, 13), "01", "0190000000001", "1", "001", "002", 123, "12345678");
        assertThat(key).hasSize(49).startsWith("1310202601").matches("\\d{49}");
        assertThat(AccessKeyGenerator.isValid(key)).isTrue();
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 500; i++) {
            int pos = random.nextInt(48);
            char original = key.charAt(pos);
            char replaced = (char) ('0' + (original - '0' + 1 + random.nextInt(9)) % 10);
            String tampered = key.substring(0, pos) + replaced + key.substring(pos + 1);
            assertThat(AccessKeyGenerator.isValid(tampered)).as("un dígito alterado se detecta").isFalse();
        }
        assertThatThrownBy(() -> AccessKeyGenerator.generate(LocalDate.of(2026, 10, 13), "01", "123", "1", "001", "002", 1, "12345678"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
