package ec.unisfera.reporting.api;

import ec.unisfera.audit.application.AuditService;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.reporting.application.ReportJobService;
import ec.unisfera.reporting.application.ReportService;
import ec.unisfera.reporting.domain.ReportTable;
import ec.unisfera.shared.domain.ForbiddenException;
import ec.unisfera.shared.file.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reportes")
@RestController
public class ReportController {

    private final ReportService reports;
    private final ReportJobService jobs;
    private final FileStorageService files;
    private final AuditService audit;

    public ReportController(ReportService reports, ReportJobService jobs, FileStorageService files, AuditService audit) {
        this.reports = reports;
        this.jobs = jobs;
        this.files = files;
        this.audit = audit;
    }

    @RequiresPermission("REPORT_READ")
    @GetMapping("/api/reports")
    public List<ReportService.Definition> catalog() {
        return ReportService.CATALOG;
    }

    @Operation(summary = "Vista previa del reporte con fecha de corte")
    @RequiresPermission("REPORT_READ")
    @GetMapping("/api/reports/{code}")
    public ReportTable run(@PathVariable String code, @RequestParam(required = false) UUID periodId, @RequestParam(required = false) LocalDate cutoff) {
        if ("control".equals(code)) {
            audit.recordExport(code, "Consulta del listado con datos personales");
        }
        return reports.run(code, periodId, cutoff);
    }

    @Operation(summary = "Exportar en segundo plano a Excel o PDF; notifica al terminar")
    @RequiresPermission("REPORT_EXPORT")
    @PostMapping("/api/reports/{code}/jobs")
    public Map<String, UUID> export(@PathVariable String code, @RequestParam(defaultValue = "XLSX") String format,
                                    @RequestParam(required = false) UUID periodId, @RequestParam(required = false) LocalDate cutoff) {
        ReportService.CATALOG.stream().filter(d -> d.code().equals(code)).findFirst()
                .orElseThrow(() -> new ec.unisfera.shared.domain.NotFoundException("El reporte"));
        return Map.of("jobId", jobs.enqueue(code, "PDF".equals(format) ? "PDF" : "XLSX", periodId, cutoff));
    }

    @RequiresPermission("REPORT_EXPORT")
    @GetMapping("/api/reports/jobs")
    public List<ReportJobService.JobView> myJobs() {
        return jobs.myJobs();
    }

    @RequiresPermission("REPORT_EXPORT")
    @GetMapping("/api/reports/jobs/{jobId}")
    public ReportJobService.JobView job(@PathVariable UUID jobId) {
        return jobs.job(jobId);
    }

    @RequiresPermission("REPORT_EXPORT")
    @GetMapping("/api/reports/jobs/{jobId}/file")
    public ResponseEntity<byte[]> download(@PathVariable UUID jobId) {
        ReportJobService.JobView job = jobs.job(jobId);
        if (job.fileId() == null) {
            throw new ForbiddenException("job:" + jobId);
        }
        FileStorageService.StoredFile meta = files.metadata(job.fileId());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(meta.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + meta.originalName() + "\"").body(files.content(job.fileId()));
    }
}
