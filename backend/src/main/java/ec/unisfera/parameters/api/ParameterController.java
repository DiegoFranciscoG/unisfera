package ec.unisfera.parameters.api;

import ec.unisfera.identity.security.PublicEndpoint;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.parameters.domain.ParameterVersion;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Parámetros (LEY-1)")
@RestController
public class ParameterController {

    public record PublishRequest(@NotBlank @Size(max = 500) String value, Instant validFrom, @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    /** Valores que la interfaz necesita para validar en vivo: los mismos que usan la base y el dominio (Ley 3). */
    public record UiSettings(BigDecimal scaleMin, BigDecimal scaleMax, BigDecimal passingGrade, BigDecimal minAttendance,
                             BigDecimal attendanceWarningMargin, int maxSubjectsPerPeriod, int passwordMinLength,
                             String currency, String zone, String closeConfirmationPhrase) {
    }

    private final ParameterService parameters;
    private final AppProperties props;

    public ParameterController(ParameterService parameters, AppProperties props) {
        this.parameters = parameters;
        this.props = props;
    }

    @RequiresPermission("PARAMETER_READ")
    @GetMapping("/api/parameters")
    public PageResponse<ParameterVersion> current(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return parameters.current(PageQuery.of(page, size));
    }

    @RequiresPermission("PARAMETER_READ")
    @GetMapping("/api/parameters/{key}/history")
    public PageResponse<ParameterVersion> history(@PathVariable String key, @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return parameters.history(key, PageQuery.of(page, size));
    }

    @Operation(summary = "Publica una nueva versión con vigencia; la anterior se cierra, nunca se sobrescribe")
    @RequiresPermission("PARAMETER_WRITE")
    @PostMapping("/api/parameters/{key}/versions")
    public ParameterVersion publish(@PathVariable String key, @Valid @RequestBody PublishRequest body) {
        return parameters.publish(key, body.value().trim(), body.validFrom(), body.reason());
    }

    @PublicEndpoint
    @GetMapping("/api/public/settings")
    public UiSettings settings() {
        return new UiSettings(parameters.decimal(ParameterKeys.SCALE_MIN), parameters.decimal(ParameterKeys.SCALE_MAX),
                parameters.decimal(ParameterKeys.PASSING_GRADE), parameters.decimal(ParameterKeys.MIN_ATTENDANCE),
                parameters.decimal(ParameterKeys.ATTENDANCE_WARNING_MARGIN), parameters.integer(ParameterKeys.MAX_SUBJECTS),
                parameters.integer(ParameterKeys.PASSWORD_MIN_LENGTH), parameters.text(ParameterKeys.CURRENCY), props.zone(),
                parameters.text("evaluation.close_confirmation_phrase"));
    }
}
