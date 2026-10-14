package ec.unisfera.identity.application;

import ec.unisfera.identity.persistence.PersonEntity;
import ec.unisfera.identity.persistence.PersonRepository;
import ec.unisfera.identity.persistence.UserAccountEntity;
import ec.unisfera.identity.persistence.UserAccountRepository;
import ec.unisfera.identity.security.PermissionResolver;
import ec.unisfera.identity.security.SecurityUtils;
import ec.unisfera.identity.security.UserDirectory;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regla de oro del alta: nadie se auto-registra. La institución crea la cuenta y entrega una
 * contraseña temporal de un solo uso, que caduca y obliga a cambiarse en el primer ingreso.
 */
@Service
public class AccountService {

    public record ProvisionedAccount(UUID userId, String username, String temporaryPassword, Instant expiresAt, List<String> roles) {
    }

    public record AccountView(UUID userId, String username, String status, boolean mfaEnabled, boolean mustChangePassword,
                              Instant lastLoginAt, String institutionalEmail, List<String> roles) {
    }

    private final UserAccountRepository users;
    private final PersonRepository persons;
    private final PasswordEncoder encoder;
    private final ParameterService parameters;
    private final PermissionResolver permissions;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuthService auth;
    private final UserDirectory directory;

    public AccountService(UserAccountRepository users, PersonRepository persons, PasswordEncoder encoder, ParameterService parameters,
                          PermissionResolver permissions, JdbcClient jdbc, Clock clock, AuthService auth, UserDirectory directory) {
        this.users = users;
        this.persons = persons;
        this.encoder = encoder;
        this.parameters = parameters;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.auth = auth;
        this.directory = directory;
    }

    @Transactional
    public ProvisionedAccount provision(UUID personId, List<String> roleCodes, String institutionalEmail) {
        PersonEntity person = persons.findById(personId).orElseThrow(() -> new NotFoundException("La persona"));
        if (users.findByPersonId(personId).isPresent()) {
            throw new BusinessRuleException("SEC-05", "La persona ya tiene una cuenta; use el restablecimiento de credenciales");
        }
        String temp = SecureTokens.temporaryPassword();
        Instant expires = clock.instant().plus(Duration.ofHours(parameters.integer(ParameterKeys.TEMP_PASSWORD_HOURS)));
        UserAccountEntity account = new UserAccountEntity(UuidV7.next(clock), personId, person.getIdNumber(), encoder.encode(temp), expires);
        if (institutionalEmail != null && !institutionalEmail.isBlank()) {
            account.setInstitutionalEmail(institutionalEmail.trim().toLowerCase(), true);
        }
        users.saveAndFlush(account);
        roleCodes.forEach(r -> grant(account.getId(), r));
        return new ProvisionedAccount(account.getId(), account.getUsername(), temp, expires, roleCodes);
    }

    /** Nueva contraseña temporal (por ejemplo, si el estudiante olvidó la suya y no tiene correo verificado). */
    @Transactional
    public ProvisionedAccount resetToTemporary(UUID userId) {
        UserAccountEntity account = users.findById(userId).orElseThrow(() -> new NotFoundException("La cuenta"));
        String temp = SecureTokens.temporaryPassword();
        Instant expires = clock.instant().plus(Duration.ofHours(parameters.integer(ParameterKeys.TEMP_PASSWORD_HOURS)));
        account.resetToTemporary(encoder.encode(temp), expires);
        auth.logoutAll(userId);
        return new ProvisionedAccount(userId, account.getUsername(), temp, expires, permissions.activeRoles(userId));
    }

    @Transactional
    public void grant(UUID userId, String roleCode) {
        UUID roleId = jdbc.sql("select id from role where code = :c").param("c", roleCode).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("El rol " + roleCode));
        boolean exists = jdbc.sql("select count(*) from user_role where user_id = :u and role_id = :r and revoked_at is null")
                .param("u", userId).param("r", roleId).query(Long.class).single() > 0;
        if (!exists) {
            jdbc.sql("insert into user_role (id, user_id, role_id, granted_by) values (:id, :u, :r, :by)")
                    .param("id", UuidV7.next(clock)).param("u", userId).param("r", roleId)
                    .param("by", SecurityUtils.currentUser().map(c -> c.userId()).orElse(null)).update();
        }
    }

    /** Revocar un rol no borra la asignación: queda con fecha y responsable (LEY-2). */
    @Transactional
    public void revoke(UUID userId, String roleCode) {
        jdbc.sql("""
                        update user_role set revoked_at = now(), revoked_by = :by
                        where user_id = :u and revoked_at is null and role_id = (select id from role where code = :c)""")
                .param("by", SecurityUtils.currentUser().map(c -> c.userId()).orElse(null)).param("u", userId).param("c", roleCode).update();
        auth.logoutAll(userId);
    }

    @Transactional
    public void disable(UUID userId, String reason) {
        UserAccountEntity account = users.findById(userId).orElseThrow(() -> new NotFoundException("La cuenta"));
        account.disable(SecurityUtils.requireUser().userId(), reason, clock.instant());
        auth.logoutAll(userId);
        directory.evict(userId);
    }

    @Transactional(readOnly = true)
    public AccountView view(UUID userId) {
        UserAccountEntity a = users.findById(userId).orElseThrow(() -> new NotFoundException("La cuenta"));
        return new AccountView(a.getId(), a.getUsername(), a.getStatus(), a.isMfaEnabled(), a.isMustChangePassword(),
                a.getLastLoginAt(), a.getInstitutionalEmail(), permissions.activeRoles(userId));
    }

    @Transactional(readOnly = true)
    public UUID userIdOfPerson(UUID personId) {
        return users.findByPersonId(personId).map(UserAccountEntity::getId).orElseThrow(() -> new NotFoundException("La cuenta"));
    }
}
