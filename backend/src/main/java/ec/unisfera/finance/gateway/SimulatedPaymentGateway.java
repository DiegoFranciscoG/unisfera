package ec.unisfera.finance.gateway;

import ec.unisfera.shared.config.AppProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pasarela simulada completa para desarrollar sin contrato. Escenarios: APPROVE (aprobado),
 * REJECT (rechazado), DELAY (queda pendiente y solo se resuelve por consulta) y DOUBLE (la misma
 * notificación llega dos veces a la vez). Las notificaciones viajan por HTTP firmadas con HMAC,
 * exactamente como lo haría una pasarela real.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    public enum Scenario { APPROVE, REJECT, DELAY, DOUBLE }

    public static final String NAME = "SIMULATED";
    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);
    private static final String STATE = "sim:gw:";
    private static final Duration STATE_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final ExecutorService executor;
    private final RestClient http;

    public SimulatedPaymentGateway(StringRedisTemplate redis, AppProperties props, ExecutorService backgroundExecutor) {
        this.redis = redis;
        this.props = props;
        this.executor = backgroundExecutor;
        this.http = RestClient.builder().baseUrl(props.apiBaseUrl()).build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Checkout createCheckout(UUID paymentId, String reference, BigDecimal amount, String currency, String description) {
        redis.opsForHash().putAll(STATE + reference, Map.of("amount", amount.toPlainString(), "status", Outcome.PENDING.name(),
                "description", description));
        redis.expire(STATE + reference, STATE_TTL);
        return new Checkout(reference, props.publicBaseUrl() + "/pagos/pasarela/" + reference);
    }

    /** Lo que haría el formulario alojado de la pasarela cuando el estudiante paga. */
    public String simulate(String reference, Scenario scenario) {
        Object amount = redis.opsForHash().get(STATE + reference, "amount");
        if (amount == null) {
            throw new IllegalArgumentException("Referencia desconocida");
        }
        Outcome outcome = scenario == Scenario.REJECT ? Outcome.REJECTED : Outcome.APPROVED;
        redis.opsForHash().put(STATE + reference, "status", outcome.name());
        String body = payload(reference, outcome, new BigDecimal(amount.toString()));
        switch (scenario) {
            case APPROVE, REJECT -> executor.submit(() -> send(body));
            case DOUBLE -> {
                executor.submit(() -> send(body));
                executor.submit(() -> send(body));
            }
            case DELAY -> log.info("Pago {} aprobado sin notificar: se resolverá por consulta activa", reference);
        }
        return outcome.name();
    }

    public String payload(String reference, Outcome outcome, BigDecimal amount) {
        String eventId = "evt-" + reference + "-" + outcome.name().toLowerCase();
        return "{\"eventId\":\"" + eventId + "\",\"reference\":\"" + reference + "\",\"outcome\":\"" + outcome.name()
                + "\",\"amount\":\"" + amount.toPlainString() + "\",\"authorizationCode\":\"SIM" + Math.abs(reference.hashCode() % 1_000_000) + "\"}";
    }

    public String sign(String body) {
        return WebhookSigner.sign(props.gatewayWebhookSecret(), body);
    }

    private void send(String body) {
        try {
            http.post().uri("/api/public/gateway/" + NAME + "/webhook").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(body)).body(body).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("La notificación simulada no se pudo entregar: {}", e.getMessage());
        }
    }

    @Override
    public Notification verifyAndParse(String body, String signature) {
        if (!WebhookSigner.verify(props.gatewayWebhookSecret(), body, signature)) {
            throw new InvalidSignatureException();
        }
        return new Notification(field(body, "eventId"), field(body, "reference"), Outcome.valueOf(field(body, "outcome")),
                new BigDecimal(field(body, "amount")), field(body, "authorizationCode"), body);
    }

    @Override
    public Optional<Notification> query(String reference) {
        Map<Object, Object> state = redis.opsForHash().entries(STATE + reference);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Outcome outcome = Outcome.valueOf(state.get("status").toString());
        return Optional.of(new Notification("query-" + reference + "-" + outcome.name().toLowerCase(), reference, outcome,
                new BigDecimal(state.get("amount").toString()), "SIMQ", null));
    }

    private static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) {
            throw new InvalidSignatureException();
        }
        return m.group(1);
    }
}
