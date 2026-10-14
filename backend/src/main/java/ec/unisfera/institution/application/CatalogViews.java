package ec.unisfera.institution.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Vistas de lectura del catálogo institucional. */
public final class CatalogViews {

    private CatalogViews() {
    }

    public record CareerView(UUID id, String code, String senescytCode, String name, String degreeTitle, String modality,
                             String educationLevel, short durationPeriods, String status) {
    }

    public record CurriculumView(UUID id, UUID careerId, String versionCode, String name, LocalDate validFrom, LocalDate validTo,
                                 String approvalResolution, String status) {
    }

    public record SubjectView(UUID id, UUID curriculumId, UUID levelId, String code, String name, int teacherContactHours,
                              int practicalHours, int autonomousHours, BigDecimal credits, String organizationUnit, boolean titling) {
    }

    public record PeriodView(UUID id, String code, String name, String type, LocalDate ordinaryEnrollmentFrom,
                             LocalDate ordinaryEnrollmentTo, LocalDate extraordinaryEnrollmentTo, LocalDate specialEnrollmentTo,
                             LocalDate classesStart, LocalDate classesEnd, LocalDate gradesCloseDeadline, String status) {
    }

    public record BlockView(int weekday, LocalTime start, LocalTime end, String classroom) {
    }

    public record SectionView(UUID id, UUID periodId, UUID subjectId, String subjectCode, String subjectName, String code,
                              int capacity, int enrolledCount, int seatsLeft, UUID teacherId, String teacherName, String status,
                              List<BlockView> blocks) {
    }

    public record SubjectNode(UUID id, String code, String name, int teacherContactHours, int practicalHours, int autonomousHours,
                              int totalHours, BigDecimal credits, String organizationUnit, List<UUID> prerequisites, List<UUID> corequisites) {
    }

    public record LevelNode(UUID id, int number, String name, List<SubjectNode> subjects) {
    }

    public record CurriculumTree(CurriculumView curriculum, CareerView career, List<LevelNode> levels) {
    }
}
