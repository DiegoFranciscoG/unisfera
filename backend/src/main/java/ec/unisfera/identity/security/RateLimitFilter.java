package ec.unisfera.identity.security;

import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Límite de peticiones con ventana fija en Redis/Valkey: por IP en el ingreso y la recuperación,
 * por usuario en el resto de la API. Compartido entre instancias.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration KEY_TTL = WINDOW.plusSeconds(10); // técnico: margen para que Redis expire la ventana

    private final StringRedisTemplate redis;
    private final ParameterService parameters;
    private final Clock clock;

    public RateLimitFilter(StringRedisTemplate redis, ParameterService parameters, Clock clock) {
        this.redis = redis;
        this.parameters = parameters;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String bucket = null;
        int limit = 0;
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/password/forgot") || path.startsWith("/api/auth/mfa")) {
            bucket = "login:" + request.getRemoteAddr();
            limit = parameters.integer(ParameterKeys.RATE_LIMIT_LOGIN);
        } else if (path.startsWith("/api/")) {
            var user = SecurityUtils.currentUser();
            if (user.isPresent()) {
                bucket = "user:" + user.get().userId();
                limit = parameters.integer(ParameterKeys.RATE_LIMIT_API);
            }
        }
        if (bucket != null && exceeded(bucket, limit)) {
            response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            SecurityConfig.writeProblem(response, org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMIT", "Demasiadas solicitudes; espere un momento");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean exceeded(String bucket, int limit) {
        String key = "rl:" + bucket + ":" + clock.millis() / WINDOW.toMillis();
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, KEY_TTL);
            }
            return count != null && count > limit;
        } catch (RuntimeException e) {
            log.warn("Rate limit no disponible: {}", e.getMessage());
            return false;
        }
    }
}
