package ec.unisfera.evaluation.domain;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cálculos de evaluación, puros: escala (RN-12), pesos (RN-11), nota final ponderada, resultado
 * con asistencia (RN-15), cuánto falta para aprobar y promedio ponderado por horas (RN-16).
 */
public final class GradeMath {

    public static final int GRADE_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Result { APPROVED, FAILED, FAILED_ATTENDANCE }

    public record Scale(BigDecimal min, BigDecimal max, BigDecimal passing, BigDecimal minAttendance) {
    }

    public record Component(UUID id, String name, BigDecimal weight) {
    }

    public record HoursGrade(int hours, BigDecimal grade) {
    }

    /** Lo que falta para aprobar: promedio mínimo necesario en los componentes pendientes. */
    public record Needed(BigDecimal accumulated, BigDecimal pendingWeight, BigDecimal neededAverage, boolean alreadyPassed, boolean reachable) {
    }

    private GradeMath() {
    }

    public static void validateValue(BigDecimal value, Scale scale) {
        if (value == null || value.compareTo(scale.min()) < 0 || value.compareTo(scale.max()) > 0) {
            throw new BusinessRuleException("RN-12", "La nota debe estar entre " + scale.min().stripTrailingZeros().toPlainString()
                    + " y " + scale.max().stripTrailingZeros().toPlainString());
        }
    }

    public static void validateWeights(Collection<BigDecimal> weights, BigDecimal maxWeight, int minComponents) {
        if (weights.size() < minComponents) {
            throw new BusinessRuleException("RN-11", "El esquema necesita al menos " + minComponents + " componentes");
        }
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(HUNDRED) != 0) {
            throw new BusinessRuleException("RN-11", "Los pesos suman " + sum.stripTrailingZeros().toPlainString() + " y deben sumar exactamente 100");
        }
        if (weights.stream().anyMatch(w -> w.signum() <= 0 || w.compareTo(maxWeight) > 0)) {
            throw new BusinessRuleException("RN-11", "Cada componente debe pesar más de 0 y como máximo " + maxWeight.stripTrailingZeros().toPlainString() + " %");
        }
    }

    /** Nota final = Σ nota × peso / 100. Devuelve vacío si falta alguna nota. */
    public static Optional<BigDecimal> finalGrade(List<Component> components, Map<UUID, BigDecimal> grades) {
        if (!components.stream().allMatch(c -> grades.containsKey(c.id()))) {
            return Optional.empty();
        }
        return Optional.of(weighted(components, grades));
    }

    public static BigDecimal weighted(List<Component> components, Map<UUID, BigDecimal> grades) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Component c : components) {
            BigDecimal g = grades.get(c.id());
            if (g != null) {
                sum = sum.add(g.multiply(c.weight()));
            }
        }
        return sum.divide(HUNDRED, GRADE_SCALE, RoundingMode.HALF_UP);
    }

    /** RN-15: la asistencia insuficiente reprueba aunque la nota sea aprobatoria. */
    public static Result result(BigDecimal finalGrade, BigDecimal attendancePct, Scale scale) {
        if (attendancePct.compareTo(scale.minAttendance()) < 0) {
            return Result.FAILED_ATTENDANCE;
        }
        return finalGrade.compareTo(scale.passing()) >= 0 ? Result.APPROVED : Result.FAILED;
    }

    public static BigDecimal attendancePct(long attended, long total) {
        if (total == 0) {
            return HUNDRED.setScale(GRADE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(attended).multiply(HUNDRED).divide(BigDecimal.valueOf(total), GRADE_SCALE, RoundingMode.HALF_UP);
    }

    public static Needed needed(List<Component> components, Map<UUID, BigDecimal> grades, Scale scale) {
        BigDecimal accumulated = weighted(components, grades);
        BigDecimal pendingWeight = components.stream().filter(c -> !grades.containsKey(c.id())).map(Component::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (accumulated.compareTo(scale.passing()) >= 0) {
            return new Needed(accumulated, pendingWeight, BigDecimal.ZERO.setScale(GRADE_SCALE), true, true);
        }
        if (pendingWeight.signum() == 0) {
            return new Needed(accumulated, pendingWeight, null, false, false);
        }
        BigDecimal needed = scale.passing().subtract(accumulated).multiply(HUNDRED).divide(pendingWeight, GRADE_SCALE, RoundingMode.CEILING);
        return new Needed(accumulated, pendingWeight, needed, false, needed.compareTo(scale.max()) <= 0);
    }

    /** RN-16: promedio ponderado por horas de la asignatura. */
    public static Optional<BigDecimal> weightedByHours(List<HoursGrade> items) {
        long hours = items.stream().mapToLong(HoursGrade::hours).sum();
        if (hours == 0) {
            return Optional.empty();
        }
        BigDecimal sum = items.stream().map(i -> i.grade().multiply(BigDecimal.valueOf(i.hours()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(hours), GRADE_SCALE, RoundingMode.HALF_UP));
    }
}
