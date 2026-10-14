package ec.unisfera.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccountEntity {

    @Id
    private UUID id;
    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;
    @Column(nullable = false, updatable = false)
    private String username;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;
    @Column(name = "password_expires_at")
    private Instant passwordExpiresAt;
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;
    @Column(nullable = false)
    private String status;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;
    @Column(name = "mfa_secret_enc")
    private byte[] mfaSecretEnc;
    @Column(name = "institutional_email")
    private String institutionalEmail;
    @Column(name = "institutional_email_verified", nullable = false)
    private boolean institutionalEmailVerified;
    @Column(name = "disabled_at")
    private Instant disabledAt;
    @Column(name = "disabled_by")
    private UUID disabledBy;
    @Column(name = "disabled_reason")
    private String disabledReason;
    @Version
    private Long version;

    protected UserAccountEntity() {
    }

    public UserAccountEntity(UUID id, UUID personId, String username, String passwordHash, Instant passwordExpiresAt) {
        this.id = id;
        this.personId = personId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordExpiresAt = passwordExpiresAt;
        this.mustChangePassword = true;
        this.status = "ACTIVE";
    }

    public UUID getId() { return id; }
    public UUID getPersonId() { return personId; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getPasswordExpiresAt() { return passwordExpiresAt; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public String getStatus() { return status; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public byte[] getMfaSecretEnc() { return mfaSecretEnc; }
    public String getInstitutionalEmail() { return institutionalEmail; }
    public boolean isInstitutionalEmailVerified() { return institutionalEmailVerified; }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void registerFailure(Instant lockedUntil) {
        this.failedAttempts++;
        if (lockedUntil != null) {
            this.lockedUntil = lockedUntil;
        }
    }

    public void registerSuccess(Instant now) {
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }

    public void changePassword(String newHash, Instant now, Instant expiresAt) {
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
        this.passwordExpiresAt = expiresAt;
        this.mustChangePassword = false;
    }

    /** Contraseña temporal de un solo uso: obliga a cambiarla en el siguiente ingreso. */
    public void resetToTemporary(String tempHash, Instant expiresAt) {
        this.passwordHash = tempHash;
        this.passwordExpiresAt = expiresAt;
        this.mustChangePassword = true;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public void preparePendingMfa(byte[] encryptedSecret) {
        this.mfaSecretEnc = encryptedSecret;
    }

    public void enableMfa() {
        this.mfaEnabled = true;
    }

    public void setInstitutionalEmail(String email, boolean verified) {
        this.institutionalEmail = email;
        this.institutionalEmailVerified = verified;
    }

    public void disable(UUID by, String reason, Instant at) {
        this.status = "DISABLED";
        this.disabledBy = by;
        this.disabledReason = reason;
        this.disabledAt = at;
    }
}
