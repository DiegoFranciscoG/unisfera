package ec.unisfera.finance.domain;

import ec.unisfera.shared.domain.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RN-09: rubros de una matrícula a partir del tarifario vigente y descuentos aplicados en el
 * orden de prelación parametrizado. Cada descuento porcentual se aplica sobre el saldo que
 * dejaron los anteriores (por eso el orden importa). Función pura.
 */
public final class ChargeCalculator {

    public enum CalcMode { FLAT, PER_CREDIT, PER_SUBJECT }

    public record Tariff(UUID tariffId, UUID conceptId, String conceptCode, String conceptName, String kind, CalcMode mode,
                         BigDecimal amount, boolean discountable, BigDecimal taxRate) {
    }

    public record DiscountSource(String originType, UUID originId, String mode, BigDecimal value, Set<String> coveredConcepts) {
    }

    public record DiscountLine(String originType, UUID originId, BigDecimal percentage, BigDecimal amount, int precedence) {
    }

    public record ChargeLine(UUID tariffId, UUID conceptId, String conceptCode, String description, BigDecimal quantity, BigDecimal unitAmount,
                             BigDecimal base, List<DiscountLine> discounts, BigDecimal taxRate) {

        public BigDecimal discountTotal() {
            return discounts.stream().map(DiscountLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal net() {
            return base.subtract(discountTotal());
        }
    }

    public record Input(List<Tariff> tariffs, BigDecimal totalCredits, int subjectCount, boolean extraordinary,
                        BigDecimal extraordinarySurchargePct, Tariff surchargeConcept, List<DiscountSource> discounts,
                        List<String> precedence, java.util.Currency currency) {
    }

    private ChargeCalculator() {
    }

    public static List<ChargeLine> calculate(Input in) {
        List<ChargeLine> base = new ArrayList<>();
        BigDecimal enrollmentFee = BigDecimal.ZERO;
        for (Tariff t : in.tariffs()) {
            BigDecimal quantity = switch (t.mode()) {
                case FLAT -> BigDecimal.ONE;
                case PER_CREDIT -> in.totalCredits();
                case PER_SUBJECT -> BigDecimal.valueOf(in.subjectCount());
            };
            BigDecimal amount = MoneyMath.round(t.amount().multiply(quantity), in.currency());
            if ("ENROLLMENT".equals(t.kind())) {
                enrollmentFee = enrollmentFee.add(amount);
            }
            base.add(new ChargeLine(t.tariffId(), t.conceptId(), t.conceptCode(), t.conceptName(), quantity, t.amount(), amount, List.of(), t.taxRate()));
        }
        if (in.extraordinary() && in.surchargeConcept() != null && in.extraordinarySurchargePct().signum() > 0) {
            BigDecimal surcharge = MoneyMath.percentOf(enrollmentFee, in.extraordinarySurchargePct(), in.currency());
            base.add(new ChargeLine(null, in.surchargeConcept().conceptId(), in.surchargeConcept().conceptCode(),
                    in.surchargeConcept().conceptName(), BigDecimal.ONE, surcharge, surcharge, List.of(), in.surchargeConcept().taxRate()));
        }
        return applyDiscounts(base, in);
    }

    private static List<ChargeLine> applyDiscounts(List<ChargeLine> lines, Input in) {
        List<DiscountSource> ordered = in.discounts().stream()
                .sorted(Comparator.comparingInt(d -> precedenceOf(d.originType(), in.precedence())))
                .toList();
        List<ChargeLine> result = new ArrayList<>(lines);
        for (DiscountSource d : ordered) {
            int precedence = precedenceOf(d.originType(), in.precedence());
            BigDecimal remainingFixed = "AMOUNT".equals(d.mode()) ? d.value() : null;
            for (int i = 0; i < result.size(); i++) {
                ChargeLine line = result.get(i);
                Tariff tariff = in.tariffs().stream().filter(t -> t.conceptCode().equals(line.conceptCode())).findFirst().orElse(null);
                boolean discountable = tariff != null && tariff.discountable();
                if (!discountable || !d.coveredConcepts().contains(line.conceptCode()) || line.net().signum() <= 0) {
                    continue;
                }
                BigDecimal amount;
                BigDecimal percentage = null;
                if ("PERCENT".equals(d.mode())) {
                    percentage = d.value();
                    amount = MoneyMath.percentOf(line.net(), d.value(), in.currency());
                } else {
                    amount = remainingFixed.min(line.net());
                    remainingFixed = remainingFixed.subtract(amount);
                }
                if (amount.signum() <= 0) {
                    continue;
                }
                List<DiscountLine> discounts = new ArrayList<>(line.discounts());
                discounts.add(new DiscountLine(d.originType(), d.originId(), percentage, amount, precedence));
                result.set(i, new ChargeLine(line.tariffId(), line.conceptId(), line.conceptCode(), line.description(), line.quantity(),
                        line.unitAmount(), line.base(), List.copyOf(discounts), line.taxRate()));
            }
        }
        return List.copyOf(result);
    }

    private static int precedenceOf(String originType, List<String> precedence) {
        int index = precedence.indexOf(originType);
        return index < 0 ? precedence.size() : index;
    }
}
