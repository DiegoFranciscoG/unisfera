package ec.unisfera.academic.api;

import ec.unisfera.academic.application.AcademicAdminService;
import ec.unisfera.academic.application.CurriculumProgressService;
import ec.unisfera.academic.application.EnrollmentService;
import ec.unisfera.academic.application.StudentService;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administración académica")
@RestController
@RequestMapping("/api/admin/academic")
public class AcademicAdminController {

    public record AdmitRequest(@NotNull UUID curriculumId, @NotNull @PositiveOrZero BigDecimal score) {
    }

    public record StudentRequest(@NotNull UUID personId, @NotNull UUID careerId, @NotNull UUID curriculumId, @NotNull UUID cohortPeriodId) {
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "^[A-Z_]+$") String toStatus, @Size(max = 40) String process) {
    }

    public record TeacherRequest(@NotNull UUID personId, @NotBlank @Size(max = 200) String highestDegree,
                                 @NotBlank @Pattern(regexp = "TIEMPO_COMPLETO|MEDIO_TIEMPO|TIEMPO_PARCIAL") String dedication,
                                 @NotBlank @Pattern(regexp = "AUXILIAR|AGREGADO|PRINCIPAL|OCASIONAL") String category) {
    }

    public record AdmissionProcessRequest(@NotNull UUID periodId, @NotNull UUID careerId, @Positive int quota, @NotNull LocalDate opensOn,
                                          @NotNull LocalDate closesOn, @NotNull @PositiveOrZero BigDecimal minScore) {
    }

    public record ApplicantRequest(@NotNull UUID personId) {
    }

    public record AuthorizationRequest(@NotBlank @Pattern(regexp = "^[A-Z_]+$") String type, UUID studentId, UUID periodId, UUID subjectId,
                                       UUID sectionId, @NotBlank @Size(max = 40) String causeCode, @NotBlank @Size(min = 10, max = 1000) String reason,
                                       @Size(max = 80) String resolutionRef) {
    }

    public record DecisionRequest(boolean approve, @Size(max = 500) String note) {
    }

    public record AnnulRequest(@NotBlank @Size(min = 10, max = 1000) String reason, @NotBlank @Size(max = 160) String legalBasis) {
    }

    public record EnrollOtherRequest(@NotNull @Size(min = 1, max = 12) List<@NotNull UUID> sectionIds) {
    }

    public record HomologationRequest(@NotNull UUID studentId, @NotNull UUID targetSubjectId,
                                      @NotBlank @Pattern(regexp = "CONTENT_ANALYSIS|KNOWLEDGE_VALIDATION|PROFESSIONAL_PRACTICE|INTERNAL_RECOGNITION") String method,
                                      @NotBlank @Size(max = 200) String sourceInstitution, @NotBlank @Size(max = 200) String sourceSubjectName,
                                      @Positive Integer sourceHours, @PositiveOrZero BigDecimal sourceGrade, @Positive BigDecimal sourceScaleMax,
                                      LocalDate sourceApprovedOn, @DecimalMin("0") @DecimalMax("100") BigDecimal coveragePercentage,
                                      @NotNull @PositiveOrZero BigDecimal grade) {
    }

    public record ResolutionRequest(@NotBlank @Size(max = 80) String resolutionRef) {
    }

    private final StudentService students;
    private final AcademicAdminService admin;
    private final EnrollmentService enrollment;
    private final CurriculumProgressService progress;

    public AcademicAdminController(StudentService students, AcademicAdminService admin, EnrollmentService enrollment,
                                   CurriculumProgressService progress) {
        this.students = students;
        this.admin = admin;
        this.enrollment = enrollment;
        this.progress = progress;
    }

    @RequiresPermission("STUDENT_READ")
    @GetMapping("/students")
    public PageResponse<StudentService.StudentSummary> search(@RequestParam(required = false) String q, @RequestParam(required = false) UUID careerId,
                                                              @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return students.search(q, careerId, status, PageQuery.of(page, size));
    }

    @RequiresPermission({"STUDENT_READ", "GRADE_READ"})
    @GetMapping("/students/{studentId}/curriculum")
    public CurriculumProgressService.Progress studentCurriculum(@PathVariable UUID studentId) {
        return progress.progress(studentId);
    }

    @RequiresPermission("STUDENT_READ")
    @GetMapping("/students/{studentId}/status-history")
    public List<StudentService.StatusEvent> history(@PathVariable UUID studentId) {
        return students.history(studentId);
    }

    @RequiresPermission("STUDENT_WRITE")
    @PostMapping("/students")
    public StudentService.AdmissionResult createStudent(@Valid @RequestBody StudentRequest r) {
        return students.createStudent(r.personId(), r.careerId(), r.curriculumId(), r.cohortPeriodId(), null);
    }

    @RequiresPermission("STUDENT_WRITE")
    @PostMapping("/students/{studentId}/status")
    public ResponseEntity<Void> changeStatus(@PathVariable UUID studentId, @Valid @RequestBody StatusRequest r) {
        students.changeStatus(studentId, r.toStatus(), r.process());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("ADMISSION_MANAGE")
    @PostMapping("/admission-processes")
    public Map<String, UUID> createProcess(@Valid @RequestBody AdmissionProcessRequest r) {
        return Map.of("id", admin.createAdmissionProcess(r.periodId(), r.careerId(), r.quota(), r.opensOn(), r.closesOn(), r.minScore()));
    }

    @RequiresPermission("ADMISSION_MANAGE")
    @PostMapping("/admission-processes/{processId}/applicants")
    public Map<String, UUID> registerApplicant(@PathVariable UUID processId, @Valid @RequestBody ApplicantRequest r) {
        return Map.of("id", admin.registerApplicant(processId, r.personId()));
    }

    @RequiresPermission({"ADMISSION_MANAGE"})
    @PostMapping("/applicants/{applicantId}/admit")
    public StudentService.AdmissionResult admit(@PathVariable UUID applicantId, @Valid @RequestBody AdmitRequest r) {
        return students.admitApplicant(applicantId, r.curriculumId(), r.score());
    }

    @RequiresPermission("TEACHER_MANAGE")
    @PostMapping("/teachers")
    public Map<String, UUID> createTeacher(@Valid @RequestBody TeacherRequest r) {
        return Map.of("id", admin.createTeacher(new AcademicAdminService.TeacherCommand(r.personId(), r.highestDegree(), r.dedication(), r.category())));
    }

    @RequiresPermission({"ACADEMIC_EXCEPTION_GRANT", "DEBT_EXCEPTION_GRANT", "COMMITTEE_RESOLVE"})
    @PostMapping("/authorizations")
    public Map<String, UUID> authorize(@Valid @RequestBody AuthorizationRequest r) {
        return Map.of("id", admin.authorize(new AcademicAdminService.AuthorizationCommand(r.type(), r.studentId(), r.periodId(), r.subjectId(),
                r.sectionId(), r.causeCode(), r.reason(), r.resolutionRef())));
    }

    @RequiresPermission("ENROLLMENT_MANAGE")
    @PostMapping("/students/{studentId}/enrollment/preview")
    public EnrollmentService.Preview previewFor(@PathVariable UUID studentId, @Valid @RequestBody EnrollOtherRequest r) {
        return enrollment.preview(studentId, r.sectionIds());
    }

    @RequiresPermission("ENROLLMENT_MANAGE")
    @PostMapping("/students/{studentId}/enrollment")
    public EnrollmentService.Confirmation enrollFor(@PathVariable UUID studentId, @Valid @RequestBody EnrollOtherRequest r) {
        return enrollment.enroll(studentId, r.sectionIds());
    }

    @RequiresPermission("ENROLLMENT_MANAGE")
    @PostMapping("/enrollments/{enrollmentId}/annul")
    public ResponseEntity<Void> annul(@PathVariable UUID enrollmentId, @Valid @RequestBody AnnulRequest r) {
        enrollment.annul(enrollmentId, r.reason(), r.legalBasis());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("WITHDRAWAL_DECIDE")
    @PostMapping("/withdrawals/{withdrawalId}/decision")
    public ResponseEntity<Void> decide(@PathVariable UUID withdrawalId, @Valid @RequestBody DecisionRequest r) {
        admin.decideWithdrawal(withdrawalId, r.approve(), r.note());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("HOMOLOGATION_MANAGE")
    @PostMapping("/homologations")
    public Map<String, UUID> homologate(@Valid @RequestBody HomologationRequest r) {
        return Map.of("id", admin.registerHomologation(new AcademicAdminService.HomologationCommand(r.studentId(), r.targetSubjectId(), r.method(),
                r.sourceInstitution(), r.sourceSubjectName(), r.sourceHours(), r.sourceGrade(), r.sourceScaleMax(), r.sourceApprovedOn(),
                r.coveragePercentage(), r.grade())));
    }

    @RequiresPermission("HOMOLOGATION_MANAGE")
    @PostMapping("/homologations/{homologationId}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID homologationId, @Valid @RequestBody ResolutionRequest r) {
        admin.approveHomologation(homologationId, r.resolutionRef());
        return ResponseEntity.noContent().build();
    }
}
