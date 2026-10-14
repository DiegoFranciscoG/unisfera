package ec.unisfera.institution.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

/** Asignatura con horas por componente de aprendizaje (RRA arts. 22–25). */
@Entity
@Table(name = "subject")
public class SubjectEntity {

    @Id
    private UUID id;
    @Column(name = "curriculum_id", nullable = false, updatable = false)
    private UUID curriculumId;
    @Column(name = "level_id", nullable = false)
    private UUID levelId;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(name = "teacher_contact_hours", nullable = false)
    private int teacherContactHours;
    @Column(name = "practical_hours", nullable = false)
    private int practicalHours;
    @Column(name = "autonomous_hours", nullable = false)
    private int autonomousHours;
    @Column(name = "total_hours", insertable = false, updatable = false)
    private Integer totalHours;
    @Column(nullable = false)
    private BigDecimal credits;
    @Column(name = "organization_unit", nullable = false)
    private String organizationUnit;
    @Column(name = "is_titling", nullable = false)
    private boolean titling;
    @Version
    private Long version;

    protected SubjectEntity() {
    }

    public SubjectEntity(UUID id, UUID curriculumId, UUID levelId, String code, String name, int teacherContactHours,
                         int practicalHours, int autonomousHours, BigDecimal credits, String organizationUnit, boolean titling) {
        this.id = id;
        this.curriculumId = curriculumId;
        this.levelId = levelId;
        this.code = code;
        this.name = name;
        this.teacherContactHours = teacherContactHours;
        this.practicalHours = practicalHours;
        this.autonomousHours = autonomousHours;
        this.credits = credits;
        this.organizationUnit = organizationUnit;
        this.titling = titling;
    }

    public UUID getId() { return id; }
    public UUID getCurriculumId() { return curriculumId; }
    public UUID getLevelId() { return levelId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getTeacherContactHours() { return teacherContactHours; }
    public int getPracticalHours() { return practicalHours; }
    public int getAutonomousHours() { return autonomousHours; }
    public BigDecimal getCredits() { return credits; }
    public String getOrganizationUnit() { return organizationUnit; }
    public boolean isTitling() { return titling; }

    public int totalHours() {
        return teacherContactHours + practicalHours + autonomousHours;
    }
}
