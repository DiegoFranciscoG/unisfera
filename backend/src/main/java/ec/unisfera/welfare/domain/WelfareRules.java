package ec.unisfera.welfare.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reglas puras de bienestar: puntaje de postulación (RN-20), mantenimiento de beca (RN-22) y riesgo de deserción. */
public final class WelfareRules {

    private static final BigDecimal HUNDRED = ec.unisfera.shared.domain.MoneyMath.hundred();
    private static final int SCALE = 2;

    public record Criterion(UUID id, BigDecimal maxScore, BigDecimal weight) {
    }

    public enum Outcome { MAINTAINED, REDUCED, SUSPENDED }

    public record MaintenanceRule(BigDecimal minAverage, BigDecimal minAttendance, BigDecimal partialMinAverage, BigDecimal partialValue) {
    }

    public record Maintenance(Outcome outcome, boolean complies, String reason) {
    }

    public enum Risk { LOW, MEDIUM, HIGH }

    public record RiskInput(int failedSubjects, BigDecimal attendancePct, boolean overdueDebt, int maxFailed, BigDecimal attendanceThreshold) {
    }

    public record RiskAssessment(Risk level, List<String> reasons) {
    }

    private WelfareRules() {
    }

    /** Puntaje total normalizado sobre 100: Σ (puntaje / máximo × peso) / Σ pesos × 100. */
    public static BigDecimal totalScore(List<Criterion> criteria, Map<UUID, BigDecimal> scores) {
        BigDecimal weights = criteria.stream().map(Criterion::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weights.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Criterion c : criteria) {
            BigDecimal s = scores.getOrDefault(c.id(), BigDecimal.ZERO);
            sum = sum.add(s.divide(c.maxScore(), 6, RoundingMode.HALF_UP).multiply(c.weight()));
        }
        return sum.multiply(HUNDRED).divide(weights, SCALE, RoundingMode.HALF_UP);
    }

    /** RN-22: mantenimiento por período con escalón intermedio opcional (modelo UPS: 90 completo, 80–89 parcial). */
    public static Maintenance maintenance(MaintenanceRule rule, BigDecimal average, BigDecimal attendance) {
        if (rule.minAttendance() != null && (attendance == null || attendance.compareTo(rule.minAttendance()) < 0)) {
            return new Maintenance(Outcome.SUSPENDED, false, "Asistencia " + plain(attendance) + " % menor al mínimo de " + plain(rule.minAttendance()) + " %");
        }
        if (rule.minAverage() == null || (average != null && average.compareTo(rule.minAverage()) >= 0)) {
            return new Maintenance(Outcome.MAINTAINED, true, "Cumple promedio y asistencia");
        }
        if (rule.partialMinAverage() != null && average != null && average.compareTo(rule.partialMinAverage()) >= 0) {
            return new Maintenance(Outcome.REDUCED, false, "Promedio " + plain(average) + " entre " + plain(rule.partialMinAverage()) + " y "
                    + plain(rule.minAverage()) + ": beca parcial");
        }
        return new Maintenance(Outcome.SUSPENDED, false, "Promedio " + plain(average) + " menor al mínimo de " + plain(rule.minAverage()));
    }

    public static RiskAssessment dropoutRisk(RiskInput in) {
        List<String> reasons = new ArrayList<>();
        if (in.failedSubjects() >= in.maxFailed()) {
            reasons.add("Reprobó " + in.failedSubjects() + " asignaturas en el período");
        }
        if (in.attendancePct() != null && in.attendancePct().compareTo(in.attendanceThreshold()) < 0) {
            reasons.add("Asistencia promedio de " + plain(in.attendancePct()) + " %");
        }
        if (in.overdueDebt()) {
            reasons.add("Valores vencidos");
        }
        Risk level = reasons.size() >= 2 ? Risk.HIGH : reasons.size() == 1 ? Risk.MEDIUM : Risk.LOW;
        return new RiskAssessment(level, List.copyOf(reasons));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
