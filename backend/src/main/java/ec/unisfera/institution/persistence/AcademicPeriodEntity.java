package ec.unisfera.institution.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "academic_period")
public class AcademicPeriodEntity {

    @Id
    private UUID id;
    @Column(nullable = false, updatable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String type;
    @Column(name = "ordinary_enrollment_from", nullable = false)
    private LocalDate ordinaryEnrollmentFrom;
    @Column(name = "ordinary_enrollment_to", nullable = false)
    private LocalDate ordinaryEnrollmentTo;
    @Column(name = "extraordinary_enrollment_to", nullable = false)
    private LocalDate extraordinaryEnrollmentTo;
    @Column(name = "special_enrollment_to", nullable = false)
    private LocalDate specialEnrollmentTo;
    @Column(name = "classes_start", nullable = false)
    private LocalDate classesStart;
    @Column(name = "classes_end", nullable = false)
    private LocalDate classesEnd;
    @Column(name = "grades_close_deadline", nullable = false)
    private LocalDate gradesCloseDeadline;
    @Column(nullable = false)
    private String status;
    @Version
    private Long version;

    protected AcademicPeriodEntity() {
    }

    public AcademicPeriodEntity(UUID id, String code, String name, String type, LocalDate ordinaryEnrollmentFrom,
                                LocalDate ordinaryEnrollmentTo, LocalDate extraordinaryEnrollmentTo, LocalDate specialEnrollmentTo,
                                LocalDate classesStart, LocalDate classesEnd, LocalDate gradesCloseDeadline) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.ordinaryEnrollmentFrom = ordinaryEnrollmentFrom;
        this.ordinaryEnrollmentTo = ordinaryEnrollmentTo;
        this.extraordinaryEnrollmentTo = extraordinaryEnrollmentTo;
        this.specialEnrollmentTo = specialEnrollmentTo;
        this.classesStart = classesStart;
        this.classesEnd = classesEnd;
        this.gradesCloseDeadline = gradesCloseDeadline;
        this.status = "PLANNED";
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getType() { return type; }
    public LocalDate getOrdinaryEnrollmentFrom() { return ordinaryEnrollmentFrom; }
    public LocalDate getOrdinaryEnrollmentTo() { return ordinaryEnrollmentTo; }
    public LocalDate getExtraordinaryEnrollmentTo() { return extraordinaryEnrollmentTo; }
    public LocalDate getSpecialEnrollmentTo() { return specialEnrollmentTo; }
    public LocalDate getClassesStart() { return classesStart; }
    public LocalDate getClassesEnd() { return classesEnd; }
    public LocalDate getGradesCloseDeadline() { return gradesCloseDeadline; }
    public String getStatus() { return status; }

    public void moveTo(String newStatus) {
        this.status = newStatus;
    }
}
