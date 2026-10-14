package ec.unisfera.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GradeMathTest {

    private static final GradeMath.Scale SCALE = new GradeMath.Scale(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("70"), new BigDecimal("75"));
    private final GradeMath.Component c1 = new GradeMath.Component(UUID.randomUUID(), "Aporte 1", new BigDecimal("35"));
    private final GradeMath.Component c2 = new GradeMath.Component(UUID.randomUUID(), "Aporte 2", new BigDecimal("35"));
    private final GradeMath.Component c3 = new GradeMath.Component(UUID.randomUUID(), "Examen", new BigDecimal("30"));
    private final List<GradeMath.Component> components = List.of(c1, c2, c3);

    @Test
    void rn11_weightsMustSumExactly100AndRespectTheMaximum() {
        GradeMath.validateWeights(List.of(new BigDecimal("35"), new BigDecimal("35"), new BigDecimal("30")), new BigDecimal("60"), 3);
        assertThatThrownBy(() -> GradeMath.validateWeights(List.of(new BigDecimal("50"), new BigDecimal("40")), new BigDecimal("60"), 2))
                .hasMessageContaining("90");
        assertThatThrownBy(() -> GradeMath.validateWeights(List.of(new BigDecimal("70"), new BigDecimal("30")), new BigDecimal("60"), 2))
                .hasMessageContaining("máximo");
        assertThatThrownBy(() -> GradeMath.validateWeights(List.of(new BigDecimal("100")), new BigDecimal("100"), 3)).hasMessageContaining("al menos");
    }

    @Test
    void rn12_gradeMustBeInsideTheScale() {
        GradeMath.validateValue(new BigDecimal("0"), SCALE);
        GradeMath.validateValue(new BigDecimal("100"), SCALE);
        assertThatThrownBy(() -> GradeMath.validateValue(new BigDecimal("100.01"), SCALE)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> GradeMath.validateValue(new BigDecimal("-1"), SCALE)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> GradeMath.validateValue(null, SCALE)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void finalGradeIsTheWeightedSumAndNeedsEveryComponent() {
        Map<UUID, BigDecimal> grades = new HashMap<>(Map.of(c1.id(), new BigDecimal("80"), c2.id(), new BigDecimal("75")));
        assertThat(GradeMath.finalGrade(components, grades)).isEmpty();
        grades.put(c3.id(), new BigDecimal("90"));
        assertThat(GradeMath.finalGrade(components, grades)).contains(new BigDecimal("81.25"));
    }

    @Test
    void rn15_attendanceDecidesEvenWithAPassingGrade() {
        assertThat(GradeMath.result(new BigDecimal("95"), new BigDecimal("74.99"), SCALE)).isEqualTo(GradeMath.Result.FAILED_ATTENDANCE);
        assertThat(GradeMath.result(new BigDecimal("69.99"), new BigDecimal("100"), SCALE)).isEqualTo(GradeMath.Result.FAILED);
        assertThat(GradeMath.result(new BigDecimal("70"), new BigDecimal("75"), SCALE)).isEqualTo(GradeMath.Result.APPROVED);
        assertThat(GradeMath.attendancePct(3, 4)).isEqualByComparingTo("75.00");
        assertThat(GradeMath.attendancePct(0, 0)).isEqualByComparingTo("100");
    }

    @Test
    void neededTellsTheStudentHowMuchIsMissing() {
        var needed = GradeMath.needed(components, Map.of(c1.id(), new BigDecimal("60"), c2.id(), new BigDecimal("60")), SCALE);
        assertThat(needed.accumulated()).isEqualByComparingTo("42.00");
        assertThat(needed.neededAverage()).isEqualByComparingTo("93.34");
        assertThat(needed.reachable()).isTrue();
        var impossible = GradeMath.needed(components, Map.of(c1.id(), new BigDecimal("10"), c2.id(), new BigDecimal("10")), SCALE);
        assertThat(impossible.reachable()).isFalse();
        var passed = GradeMath.needed(components, Map.of(c1.id(), new BigDecimal("100"), c2.id(), new BigDecimal("100")), SCALE);
        assertThat(passed.alreadyPassed()).isTrue();
        var closed = GradeMath.needed(components, Map.of(c1.id(), BigDecimal.ONE, c2.id(), BigDecimal.ONE, c3.id(), BigDecimal.ONE), SCALE);
        assertThat(closed.reachable()).isFalse();
    }

    /** RN-16: el promedio ponderado por horas coincide con la definición en miles de casos generados. */
    @Test
    void rn16_weightedAverageByHoursProperty() {
        SplittableRandom random = new SplittableRandom(99);
        for (int i = 0; i < 5_000; i++) {
            List<GradeMath.HoursGrade> items = new ArrayList<>();
            long hours = 0;
            BigDecimal sum = BigDecimal.ZERO;
            for (int k = 0; k < 1 + random.nextInt(12); k++) {
                int h = 48 * (1 + random.nextInt(5));
                BigDecimal g = BigDecimal.valueOf(random.nextInt(7000, 10001), 2);
                items.add(new GradeMath.HoursGrade(h, g));
                hours += h;
                sum = sum.add(g.multiply(BigDecimal.valueOf(h)));
            }
            BigDecimal expected = sum.divide(BigDecimal.valueOf(hours), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal actual = GradeMath.weightedByHours(items).orElseThrow();
            assertThat(actual).isEqualByComparingTo(expected);
            BigDecimal min = items.stream().map(GradeMath.HoursGrade::grade).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal max = items.stream().map(GradeMath.HoursGrade::grade).max(BigDecimal::compareTo).orElseThrow();
            assertThat(actual).isBetween(min, max);
        }
        assertThat(GradeMath.weightedByHours(List.of())).isEmpty();
    }

    @Test
    void rn13_hashIsDeterministicOrderIndependentAndTamperEvident() {
        UUID section = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        var l1 = new ActHasher.Line(d1, Map.of(c1.id(), new BigDecimal("80.00")), new BigDecimal("80.00"), new BigDecimal("90"), "APPROVED");
        var l2 = new ActHasher.Line(d2, Map.of(c1.id(), new BigDecimal("50")), new BigDecimal("50"), new BigDecimal("90"), "FAILED");
        String h = ActHasher.hash(section, List.of(l1, l2));
        assertThat(h).hasSize(64).isEqualTo(ActHasher.hash(section, List.of(l2, l1)));
        var tampered = new ActHasher.Line(d2, Map.of(c1.id(), new BigDecimal("51")), new BigDecimal("51"), new BigDecimal("90"), "FAILED");
        assertThat(ActHasher.hash(section, List.of(l1, tampered))).isNotEqualTo(h);
    }
}
