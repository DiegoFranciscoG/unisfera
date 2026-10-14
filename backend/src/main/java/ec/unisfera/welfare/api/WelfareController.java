package ec.unisfera.welfare.api;

import ec.unisfera.identity.security.AccessGuard;
import ec.unisfera.identity.security.AuthenticatedOnly;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.identity.security.SecurityUtils;
import ec.unisfera.welfare.application.ScholarshipService;
import ec.unisfera.welfare.application.WelfareCareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Bienestar estudiantil")
@RestController
public class WelfareController {

    public record DraftRequest(@NotNull UUID callId, @Size(max = 4000) String statement) {
    }

    public record StatementRequest(@Size(max = 4000) String statement) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record AppealRequest(@NotBlank @Size(min = 20, max = 4000) String statement) {
    }

    public record ConsentRequest(@NotBlank @Pattern(regexp = "WELFARE_SENSITIVE|SOCIOECONOMIC|PHOTO|COMMUNICATIONS") String purpose) {
    }

    public record TypeRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name, @NotBlank String description,
                              @NotBlank @Pattern(regexp = "PERCENT|AMOUNT") String mode, @NotNull @Positive BigDecimal value, BigDecimal minAverage,
                              BigDecimal minAttendance, BigDecimal partialMinAverage, BigDecimal partialValue, boolean renewable,
                              @NotEmpty List<@NotBlank String> coveredConcepts) {
    }

    public record CallRequest(@NotNull UUID typeId, @NotNull UUID periodId, @NotBlank @Size(max = 160) String name, @Positive int quota,
                              @NotNull Instant opensAt, @NotNull Instant closesAt, @NotNull LocalDate resultsBy,
                              @NotEmpty List<ScholarshipService.DocumentInput> documents, @NotEmpty List<ScholarshipService.CriterionInput> criteria) {
    }

    public record ScoreRequest(@NotEmpty Map<UUID, @DecimalMin("0") BigDecimal> scores) {
    }

    public record ResolutionRequest(@NotBlank @Size(max = 40) String actNumber, @NotNull LocalDate sessionDate, @NotBlank String summary,
                                    @NotEmpty List<ScholarshipService.Member> members, @NotNull List<UUID> approved, @NotNull List<UUID> rejected) {
    }

    public record DecisionRequest(boolean accept, @NotBlank @Size(max = 1000) String note) {
    }

    public record AttentionRequest(@NotBlank @Pattern(regexp = "HEALTH|PSYCHOLOGY|ORIENTATION|SOCIAL_WORK") String category, @NotNull Instant attendedAt,
                                   @NotBlank @Size(max = 2000) String summary, @Size(max = 8000) String details, boolean followUpRequired) {
    }

    public record AlertUpdate(@NotBlank @Pattern(regexp = "OPEN|IN_FOLLOW_UP|CLOSED") String status, @Size(max = 1000) String note) {
    }

    private final AccessGuard guard;
    private final ScholarshipService scholarships;
    private final WelfareCareService care;

    public WelfareController(AccessGuard guard, ScholarshipService scholarships, WelfareCareService care) {
        this.guard = guard;
        this.scholarships = scholarships;
        this.care = care;
    }

    // ------------------------------------------------------------ estudiante
    @RequiresPermission({"SELF_SERVICE", "SCHOLARSHIP_READ"})
    @GetMapping("/api/scholarships/calls/open")
    public List<ScholarshipService.CallView> openCalls() {
        return scholarships.calls(true);
    }

    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/scholarships/applications")
    public List<ScholarshipService.ApplicationView> myApplications() {
        return scholarships.applicationsOf(guard.currentStudentId());
    }

    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/scholarships/applications")
    public Map<String, UUID> draft(@Valid @RequestBody DraftRequest body) {
        return Map.of("id", scholarships.draft(guard.currentStudentId(), body.callId(), body.statement()));
    }

    @RequiresPermission("SELF_SERVICE")
    @PutMapping("/api/me/scholarships/applications/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody StatementRequest body) {
        scholarships.updateDraft(guard.currentStudentId(), id, body.statement());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Adjuntar un documento exigido (PDF/PNG/JPEG validados por firma de bytes)")
    @RequiresPermission("SELF_SERVICE")
    @PostMapping(value = "/api/me/scholarships/applications/{id}/documents/{code}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> attach(@PathVariable UUID id, @PathVariable @Pattern(regexp = "^[A-Z0-9_]{2,40}$") String code,
                                       @RequestPart("file") MultipartFile file) throws IOException {
        scholarships.attach(guard.currentStudentId(), SecurityUtils.requireUser().personId(), id, code, file.getBytes(), file.getOriginalFilename());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/scholarships/applications/{id}/submit")
    public ResponseEntity<Void> submit(@PathVariable UUID id) {
        scholarships.submit(guard.currentStudentId(), id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/scholarships/applications/{id}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        scholarships.withdraw(guard.currentStudentId(), id, body.reason());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/scholarships/awards")
    public List<ScholarshipService.AwardView> myAwards() {
        return scholarships.awardsOf(guard.currentStudentId());
    }

    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/scholarships/followups/{followupId}/appeal")
    public Map<String, UUID> appeal(@PathVariable UUID followupId, @Valid @RequestBody AppealRequest body) {
        return Map.of("id", scholarships.appeal(guard.currentStudentId(), followupId, body.statement()));
    }

    @AuthenticatedOnly
    @GetMapping("/api/me/consents")
    public List<WelfareCareService.Consent> consents() {
        return care.consents(SecurityUtils.requireUser().personId());
    }

    @Operation(summary = "Consentimiento explícito (LOPDP arts. 8 y 26)")
    @AuthenticatedOnly
    @PostMapping("/api/me/consents")
    public ResponseEntity<Void> grant(@Valid @RequestBody ConsentRequest body) {
        care.grantConsent(SecurityUtils.requireUser().personId(), body.purpose());
        return ResponseEntity.noContent().build();
    }

    @AuthenticatedOnly
    @PostMapping("/api/me/consents/revoke")
    public ResponseEntity<Void> revoke(@Valid @RequestBody ConsentRequest body) {
        care.revokeConsent(SecurityUtils.requireUser().personId(), body.purpose());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ bienestar
    @RequiresPermission("SCHOLARSHIP_READ")
    @GetMapping("/api/admin/welfare/calls")
    public List<ScholarshipService.CallView> calls() {
        return scholarships.calls(false);
    }

    @RequiresPermission("SCHOLARSHIP_READ")
    @GetMapping("/api/admin/welfare/types")
    public List<ScholarshipService.TypeView> types() {
        return scholarships.types();
    }

    @RequiresPermission("SCHOLARSHIP_READ")
    @GetMapping("/api/admin/welfare/calls/{callId}/criteria")
    public List<ScholarshipService.CriterionView> criteria(@PathVariable UUID callId) {
        return scholarships.criteria(callId);
    }

    @RequiresPermission("SCHOLARSHIP_MANAGE")
    @PostMapping("/api/admin/welfare/types")
    public Map<String, UUID> createType(@Valid @RequestBody TypeRequest r) {
        return Map.of("id", scholarships.createType(new ScholarshipService.TypeInput(r.code(), r.name(), r.description(), r.mode(), r.value(),
                r.minAverage(), r.minAttendance(), r.partialMinAverage(), r.partialValue(), r.renewable(), r.coveredConcepts())));
    }

    @RequiresPermission("SCHOLARSHIP_MANAGE")
    @PostMapping("/api/admin/welfare/calls")
    public Map<String, UUID> createCall(@Valid @RequestBody CallRequest r) {
        return Map.of("id", scholarships.createCall(r.typeId(), r.periodId(), r.name(), r.quota(), r.opensAt(), r.closesAt(), r.resultsBy(),
                r.documents(), r.criteria()));
    }

    @RequiresPermission("SCHOLARSHIP_READ")
    @GetMapping("/api/admin/welfare/calls/{callId}/applications")
    public List<ScholarshipService.ApplicationView> applications(@PathVariable UUID callId) {
        return scholarships.applicationsOfCall(callId);
    }

    @RequiresPermission("SCHOLARSHIP_MANAGE")
    @PostMapping("/api/admin/welfare/applications/{id}/score")
    public Map<String, BigDecimal> score(@PathVariable UUID id, @Valid @RequestBody ScoreRequest r) {
        return Map.of("totalScore", scholarships.score(id, r.scores(), SecurityUtils.requireUser().userId()));
    }

    @RequiresPermission("COMMITTEE_RESOLVE")
    @PostMapping("/api/admin/welfare/calls/{callId}/resolution")
    public Map<String, UUID> resolve(@PathVariable UUID callId, @Valid @RequestBody ResolutionRequest r) {
        return Map.of("id", scholarships.resolve(callId, r.actNumber(), r.sessionDate(), r.summary(), r.members(), r.approved(), r.rejected()));
    }

    @RequiresPermission("SCHOLARSHIP_MANAGE")
    @PostMapping("/api/admin/welfare/periods/{periodId}/followup")
    public Map<String, Integer> followup(@PathVariable UUID periodId) {
        return Map.of("evaluated", scholarships.followup(periodId));
    }

    @RequiresPermission("COMMITTEE_RESOLVE")
    @PostMapping("/api/admin/welfare/appeals/{appealId}/decision")
    public ResponseEntity<Void> decideAppeal(@PathVariable UUID appealId, @Valid @RequestBody DecisionRequest r) {
        scholarships.decideAppeal(appealId, r.accept(), r.note());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Atenciones de bienestar (dato sensible: solo rol BIENESTAR, lectura auditada)")
    @RequiresPermission("WELFARE_SENSITIVE")
    @GetMapping("/api/admin/welfare/students/{studentId}/attentions")
    public List<WelfareCareService.Attention> attentions(@PathVariable UUID studentId) {
        return care.attentions(studentId);
    }

    @RequiresPermission("WELFARE_SENSITIVE")
    @PostMapping("/api/admin/welfare/students/{studentId}/attentions")
    public Map<String, UUID> record(@PathVariable UUID studentId, @Valid @RequestBody AttentionRequest r) {
        return Map.of("id", care.record(studentId, r.category(), r.attendedAt(), r.summary(), r.details(), r.followUpRequired()));
    }

    @RequiresPermission("DROPOUT_MANAGE")
    @PostMapping("/api/admin/welfare/periods/{periodId}/dropout-alerts/detect")
    public Map<String, Integer> detect(@PathVariable UUID periodId) {
        return Map.of("alerts", care.detectDropoutRisk(periodId));
    }

    @RequiresPermission("DROPOUT_MANAGE")
    @GetMapping("/api/admin/welfare/periods/{periodId}/dropout-alerts")
    public List<WelfareCareService.Alert> alerts(@PathVariable UUID periodId, @RequestParam(required = false) String status) {
        return care.alerts(periodId, status);
    }

    @RequiresPermission("DROPOUT_MANAGE")
    @PostMapping("/api/admin/welfare/dropout-alerts/{alertId}")
    public ResponseEntity<Void> updateAlert(@PathVariable UUID alertId, @Valid @RequestBody AlertUpdate r) {
        care.updateAlert(alertId, r.status(), r.note());
        return ResponseEntity.noContent().build();
    }
}
