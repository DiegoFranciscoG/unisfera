package ec.unisfera.institution.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "career")
public class CareerEntity {

    @Id
    private UUID id;
    @Column(name = "faculty_id", nullable = false)
    private UUID facultyId;
    @Column(name = "campus_id", nullable = false)
    private UUID campusId;
    @Column(nullable = false, updatable = false)
    private String code;
    @Column(name = "senescyt_code", nullable = false)
    private String senescytCode;
    @Column(nullable = false)
    private String name;
    @Column(name = "degree_title", nullable = false)
    private String degreeTitle;
    @Column(nullable = false)
    private String modality;
    @Column(name = "education_level", nullable = false)
    private String educationLevel;
    @Column(name = "duration_periods", nullable = false)
    private short durationPeriods;
    @Column(nullable = false)
    private String status;
    @Version
    private Long version;

    protected CareerEntity() {
    }

    public CareerEntity(UUID id, UUID facultyId, UUID campusId, String code, String senescytCode, String name, String degreeTitle,
                        String modality, String educationLevel, short durationPeriods) {
        this.id = id;
        this.facultyId = facultyId;
        this.campusId = campusId;
        this.code = code;
        this.senescytCode = senescytCode;
        this.name = name;
        this.degreeTitle = degreeTitle;
        this.modality = modality;
        this.educationLevel = educationLevel;
        this.durationPeriods = durationPeriods;
        this.status = "ACTIVE";
    }

    public UUID getId() { return id; }
    public UUID getFacultyId() { return facultyId; }
    public UUID getCampusId() { return campusId; }
    public String getCode() { return code; }
    public String getSenescytCode() { return senescytCode; }
    public String getName() { return name; }
    public String getDegreeTitle() { return degreeTitle; }
    public String getModality() { return modality; }
    public String getEducationLevel() { return educationLevel; }
    public short getDurationPeriods() { return durationPeriods; }
    public String getStatus() { return status; }

    public void close() {
        this.status = "CLOSED";
    }
}
