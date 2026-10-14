package ec.unisfera.evaluation.api;

import ec.unisfera.evaluation.application.KardexService;
import ec.unisfera.evaluation.application.SchemeService;
import ec.unisfera.evaluation.application.TeachingService;
import ec.unisfera.identity.security.AccessGuard;
import ec.unisfera.identity.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Evaluación, actas y kardex")
@RestController
public class EvaluationController {

    public record GradesRequest(@NotEmpty @Size(max = 500) List<@Valid GradeItem> grades) {
    }

    public record GradeItem(@NotNull UUID detailId, @NotNull UUID componentId, @NotNull @DecimalMin("0") BigDecimal value, Long version) {
    }

    public record SessionRequest(@NotNull LocalDate date, @NotNull LocalTime startsAt, @NotBlank @Size(max = 200) String topic) {
    }

    public record AttendanceRequest(@NotEmpty @Size(max = 200) List<@Valid AttendanceItem> marks) {
    }

    public record AttendanceItem(@NotNull UUID detailId, @NotBlank @Pattern(regexp = "PRESENT|ABSENT|LATE|JUSTIFIED") String status) {
    }

    public record CloseRequest(@NotBlank @Size(max = 40) String confirmation) {
    }

    public record SchemeRequest(@NotNull UUID periodId, UUID careerId, @NotEmpty @Size(max = 10) List<@Valid ComponentItem> components) {
    }

    public record ComponentItem(@NotBlank @Size(max = 80) String name, @NotBlank @Pattern(regexp = "FORMATIVE|SUMMATIVE|FINAL") String kind,
                                @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal weight) {
    }

    public record RectifyRequest(@NotNull UUID authorizationId, @NotBlank @Size(min = 10, max = 1000) String reason,
                                 @NotEmpty Map<UUID, Map<UUID, @DecimalMin("0") BigDecimal>> corrections) {
    }

    private final AccessGuard guard;
    private final TeachingService teaching;
    private final KardexService kardex;
    private final SchemeService schemes;

    public EvaluationController(AccessGuard guard, TeachingService teaching, KardexService kardex, SchemeService schemes) {
        this.guard = guard;
        this.teaching = teaching;
        this.kardex = kardex;
        this.schemes = schemes;
    }

    // ---------------------------------------------------------------- docente
    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @GetMapping("/api/teacher/sections")
    public List<TeachingService.SectionCard> mySections() {
        return teaching.sectionsOf(guard.currentTeacherId());
    }

    @Operation(summary = "Lista del paralelo con notas, asistencia y resultado provisional")
    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @GetMapping("/api/teacher/sections/{sectionId}/roster")
    public TeachingService.Roster roster(@PathVariable UUID sectionId) {
        guard.requireOwnSection(sectionId);
        return teaching.roster(sectionId);
    }

    @Operation(summary = "Guardado parcial de notas (bloqueo optimista por versión)")
    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @PostMapping("/api/teacher/sections/{sectionId}/grades")
    public Map<String, Integer> saveGrades(@PathVariable UUID sectionId, @Valid @RequestBody GradesRequest body) {
        guard.requireOwnSection(sectionId);
        return Map.of("saved", teaching.saveGrades(sectionId, body.grades().stream()
                .map(g -> new TeachingService.GradeInput(g.detailId(), g.componentId(), g.value(), g.version())).toList()));
    }

    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @PostMapping("/api/teacher/sections/{sectionId}/sessions")
    public Map<String, UUID> session(@PathVariable UUID sectionId, @Valid @RequestBody SessionRequest body) {
        guard.requireOwnSection(sectionId);
        return Map.of("id", teaching.createSession(sectionId, body.date(), body.startsAt(), body.topic()));
    }

    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @PostMapping("/api/teacher/sections/{sectionId}/sessions/{sessionId}/attendance")
    public Map<String, Integer> attendance(@PathVariable UUID sectionId, @PathVariable UUID sessionId, @Valid @RequestBody AttendanceRequest body) {
        guard.requireOwnSection(sectionId);
        return Map.of("saved", teaching.markAttendance(sectionId, sessionId, body.marks().stream()
                .map(m -> new TeachingService.AttendanceInput(m.detailId(), m.status())).toList()));
    }

    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @GetMapping("/api/teacher/sections/{sectionId}/act/preview")
    public TeachingService.ActPreview preview(@PathVariable UUID sectionId) {
        guard.requireOwnSection(sectionId);
        return teaching.preview(sectionId);
    }

    @Operation(summary = "Cierre del acta: resultados, hash SHA-256 y firma; desde aquí es inmutable")
    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @PostMapping("/api/teacher/sections/{sectionId}/act/close")
    public TeachingService.ActView close(@PathVariable UUID sectionId, @Valid @RequestBody CloseRequest body) {
        guard.requireOwnSection(sectionId);
        return teaching.close(sectionId, body.confirmation());
    }

    @RequiresPermission("TEACHING_OWN_SECTIONS")
    @GetMapping("/api/teacher/acts")
    public List<TeachingService.ActView> myActs() {
        return teaching.actsOfTeacher(guard.currentTeacherId());
    }

    // ------------------------------------------------------------- estudiante
    @Operation(summary = "Mis notas del período: provisionales, lo que me falta para aprobar y asistencia")
    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/grades")
    public List<KardexService.CurrentSubject> myGrades() {
        return kardex.currentGrades(guard.currentStudentId());
    }

    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/kardex")
    public KardexService.Kardex myKardex() {
        return kardex.kardex(guard.currentStudentId());
    }

    // ---------------------------------------------------------- administración
    @RequiresPermission("GRADE_READ")
    @GetMapping("/api/admin/students/{studentId}/kardex")
    public KardexService.Kardex studentKardex(@PathVariable UUID studentId) {
        return kardex.kardex(studentId);
    }

    @RequiresPermission("ACT_READ")
    @GetMapping("/api/admin/sections/{sectionId}/acts")
    public List<TeachingService.ActView> sectionActs(@PathVariable UUID sectionId) {
        return teaching.actsOfSection(sectionId);
    }

    @RequiresPermission("CATALOG_WRITE")
    @PostMapping("/api/admin/evaluation/schemes")
    public SchemeService.SchemeView createScheme(@Valid @RequestBody SchemeRequest body) {
        return schemes.create(body.periodId(), body.careerId(), body.components().stream()
                .map(c -> new SchemeService.ComponentInput(c.name(), c.kind(), c.weight())).toList());
    }

    @Operation(summary = "Rectificación autorizada de un acta cerrada (ambas quedan visibles)")
    @RequiresPermission("ACADEMIC_EXCEPTION_GRANT")
    @PostMapping("/api/admin/acts/{actId}/rectify")
    public TeachingService.ActView rectify(@PathVariable UUID actId, @Valid @RequestBody RectifyRequest body) {
        return teaching.rectify(actId, body.authorizationId(), body.reason(), body.corrections());
    }
}
