package ec.unisfera.audit.api;

import ec.unisfera.audit.application.AuditService;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auditoría")
@RestController
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @RequiresPermission("AUDIT_READ")
    @GetMapping("/api/audit")
    public PageResponse<AuditService.AuditEntry> search(@RequestParam(required = false) String table, @RequestParam(required = false) String recordId,
                                                        @RequestParam(required = false) String action, @RequestParam(required = false) UUID actor,
                                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return audit.search(table, recordId, action, actor, PageQuery.of(page, size));
    }
}
