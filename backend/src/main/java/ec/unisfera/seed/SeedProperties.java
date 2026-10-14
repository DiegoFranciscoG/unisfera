package ec.unisfera.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Escenario de demostración. Los seeds cargan DATOS y PARÁMETROS en tablas; no son constantes del
 * código (LEY-1). La contraseña de las cuentas demo llega por variable de entorno.
 */
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(boolean enabled, int students, int loadTestStudents, String demoPassword) {
}
