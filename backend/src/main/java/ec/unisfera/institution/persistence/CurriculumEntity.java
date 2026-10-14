package ec.unisfera.institution.persistence;

import ec.unisfera.shared.domain.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/** Malla curricular versionada: una vez activa no cambia; una reforma es una nueva versión (RN-03). */
@Entity
@Table(name = "curriculum")
public class CurriculumEntity {

    @Id
    private UUID id;
    @Column(name = "career_id", nullable = false, updatable = false)
    private UUID careerId;
    @Column(name = "version_code", nullable = false, updatable = false)
    private String versionCode;
    @Column(nullable = false)
    private String name;
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;
    @Column(name = "valid_to")
    private LocalDate validTo;
    @Column(name = "approval_resolution", nullable = false)
    private String approvalResolution;
    @Column(nullable = false)
    private String status;
    @Version
    private Long version;

    protected CurriculumEntity() {
    }

    public CurriculumEntity(UUID id, UUID careerId, String versionCode, String name, LocalDate validFrom, String approvalResolution) {
        this.id = id;
        this.careerId = careerId;
        this.versionCode = versionCode;
        this.name = name;
        this.validFrom = validFrom;
        this.approvalResolution = approvalResolution;
        this.status = "DRAFT";
    }

    public UUID getId() { return id; }
    public UUID getCareerId() { return careerId; }
    public String getVersionCode() { return versionCode; }
    public String getName() { return name; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getApprovalResolution() { return approvalResolution; }
    public String getStatus() { return status; }

    public boolean isDraft() {
        return "DRAFT".equals(status);
    }

    public void activate() {
        if (!isDraft()) {
            throw new BusinessRuleException("RN-03", "Solo una malla en borrador puede activarse");
        }
        this.status = "ACTIVE";
    }

    public void close(LocalDate on) {
        this.status = "CLOSED";
        this.validTo = on;
    }
}
