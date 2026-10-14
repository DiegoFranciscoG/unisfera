package ec.unisfera.academic.api;

import ec.unisfera.academic.application.AcademicAdminService;
import ec.unisfera.academic.application.CurriculumProgressService;
import ec.unisfera.academic.application.EnrollmentGate;
import ec.unisfera.academic.application.EnrollmentService;
import ec.unisfera.identity.security.AccessGuard;
import ec.unisfera.identity.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Portal del estudiante: el estudiante se deriva del token; no hay identificadores ajenos en la URL. */
@Tag(name = "Portal del estudiante · académico")
@RestController
@RequestMapping("/api/me")
@RequiresPermission("SELF_SERVICE")
public class StudentPortalController {

    public record EnrollmentRequest(@NotNull @Size(max = 12) List<@NotNull UUID> sectionIds) {
    }

    public record WithdrawalRequest(@NotNull UUID enrollmentDetailId, @NotBlank @Pattern(regexp = "VOLUNTARY|FORCE_MAJEURE") String type,
                                    @NotBlank @Size(min = 10, max = 1000) String reason, UUID documentFileId) {
    }

    private final AccessGuard guard;
    private final CurriculumProgressService progress;
    private final EnrollmentService enrollment;
    private final AcademicAdminService admin;
    private final EnrollmentGate gate;

    public StudentPortalController(AccessGuard guard, CurriculumProgressService progress, EnrollmentService enrollment, AcademicAdminService admin,
                                   EnrollmentGate gate) {
        this.guard = guard;
        this.progress = progress;
        this.enrollment = enrollment;
        this.admin = admin;
        this.gate = gate;
    }

    @Operation(summary = "Mi malla con avance: aprobadas, en curso, disponibles y bloqueadas por prerrequisito")
    @GetMapping("/curriculum")
    public CurriculumProgressService.Progress curriculum() {
        return progress.progress(guard.currentStudentId());
    }

    @Operation(summary = "Oferta del período: asignaturas que puedo tomar con cupos en vivo")
    @GetMapping("/enrollment/offer")
    public CurriculumProgressService.Offer offer() {
        return progress.offer(guard.currentStudentId());
    }

    @Operation(summary = "Validación en vivo y resumen de costos antes de confirmar")
    @PostMapping("/enrollment/preview")
    public EnrollmentService.Preview preview(@Valid @RequestBody EnrollmentRequest body) {
        return enrollment.preview(guard.currentStudentId(), body.sectionIds());
    }

    @Operation(summary = "Confirmar matrícula (la base reserva el cupo de forma atómica)")
    @PostMapping("/enrollment")
    public EnrollmentService.Confirmation enroll(@Valid @RequestBody EnrollmentRequest body) {
        return gate.enroll(guard.currentStudentId(), body.sectionIds());
    }

    @GetMapping("/enrollment")
    public ResponseEntity<EnrollmentService.Confirmation> current() {
        EnrollmentService.Confirmation c = enrollment.current(guard.currentStudentId());
        return c == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(c);
    }

    @GetMapping("/schedule")
    public List<CurriculumProgressService.ScheduleEntry> schedule() {
        return progress.schedule(guard.currentStudentId());
    }

    @GetMapping("/withdrawals")
    public List<AcademicAdminService.WithdrawalView> withdrawals() {
        return admin.withdrawals(guard.currentStudentId());
    }

    @PostMapping("/withdrawals")
    public Map<String, UUID> requestWithdrawal(@Valid @RequestBody WithdrawalRequest body) {
        return Map.of("id", admin.requestWithdrawal(guard.currentStudentId(), body.enrollmentDetailId(), body.type(), body.reason(),
                body.documentFileId()));
    }
}
