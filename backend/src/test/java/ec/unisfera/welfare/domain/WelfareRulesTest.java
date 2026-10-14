package ec.unisfera.welfare.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ec.unisfera.parameters.domain.ParameterTimeline;
import ec.unisfera.parameters.domain.ParameterType;
import ec.unisfera.parameters.domain.ParameterVersion;
import ec.unisfera.shared.domain.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class WelfareRulesTest {

    @Test
    void rn20_scoreIsNormalizedByCriterionWeight() {
        UUID income = UUID.randomUUID();
        UUID grades = UUID.randomUUID();
        var criteria = List.of(new WelfareRules.Criterion(income, new BigDecimal("10"), new BigDecimal("60")),
                new WelfareRules.Criterion(grades, new BigDecimal("100"), new BigDecimal("40")));
        assertThat(WelfareRules.totalScore(criteria, Map.of(income, new BigDecimal("10"), grades, new BigDecimal("50")))).isEqualByComparingTo("80.00");
        assertThat(WelfareRules.totalScore(criteria, Map.of())).isEqualByComparingTo("0.00");
        assertThat(WelfareRules.totalScore(List.of(), Map.of())).isEqualByComparingTo("0.00");
    }

    @Test
    void rn22_maintenanceWithIntermediateTierLikeUps() {
        var rule = new WelfareRules.MaintenanceRule(new BigDecimal("90"), new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("15"));
        assertThat(WelfareRules.maintenance(rule, new BigDecimal("92"), new BigDecimal("95")).outcome()).isEqualTo(WelfareRules.Outcome.MAINTAINED);
        assertThat(WelfareRules.maintenance(rule, new BigDecimal("85"), new BigDecimal("95")).outcome()).isEqualTo(WelfareRules.Outcome.REDUCED);
        assertThat(WelfareRules.maintenance(rule, new BigDecimal("79.99"), new BigDecimal("95")).outcome()).isEqualTo(WelfareRules.Outcome.SUSPENDED);
        var lowAttendance = WelfareRules.maintenance(rule, new BigDecimal("99"), new BigDecimal("70"));
        assertThat(lowAttendance.outcome()).isEqualTo(WelfareRules.Outcome.SUSPENDED);
        assertThat(lowAttendance.reason()).contains("Asistencia");
    }

    @Test
    void dropoutRiskCombinesSignals() {
        var high = WelfareRules.dropoutRisk(new WelfareRules.RiskInput(3, new BigDecimal("60"), true, 2, new BigDecimal("80")));
        assertThat(high.level()).isEqualTo(WelfareRules.Risk.HIGH);
        assertThat(high.reasons()).hasSize(3);
        assertThat(WelfareRules.dropoutRisk(new WelfareRules.RiskInput(0, new BigDecimal("95"), true, 2, new BigDecimal("80"))).level())
                .isEqualTo(WelfareRules.Risk.MEDIUM);
        assertThat(WelfareRules.dropoutRisk(new WelfareRules.RiskInput(0, null, false, 2, new BigDecimal("80"))).level())
                .isEqualTo(WelfareRules.Risk.LOW);
    }

    @Test
    void rn26_parameterTimelineResolvesTheValueInForceAtAnyInstant() {
        Instant y2024 = Instant.parse("2024-01-01T05:00:00Z");
        Instant y2026 = Instant.parse("2026-01-01T05:00:00Z");
        var timeline = new ParameterTimeline("finance.late_fee_monthly_rate", List.of(
                new ParameterVersion(UUID.randomUUID(), "finance.late_fee_monthly_rate", "2.0", ParameterType.DECIMAL, "%", "x", y2026, null, "nuevo"),
                new ParameterVersion(UUID.randomUUID(), "finance.late_fee_monthly_rate", "1.1", ParameterType.DECIMAL, "%", "x", y2024, y2026, null)));
        assertThat(timeline.at(Instant.parse("2025-06-01T00:00:00Z")).value()).isEqualTo("1.1");
        assertThat(timeline.at(Instant.parse("2026-06-01T00:00:00Z")).value()).isEqualTo("2.0");
        Assertions.assertThatThrownBy(() -> timeline.at(Instant.parse("2020-01-01T00:00:00Z"))).isInstanceOf(BusinessRuleException.class);
        ParameterType.DECIMAL.validate("10.5");
        Assertions.assertThatThrownBy(() -> ParameterType.INTEGER.validate("diez")).isInstanceOf(BusinessRuleException.class);
        Assertions.assertThatThrownBy(() -> ParameterType.BOOLEAN.validate("si")).isInstanceOf(BusinessRuleException.class);
        ParameterType.DATE.validate("2026-10-13");
        ParameterType.LIST.validate("A,B");
    }
}
