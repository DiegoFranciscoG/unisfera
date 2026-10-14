package ec.unisfera.identity.application;

import ec.unisfera.identity.domain.LockoutPolicy;
import ec.unisfera.identity.domain.PasswordPolicy;
import ec.unisfera.identity.domain.Totp;
import ec.unisfera.identity.persistence.PersonEntity;
import ec.unisfera.identity.persistence.PersonRepository;
import ec.unisfera.identity.persistence.UserAccountEntity;
import ec.unisfera.identity.persistence.UserAccountRepository;
import ec.unisfera.identity.security.CryptoService;
import ec.unisfera.identity.security.PermissionResolver;
import ec.unisfera.identity.security.RevokedSessionStore;
import ec.unisfera.identity.security.TokenService;
import ec.unisfera.identity.security.UserDirectory;
import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingreso con cédula o pasaporte + contraseña. La cédula es un identificador público: nunca
 * prueba identidad por sí sola. Flujo: contraseña → (cambio obligatorio) → (segundo factor) → tokens.
 */
@Service
public class AuthService {

    public enum Status { AUTHENTICATED, PASSWORD_CHANGE_REQUIRED, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED }

    public record UserSummary(UUID id, String username, String displayName, List<String> roles, Set<String> permissions) {
    }

    public record AuthOutcome(Status status, String accessToken, long expiresIn, String refreshToken, Duration refreshTtl,
                              String challengeToken, String otpauthUri, String mfaSecret, UserSummary user) {

        static AuthOutcome challenge(Status status, String token, String uri, String secret) {
            return new AuthOutcome(status, null, 0, null, null, token, uri, secret, null);
        }
    }

    private static final String MFA_CONTEXT = "user_account.mfa_secret_enc";
    private static final String ISSUER = "UNISFERA";

    private final UserAccountRepository users;
    private final PersonRepository persons;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final ParameterService parameters;
    private final PermissionResolver permissions;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final CryptoService crypto;
    private final RevokedSessionStore revoked;
    private final StringRedisTemplate redis;
    private final NotificationService notifications;
    private final AppProperties props;
    private final UserDirectory directory;
    private final String timingEqualizerHash;

    public AuthService(UserAccountRepository users, PersonRepository persons, PasswordEncoder encoder, TokenService tokens,
                       ParameterService parameters, PermissionResolver permissions, JdbcClient jdbc, Clock clock,
                       CryptoService crypto, RevokedSessionStore revoked, StringRedisTemplate redis,
                       NotificationService notifications, AppProperties props, UserDirectory directory) {
        this.users = users;
        this.persons = persons;
        this.encoder = encoder;
        this.tokens = tokens;
        this.parameters = parameters;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.crypto = crypto;
        this.revoked = revoked;
        this.redis = redis;
        this.notifications = notifications;
        this.props = props;
        this.directory = directory;
        this.timingEqualizerHash = encoder.encode(SecureTokens.newToken());
    }

    // ---------------------------------------------------------------- ingreso
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public AuthOutcome login(String rawUsername, String password) {
        String username = normalize(rawUsername);
        Instant now = clock.instant();
        Optional<UserAccountEntity> found = users.findByUsername(username);
        if (found.isEmpty()) {
            encoder.matches(password, timingEqualizerHash);
            recordAttempt(username, null, false, "UNKNOWN_USER");
            throw AuthenticationFailedException.generic();
        }
        UserAccountEntity user = found.get();
        boolean passwordOk = encoder.matches(password, user.getPasswordHash());
        if (!user.isActive()) {
            recordAttempt(username, user.getId(), false, "DISABLED");
            throw AuthenticationFailedException.generic();
        }
        if (user.isLockedAt(now)) {
            recordAttempt(username, user.getId(), false, "LOCKED");
            throw AuthenticationFailedException.generic();
        }
        if (!passwordOk) {
            Duration lock = LockoutPolicy.lockFor(user.getFailedAttempts() + 1,
                    parameters.integer(ParameterKeys.MAX_FAILED_ATTEMPTS),
                    parameters.integer(ParameterKeys.LOCKOUT_BASE_SECONDS),
                    parameters.integer(ParameterKeys.LOCKOUT_MAX_SECONDS));
            user.registerFailure(lock.isZero() ? null : now.plus(lock));
            recordAttempt(username, user.getId(), false, lock.isZero() ? "BAD_PASSWORD" : "LOCKED_NOW");
            throw AuthenticationFailedException.generic();
        }
        if (user.isMustChangePassword() && user.getPasswordExpiresAt() != null && now.isAfter(user.getPasswordExpiresAt())) {
            recordAttempt(username, user.getId(), false, "TEMP_PASSWORD_EXPIRED");
            throw new AuthenticationFailedException("SEC-02", "Su contraseña temporal caducó; solicite una nueva en Secretaría");
        }
        user.registerSuccess(now);
        recordAttempt(username, user.getId(), true, "OK");
        boolean expired = user.getPasswordExpiresAt() != null && now.isAfter(user.getPasswordExpiresAt());
        if (user.isMustChangePassword() || expired) {
            return AuthOutcome.challenge(Status.PASSWORD_CHANGE_REQUIRED,
                    tokens.challengeToken(user.getId(), TokenService.Scope.PASSWORD_CHANGE), null, null);
        }
        return afterPassword(user);
    }

    private AuthOutcome afterPassword(UserAccountEntity user) {
        List<String> roles = permissions.activeRoles(user.getId());
        if (user.isMfaEnabled()) {
            return AuthOutcome.challenge(Status.MFA_REQUIRED, tokens.challengeToken(user.getId(), TokenService.Scope.MFA), null, null);
        }
        if (permissions.anyRequiresMfa(roles)) {
            String secret = Totp.newSecret();
            user.preparePendingMfa(crypto.encrypt(secret, MFA_CONTEXT));
            return AuthOutcome.challenge(Status.MFA_ENROLLMENT_REQUIRED,
                    tokens.challengeToken(user.getId(), TokenService.Scope.MFA_ENROLL),
                    Totp.otpauthUri(ISSUER, user.getUsername(), secret), secret);
        }
        return issueSession(user, roles, false, null);
    }

    // ------------------------------------------------- contraseña obligatoria
    @Transactional
    public AuthOutcome changePasswordWithChallenge(String challengeToken, String newPassword) {
        TokenService.TokenClaims claims = consumeChallenge(challengeToken, TokenService.Scope.PASSWORD_CHANGE);
        UserAccountEntity user = users.findById(claims.userId()).orElseThrow(AuthenticationFailedException::generic);
        applyNewPassword(user, newPassword);
        revokeAllSessions(user.getId(), "PASSWORD_CHANGED");
        return afterPassword(user);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccountEntity user = users.findById(userId).orElseThrow(AuthenticationFailedException::generic);
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("SEC-02", "La contraseña actual no es correcta");
        }
        applyNewPassword(user, newPassword);
    }

    private void applyNewPassword(UserAccountEntity user, String newPassword) {
        PersonEntity person = persons.findById(user.getPersonId()).orElseThrow();
        PasswordPolicy.validate(newPassword, person.getIdNumber(), parameters.integer(ParameterKeys.PASSWORD_MIN_LENGTH));
        int historySize = parameters.integer(ParameterKeys.PASSWORD_HISTORY);
        List<String> previous = jdbc.sql("select password_hash from password_history where user_id = :u order by created_at desc limit :n")
                .param("u", user.getId()).param("n", historySize).query(String.class).list();
        if (encoder.matches(newPassword, user.getPasswordHash()) || previous.stream().anyMatch(h -> encoder.matches(newPassword, h))) {
            throw new BusinessRuleException("SEC-01", "No puede repetir sus últimas " + historySize + " contraseñas");
        }
        Instant now = clock.instant();
        String hash = encoder.encode(newPassword);
        user.changePassword(hash, now, now.plus(Duration.ofDays(parameters.integer(ParameterKeys.PASSWORD_MAX_AGE_DAYS))));
        jdbc.sql("insert into password_history (id, user_id, password_hash) values (:id, :u, :h)")
                .param("id", UuidV7.next(clock)).param("u", user.getId()).param("h", hash).update();
    }

    // ------------------------------------------------------- segundo factor
    @Transactional
    public AuthOutcome verifyMfa(String challengeToken, String code) {
        TokenService.TokenClaims claims = tokens.parse(challengeToken)
                .filter(c -> c.scope() == TokenService.Scope.MFA || c.scope() == TokenService.Scope.MFA_ENROLL)
                .orElseThrow(() -> new AuthenticationFailedException("SEC-04", "El desafío de seguridad caducó; vuelva a ingresar"));
        UserAccountEntity user = users.findById(claims.userId()).orElseThrow(AuthenticationFailedException::generic);
        if (user.getMfaSecretEnc() == null) {
            throw new AuthenticationFailedException("SEC-04", "No hay un segundo factor configurado");
        }
        String secret = crypto.decrypt(user.getMfaSecretEnc(), MFA_CONTEXT);
        long step = Totp.verify(secret, code, clock.instant());
        if (step < 0 || !acceptStep(user.getId(), step)) {
            recordAttempt(user.getUsername(), user.getId(), false, "BAD_MFA");
            throw new AuthenticationFailedException("SEC-04", "El código de verificación no es válido");
        }
        markChallengeUsed(claims);
        if (claims.scope() == TokenService.Scope.MFA_ENROLL) {
            user.enableMfa();
        }
        return issueSession(user, permissions.activeRoles(user.getId()), true, null);
    }

    private boolean acceptStep(UUID userId, long step) {
        String key = "mfa:last:" + userId;
        String last = redis.opsForValue().get(key);
        if (last != null && Long.parseLong(last) >= step) {
            return false;
        }
        redis.opsForValue().set(key, Long.toString(step), Duration.ofMinutes(2));
        return true;
    }

    // ------------------------------------------------------------ sesiones
    private AuthOutcome issueSession(UserAccountEntity user, List<String> roles, boolean mfaVerified, UUID familyId) {
        Instant now = clock.instant();
        Duration refreshTtl = Duration.ofHours(parameters.integer(ParameterKeys.REFRESH_TOKEN_HOURS));
        String refresh = SecureTokens.newToken();
        UUID sessionId = UuidV7.next(clock);
        jdbc.sql("""
                        insert into auth_session (id, user_id, family_id, refresh_token_hash, mfa_verified, ip, user_agent, expires_at, last_seen_at)
                        values (:id, :user, :family, :hash, :mfa, :ip, :ua, :exp, :now)""")
                .param("id", sessionId).param("user", user.getId()).param("family", familyId == null ? sessionId : familyId)
                .param("hash", SecureTokens.sha256(refresh)).param("mfa", mfaVerified)
                .param("ip", RequestContext.ip()).param("ua", RequestContext.userAgent())
                .param("exp", Timestamp.from(now.plus(refreshTtl))).param("now", Timestamp.from(now)).update();
        PersonEntity person = persons.findById(user.getPersonId()).orElseThrow();
        UserSummary summary = new UserSummary(user.getId(), user.getUsername(), person.fullName(), roles, permissions.permissionsFor(roles));
        return new AuthOutcome(Status.AUTHENTICATED, tokens.accessToken(user.getId(), sessionId, roles), tokens.accessTokenSeconds(),
                refresh, refreshTtl, null, null, null, summary);
    }

    /** Rotación del token de refresco; reutilizar uno ya rotado revoca toda la familia (robo de token). */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public AuthOutcome refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthenticationFailedException("SESSION", "La sesión expiró; vuelva a ingresar");
        }
        Instant now = clock.instant();
        var session = jdbc.sql("""
                        select id, user_id, family_id, mfa_verified, expires_at, last_seen_at, revoked_at, revoked_reason
                        from auth_session where refresh_token_hash = :hash for update""")
                .param("hash", SecureTokens.sha256(refreshToken))
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class), rs.getBoolean("mfa_verified"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("revoked_at"), rs.getString("revoked_reason")})
                .optional()
                .orElseThrow(() -> new AuthenticationFailedException("SESSION", "La sesión expiró; vuelva a ingresar"));
        UUID sessionId = (UUID) session[0];
        UUID userId = (UUID) session[1];
        UUID familyId = (UUID) session[2];
        if (session[6] != null) {
            if ("ROTATED".equals(session[7])) {
                revokeFamily(familyId, "REUSE_DETECTED");
            }
            throw new AuthenticationFailedException("SESSION", "La sesión expiró; vuelva a ingresar");
        }
        Duration inactivity = Duration.ofMinutes(parameters.integer(ParameterKeys.INACTIVITY_MINUTES));
        if (now.isAfter((Instant) session[4]) || now.isAfter(((Instant) session[5]).plus(inactivity))) {
            revokeSession(sessionId, "INACTIVE");
            throw new AuthenticationFailedException("SESSION", "Su sesión se cerró por inactividad; vuelva a ingresar");
        }
        UserAccountEntity user = users.findById(userId).filter(UserAccountEntity::isActive)
                .orElseThrow(() -> new AuthenticationFailedException("SESSION", "La sesión expiró; vuelva a ingresar"));
        AuthOutcome next = issueSession(user, permissions.activeRoles(userId), (Boolean) session[3], familyId);
        jdbc.sql("""
                        update auth_session set revoked_at = now(), revoked_reason = 'ROTATED',
                        replaced_by_id = (select id from auth_session where refresh_token_hash = :next)
                        where id = :id""")
                .param("next", SecureTokens.sha256(next.refreshToken())).param("id", sessionId).update();
        return next;
    }

    @Transactional
    public void logout(UUID sessionId) {
        revokeSession(sessionId, "LOGOUT");
    }

    @Transactional
    public int logoutAll(UUID userId) {
        return revokeAllSessions(userId, "LOGOUT_ALL");
    }

    private void revokeSession(UUID sessionId, String reason) {
        jdbc.sql("update auth_session set revoked_at = now(), revoked_reason = :r where id = :id and revoked_at is null")
                .param("r", reason).param("id", sessionId).update();
        revoked.revoke(sessionId, Duration.ofSeconds(tokens.accessTokenSeconds()));
    }

    private int revokeAllSessions(UUID userId, String reason) {
        List<UUID> active = jdbc.sql("select id from auth_session where user_id = :u and revoked_at is null")
                .param("u", userId).query(UUID.class).list();
        active.forEach(id -> revokeSession(id, reason));
        directory.evict(userId);
        return active.size();
    }

    private void revokeFamily(UUID familyId, String reason) {
        jdbc.sql("select id from auth_session where family_id = :f and revoked_at is null")
                .param("f", familyId).query(UUID.class).list()
                .forEach(id -> revokeSession(id, reason));
    }

    // ------------------------------------------------------- recuperación
    /** Siempre responde igual; solo envía el enlace si existe un correo institucional verificado. */
    @Transactional
    public void forgotPassword(String rawUsername) {
        users.findByUsername(normalize(rawUsername))
                .filter(UserAccountEntity::isActive)
                .filter(u -> u.getInstitutionalEmail() != null && u.isInstitutionalEmailVerified())
                .ifPresent(user -> {
                    String token = SecureTokens.newToken();
                    Duration ttl = Duration.ofMinutes(parameters.integer(ParameterKeys.RESET_TOKEN_MINUTES));
                    jdbc.sql("insert into password_reset_token (id, user_id, token_hash, expires_at, requested_ip) values (:id, :u, :h, :e, :ip)")
                            .param("id", UuidV7.next(clock)).param("u", user.getId()).param("h", SecureTokens.sha256(token))
                            .param("e", Timestamp.from(clock.instant().plus(ttl))).param("ip", RequestContext.ip()).update();
                    notifications.email(user.getInstitutionalEmail(), "UNISFERA · Restablecer contraseña",
                            "Recibimos una solicitud para restablecer su contraseña.\n\nAbra este enlace (válido " + ttl.toMinutes()
                                    + " minutos, un solo uso):\n" + props.publicBaseUrl() + "/restablecer?token=" + token
                                    + "\n\nSi no fue usted, ignore este mensaje.");
                });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        UUID userId = jdbc.sql("""
                        update password_reset_token set used_at = now()
                        where token_hash = :h and used_at is null and expires_at > now() returning user_id""")
                .param("h", SecureTokens.sha256(token)).query(UUID.class).optional()
                .orElseThrow(() -> new BusinessRuleException("SEC-02", "El enlace no es válido o ya caducó"));
        UserAccountEntity user = users.findById(userId).orElseThrow();
        applyNewPassword(user, newPassword);
        revokeAllSessions(userId, "PASSWORD_RESET");
    }

    // ------------------------------------------------------------ apoyo
    private TokenService.TokenClaims consumeChallenge(String token, TokenService.Scope scope) {
        TokenService.TokenClaims claims = tokens.parse(token).filter(c -> c.scope() == scope)
                .orElseThrow(() -> new AuthenticationFailedException("SEC-02", "El desafío caducó; vuelva a ingresar"));
        markChallengeUsed(claims);
        return claims;
    }

    private void markChallengeUsed(TokenService.TokenClaims claims) {
        Duration ttl = Duration.between(clock.instant(), claims.expiresAt());
        Boolean first = redis.opsForValue().setIfAbsent("challenge:used:" + claims.tokenId(), "1",
                ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl);
        if (!Boolean.TRUE.equals(first)) {
            throw new AuthenticationFailedException("SEC-02", "El desafío ya fue utilizado; vuelva a ingresar");
        }
    }

    private void recordAttempt(String username, UUID userId, boolean success, String reason) {
        jdbc.sql("""
                        insert into login_attempt (id, username_attempted, user_id, ip, user_agent, success, reason)
                        values (:id, :u, :uid, :ip, :ua, :ok, :r)""")
                .param("id", UuidV7.next(clock)).param("u", username.length() > 40 ? username.substring(0, 40) : username)
                .param("uid", userId).param("ip", RequestContext.ip()).param("ua", RequestContext.userAgent())
                .param("ok", success).param("r", reason).update();
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toUpperCase(Locale.ROOT);
    }
}
