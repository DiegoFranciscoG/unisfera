package ec.unisfera.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La pasarela simulada aprueba pagos sin cobrar: fuera de la demo debe estar apagada para todos. */
class SimulatedGatewaySwitchTest {

    @Test
    void rn24_disabledSimulatorNeitherCreatesCheckoutsNorApprovesPayments() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(null, props(false), null);

        assertThat(gateway.enabled()).isFalse();
        assertThatThrownBy(() -> gateway.createCheckout(UUID.randomUUID(), "referencia-de-prueba-123", BigDecimal.TEN, "USD", "Arancel"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("pasarela");
        assertThatThrownBy(() -> gateway.simulate("referencia-de-prueba-123", SimulatedPaymentGateway.Scenario.APPROVE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void simulatorIsOnlyEnabledWhenTheDeploymentAsksForIt() {
        assertThat(new SimulatedPaymentGateway(null, props(true), null).enabled()).isTrue();
    }

    private static AppProperties props(boolean simulator) {
        String key = "k".repeat(64);
        return new AppProperties("America/Guayaquil", List.of("http://localhost:4200"), key, key, key, key, "3000000003",
                "Clave-De-Prueba-Larga", true, "./storage", "http://localhost:4200", "http://localhost:8080", "no-reply@prueba.ec",
                false, simulator);
    }
}
