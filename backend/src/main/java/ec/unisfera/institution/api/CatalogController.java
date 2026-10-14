package ec.unisfera.institution.api;

import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.institution.application.CatalogService;
import ec.unisfera.institution.application.CatalogViews;
import ec.unisfera.institution.application.PeriodService;
import ec.unisfera.institution.application.SectionService;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Configuración institucional")
@RestController
public class CatalogController {

    public record CareerRequest(@NotNull UUID facultyId, @NotNull UUID campusId, @NotBlank @Size(max = 12) String code,
                                @NotBlank @Size(max = 40) String senescytCode, @NotBlank @Size(max = 160) String name,
                                @NotBlank @Size(max = 200) String degreeTitle,
                                @NotBlank @Pattern(regexp = "PRESENCIAL|SEMIPRESENCIAL|EN_LINEA|A_DISTANCIA|HIBRIDA|DUAL") String modality,
                                @NotBlank @Pattern(regexp = "TERCER_NIVEL_GRADO|TERCER_NIVEL_TECNOLOGICO") String educationLevel,
                                @Positive short durationPeriods) {
    }

    public record CurriculumRequest(@NotBlank @Size(max = 20) String versionCode, @NotBlank @Size(max = 160) String name,
                                    @NotNull LocalDate validFrom, @NotBlank @Size(max = 80) String approvalResolution,
                                    @Min(1) @Max(20) int levels) {
    }

    public record SubjectRequest(@Min(1) @Max(20) int levelNumber, @NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 160) String name,
                                 @PositiveOrZero int teacherContactHours, @PositiveOrZero int practicalHours, @PositiveOrZero int autonomousHours,
                                 @NotNull @DecimalMin("0.01") BigDecimal credits,
                                 @NotBlank @Pattern(regexp = "BASICA|PROFESIONAL|INTEGRACION_CURRICULAR") String organizationUnit, boolean titling) {
    }

    public record PeriodRequest(@NotBlank @Pattern(regexp = "^[0-9]{4}-[A-Z0-9]{1,4}$") String code, @NotBlank @Size(max = 80) String name,
                                @NotBlank @Pattern(regexp = "ORDINARY|EXTRAORDINARY") String type, @NotNull LocalDate ordinaryEnrollmentFrom,
                                @NotNull LocalDate ordinaryEnrollmentTo, @NotNull LocalDate extraordinaryEnrollmentTo,
                                @NotNull LocalDate specialEnrollmentTo, @NotNull LocalDate classesStart, @NotNull LocalDate classesEnd,
                                @NotNull LocalDate gradesCloseDeadline) {
    }

    public record SectionRequest(@NotNull UUID subjectId, @NotBlank @Pattern(regexp = "^[A-Z0-9]{1,5}$") String code, @Positive int capacity,
                                 UUID teacherId, UUID classroomId) {
    }

    public record BlockRequest(@Min(1) @Max(7) int weekday, @NotNull LocalTime start, @NotNull LocalTime end, UUID classroomId) {
    }

    private final CatalogService catalog;
    private final PeriodService periods;
    private final SectionService sections;

    public CatalogController(CatalogService catalog, PeriodService periods, SectionService sections) {
        this.catalog = catalog;
        this.periods = periods;
        this.sections = sections;
    }

    @RequiresPermission({"CATALOG_READ", "SELF_SERVICE", "TEACHING_OWN_SECTIONS"})
    @GetMapping("/api/catalog/careers")
    public PageResponse<CatalogViews.CareerView> careers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.careers(PageQuery.of(page, size));
    }

    @RequiresPermission("CATALOG_READ")
    @GetMapping("/api/catalog/careers/{careerId}/curricula")
    public List<CatalogViews.CurriculumView> curricula(@PathVariable UUID careerId) {
        return catalog.curricula(careerId);
    }

    @RequiresPermission("CATALOG_READ")
    @GetMapping("/api/catalog/curricula/{curriculumId}")
    public CatalogViews.CurriculumTree tree(@PathVariable UUID curriculumId) {
        return catalog.tree(curriculumId);
    }

    @RequiresPermission({"CATALOG_READ", "SELF_SERVICE", "TEACHING_OWN_SECTIONS"})
    @GetMapping("/api/catalog/periods")
    public PageResponse<CatalogViews.PeriodView> periods(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return periods.list(PageQuery.of(page, size));
    }

    @RequiresPermission({"CATALOG_READ", "SELF_SERVICE", "TEACHING_OWN_SECTIONS"})
    @GetMapping("/api/catalog/periods/current")
    public CatalogViews.PeriodView currentPeriod() {
        return periods.current();
    }

    @RequiresPermission("CATALOG_READ")
    @GetMapping("/api/catalog/periods/{periodId}/sections")
    public PageResponse<CatalogViews.SectionView> sections(@PathVariable UUID periodId, @RequestParam(required = false) UUID subjectId,
                                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return sections.list(periodId, subjectId, PageQuery.of(page, size));
    }

    // ------------------------------------------------------------ escritura
    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/careers")
    public CatalogViews.CareerView createCareer(@Valid @RequestBody CareerRequest r) {
        return catalog.createCareer(new CatalogService.CareerCommand(r.facultyId(), r.campusId(), r.code(), r.senescytCode(), r.name(),
                r.degreeTitle(), r.modality(), r.educationLevel(), r.durationPeriods()));
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/careers/{careerId}/curricula")
    public CatalogViews.CurriculumView createCurriculum(@PathVariable UUID careerId, @Valid @RequestBody CurriculumRequest r) {
        return catalog.createCurriculum(careerId, new CatalogService.CurriculumCommand(r.versionCode(), r.name(), r.validFrom(),
                r.approvalResolution(), r.levels()));
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/curricula/{curriculumId}/activate")
    public CatalogViews.CurriculumView activate(@PathVariable UUID curriculumId) {
        return catalog.activateCurriculum(curriculumId);
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/curricula/{curriculumId}/subjects")
    public CatalogViews.SubjectView addSubject(@PathVariable UUID curriculumId, @Valid @RequestBody SubjectRequest r) {
        return catalog.addSubject(curriculumId, new CatalogService.SubjectCommand(r.levelNumber(), r.code(), r.name(),
                r.teacherContactHours(), r.practicalHours(), r.autonomousHours(), r.credits(), r.organizationUnit(), r.titling()));
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/subjects/{subjectId}/prerequisites/{requiredId}")
    public ResponseEntity<Void> prerequisite(@PathVariable UUID subjectId, @PathVariable UUID requiredId) {
        catalog.addPrerequisite(subjectId, requiredId);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/subjects/{subjectId}/corequisites/{coId}")
    public ResponseEntity<Void> corequisite(@PathVariable UUID subjectId, @PathVariable UUID coId) {
        catalog.addCorequisite(subjectId, coId);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/periods")
    public CatalogViews.PeriodView createPeriod(@Valid @RequestBody PeriodRequest r) {
        return periods.create(new PeriodService.PeriodCommand(r.code(), r.name(), r.type(), r.ordinaryEnrollmentFrom(),
                r.ordinaryEnrollmentTo(), r.extraordinaryEnrollmentTo(), r.specialEnrollmentTo(), r.classesStart(), r.classesEnd(),
                r.gradesCloseDeadline()));
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/periods/{periodId}/status/{status}")
    public CatalogViews.PeriodView moveStatus(@PathVariable UUID periodId, @PathVariable String status) {
        return periods.moveTo(periodId, status);
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/periods/{periodId}/sections")
    public Map<String, UUID> createSection(@PathVariable UUID periodId, @Valid @RequestBody SectionRequest r) {
        return Map.of("id", sections.create(new SectionService.SectionCommand(periodId, r.subjectId(), r.code(), r.capacity(),
                r.teacherId(), r.classroomId())));
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/catalog/sections/{sectionId}/blocks")
    public ResponseEntity<Void> addBlock(@PathVariable UUID sectionId, @Valid @RequestBody BlockRequest r) {
        sections.addBlock(sectionId, new SectionService.BlockCommand(r.weekday(), r.start(), r.end(), r.classroomId()));
        return ResponseEntity.noContent().build();
    }
}
