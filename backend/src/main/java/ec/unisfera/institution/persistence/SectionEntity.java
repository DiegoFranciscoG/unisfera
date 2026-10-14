package ec.unisfera.institution.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * Paralelo. enrolled_count es de solo lectura para JPA: lo mantiene exclusivamente el trigger de
 * matrícula con una actualización atómica (RN-05), así una edición administrativa nunca pisa el contador.
 */
@Entity
@Table(name = "section")
public class SectionEntity {

    @Id
    private UUID id;
    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;
    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;
    @Column(nullable = false, updatable = false)
    private String code;
    @Column(nullable = false)
    private int capacity;
    @Column(name = "enrolled_count", insertable = false, updatable = false)
    private Integer enrolledCount;
    @Column(name = "teacher_id")
    private UUID teacherId;
    @Column(name = "classroom_id")
    private UUID classroomId;
    @Column(nullable = false)
    private String status;
    @Version
    private Long version;

    protected SectionEntity() {
    }

    public SectionEntity(UUID id, UUID periodId, UUID subjectId, String code, int capacity, UUID teacherId, UUID classroomId) {
        this.id = id;
        this.periodId = periodId;
        this.subjectId = subjectId;
        this.code = code;
        this.capacity = capacity;
        this.teacherId = teacherId;
        this.classroomId = classroomId;
        this.status = "OPEN";
    }

    public UUID getId() { return id; }
    public UUID getPeriodId() { return periodId; }
    public UUID getSubjectId() { return subjectId; }
    public String getCode() { return code; }
    public int getCapacity() { return capacity; }
    public Integer getEnrolledCount() { return enrolledCount; }
    public UUID getTeacherId() { return teacherId; }
    public UUID getClassroomId() { return classroomId; }
    public String getStatus() { return status; }

    public void assign(UUID teacherId, UUID classroomId, int capacity) {
        this.teacherId = teacherId;
        this.classroomId = classroomId;
        this.capacity = capacity;
    }

    public void close() {
        this.status = "CLOSED";
    }
}
