package ec.unisfera.requests.api;

import ec.unisfera.identity.security.AccessGuard;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.requests.application.StudentRequestService;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@Tag(name = "Solicitudes")
@RestController
public class StudentRequestController {

    public record CreateRequest(@NotBlank @Pattern(regexp = "CERTIFICATE|WITHDRAWAL|HOMOLOGATION|THIRD_ENROLLMENT|SPECIAL_ENROLLMENT|RECTIFICATION|OTHER") String type,
                                @NotBlank @Size(max = 160) String subject, @NotBlank @Size(min = 10, max = 4000) String detail) {
    }

    public record TransitionRequest(@NotBlank @Pattern(regexp = "IN_REVIEW|APPROVED|REJECTED") String status, @Size(max = 2000) String resolution) {
    }

    private final AccessGuard guard;
    private final StudentRequestService requests;

    public StudentRequestController(AccessGuard guard, StudentRequestService requests) {
        this.guard = guard;
        this.requests = requests;
    }

    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/requests")
    public List<StudentRequestService.RequestView> mine() {
        return requests.mine(guard.currentStudentId());
    }

    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/requests")
    public Map<String, UUID> create(@Valid @RequestBody CreateRequest r) {
        return Map.of("id", requests.create(guard.currentStudentId(), r.type(), r.subject(), r.detail()));
    }

    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/requests/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        requests.cancel(guard.currentStudentId(), id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("REQUEST_MANAGE")
    @GetMapping("/api/admin/requests")
    public PageResponse<StudentRequestService.RequestView> queue(@RequestParam(required = false) String status,
                                                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return requests.queue(status, PageQuery.of(page, size));
    }

    @RequiresPermission("REQUEST_MANAGE")
    @PostMapping("/api/admin/requests/{id}/status")
    public ResponseEntity<Void> transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) {
        requests.transition(id, r.status(), r.resolution());
        return ResponseEntity.noContent().build();
    }
}
