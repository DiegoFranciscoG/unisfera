package ec.unisfera.reporting.api;

import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.reporting.application.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tablero")
@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @RequiresPermission("REPORT_READ")
    @GetMapping("/api/admin/dashboard")
    public DashboardService.Dashboard dashboard() {
        return dashboard.dashboard();
    }
}
