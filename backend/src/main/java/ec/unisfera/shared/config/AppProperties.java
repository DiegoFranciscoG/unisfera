package ec.unisfera.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración técnica. Los secretos llegan solo por variables de entorno y no tienen
 * valor por defecto: si falta uno, la aplicación no arranca.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String zone,
        @NotEmpty List<String> corsAllowedOrigins,
        @NotBlank @Size(min = 43, message = "JWT_SECRET debe tener al menos 256 bits en Base64") String jwtSecret,
        @NotBlank @Size(min = 43, message = "SENSITIVE_DATA_KEY debe ser una clave AES-256 en Base64") String sensitiveDataKey,
        @NotBlank @Size(min = 32) String actSigningKey,
        @NotBlank @Size(min = 32) String gatewayWebhookSecret,
        @NotBlank String superadminIdNumber,
        @NotBlank @Size(min = 12) String superadminInitialPassword,
        boolean cookieSecure,
        @NotBlank String storagePath,
        @NotBlank String publicBaseUrl,
        @NotBlank String apiBaseUrl,
        @NotBlank String mailFrom,
        boolean workersEnabled,
        boolean paymentSimulatorEnabled) {

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
