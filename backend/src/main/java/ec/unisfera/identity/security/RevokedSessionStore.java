package ec.unisfera.identity.security;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Sesiones revocadas compartidas por todas las instancias: cerrar sesión (o cerrar en todos los
 * dispositivos) invalida el token de acceso de inmediato, sin esperar a que caduque.
 */
@Component
public class RevokedSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RevokedSessionStore.class);
    private static final String PREFIX = "revoked:sid:";

    private final StringRedisTemplate redis;

    public RevokedSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void revoke(UUID sessionId, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + sessionId, "1", ttl);
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar la sesión revocada {}: {}", sessionId, e.getMessage());
        }
    }

    /** Si Redis no responde se permite el acceso: la vida corta del token acota la exposición. */
    public boolean isRevoked(UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + sessionId));
        } catch (RuntimeException e) {
            log.warn("No se pudo consultar la revocación de la sesión {}: {}", sessionId, e.getMessage());
            return false;
        }
    }
}
