package ec.unisfera.finance.gateway;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de pasarela de pagos (adaptador). El dominio solo conoce esta interfaz: una pasarela
 * real (Kushki, Payphone…) se enchufa implementándola, sin tocar las reglas de negocio.
 */
public interface PaymentGateway {

    enum Outcome { APPROVED, REJECTED, PENDING }

    record Checkout(String reference, String redirectUrl) {
    }

    record Notification(String eventId, String reference, Outcome outcome, BigDecimal amount, String authorizationCode, String rawBody) {
    }

    String name();

    Checkout createCheckout(UUID paymentId, String reference, BigDecimal amount, String currency, String description);

    /** Verifica la firma del webhook y lo interpreta; lanza InvalidSignatureException si no es auténtico. */
    Notification verifyAndParse(String body, String signature);

    /** Consulta activa del estado (para pagos que se demoran y nunca notifican). */
    Optional<Notification> query(String reference);

    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException() {
            super("Firma del webhook inválida");
        }
    }
}
