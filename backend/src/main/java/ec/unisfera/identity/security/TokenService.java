package ec.unisfera.identity.security;

import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Tokens HS256 de vida corta. El alcance (scope) separa el token de acceso completo de los
 * tokens de desafío (cambio de contraseña obligatorio, segundo factor), que no abren la API.
 */
@Component
public class TokenService {

    public enum Scope { FULL, PASSWORD_CHANGE, MFA, MFA_ENROLL }

    public record TokenClaims(UUID userId, UUID sessionId, List<String> roles, Scope scope, String tokenId, Instant expiresAt) {
    }

    private static final String ISSUER = "unisfera";

    private final SecretKey key;
    private final Clock clock;
    private final ParameterService parameters;

    public TokenService(AppProperties props, Clock clock, ParameterService parameters) {
        byte[] secret = Decoders.BASE64.decode(props.jwtSecret());
        this.key = Keys.hmacShaKeyFor(secret);
        this.clock = clock;
        this.parameters = parameters;
    }

    public String accessToken(UUID userId, UUID sessionId, List<String> roles) {
        return build(userId, sessionId, roles, Scope.FULL, Duration.ofMinutes(parameters.integer(ParameterKeys.ACCESS_TOKEN_MINUTES)));
    }

    public String challengeToken(UUID userId, Scope scope) {
        return build(userId, null, List.of(), scope, Duration.ofMinutes(parameters.integer(ParameterKeys.CHALLENGE_TOKEN_MINUTES)));
    }

    public long accessTokenSeconds() {
        return Duration.ofMinutes(parameters.integer(ParameterKeys.ACCESS_TOKEN_MINUTES)).toSeconds();
    }

    private String build(UUID userId, UUID sessionId, List<String> roles, Scope scope, Duration ttl) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .claim("scope", scope.name())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (sessionId != null) {
            builder.claim("sid", sessionId.toString());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    public Optional<TokenClaims> parse(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object rawRoles = c.get("roles");
            List<String> roles = rawRoles instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            String sid = c.get("sid", String.class);
            return Optional.of(new TokenClaims(UUID.fromString(c.getSubject()), sid == null ? null : UUID.fromString(sid), roles,
                    Scope.valueOf(c.get("scope", String.class)), c.getId(), c.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
