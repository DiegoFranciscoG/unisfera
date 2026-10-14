package ec.unisfera.shared.web;

import ec.unisfera.audit.application.AuditService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.ForbiddenException;
import ec.unisfera.shared.domain.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Errores en formato RFC 9457 sin detalles internos. Las violaciones que llegan desde la base
 * (triggers y restricciones) se traducen al mismo código RN-xx que usa el dominio: Ley 3.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern RULE = Pattern.compile("^((?:RN-\\d{2})|(?:LEY-\\d)|(?:SEC-\\d{2})): (.+)$", Pattern.DOTALL);
    private static final List<String> RECORDED_DENIALS = List.of("LEY-2", "RN-14", "RN-28");

    /** Restricciones de la base traducidas a la regla y a un mensaje entendible. */
    private static final Map<String, String[]> CONSTRAINTS = Map.ofEntries(
            Map.entry("ex_student_schedule_clash", new String[]{"RN-06", "Hay un choque de horario con otra asignatura de su matrícula"}),
            Map.entry("ex_classroom_clash", new String[]{"RN-06", "El aula ya está ocupada en ese horario"}),
            Map.entry("ck_section_capacity", new String[]{"RN-05", "El paralelo no tiene cupos disponibles"}),
            Map.entry("ck_attempt_range", new String[]{"RN-07", "Cuarta matrícula imposible: la asignatura ya se cursó tres veces"}),
            Map.entry("ck_third_attempt_authorized", new String[]{"RN-07", "La tercera matrícula requiere una autorización registrada"}),
            Map.entry("uq_enrollment_active", new String[]{"RN-01", "El estudiante ya tiene una matrícula vigente en este período"}),
            Map.entry("uq_enrollment_subject", new String[]{"RN-01", "La asignatura ya consta en la matrícula"}),
            Map.entry("ck_line_result_approved", new String[]{"RN-15", "El resultado del acta no coincide con la nota y la asistencia"}),
            Map.entry("ck_line_result_attendance", new String[]{"RN-15", "El resultado del acta no coincide con la asistencia"}),
            Map.entry("uq_payment_idempotency", new String[]{"RN-24", "Este pago ya fue registrado"}),
            Map.entry("uq_payment_gateway_ref", new String[]{"RN-24", "La referencia de la pasarela ya fue registrada"}),
            Map.entry("ck_person_cedula", new String[]{"SEC-01", "La cédula no es válida (dígito verificador)"}),
            Map.entry("uq_person_identity", new String[]{"SEC-01", "Ya existe una persona con esa identificación"}),
            Map.entry("ex_parameter_validity", new String[]{"LEY-1", "Ya existe un valor vigente para ese parámetro en esas fechas"}),
            Map.entry("uq_scheme", new String[]{"RN-11", "Ya existe un esquema de evaluación para ese período y carrera"}),
            Map.entry("uq_application", new String[]{"RN-18", "Ya existe una postulación a esta convocatoria"}),
            Map.entry("ck_installment_paid", new String[]{"RN-29", "El abono supera el saldo de la cuota"}));

    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail p = rule(ex.code(), ex.getMessage(), request);
        if (!ex.details().isEmpty()) {
            p.setProperty("violations", ex.details());
        }
        return p;
    }

    @ExceptionHandler(ec.unisfera.identity.application.AuthenticationFailedException.class)
    ProblemDetail authenticationFailed(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail p = problem(HttpStatus.UNAUTHORIZED, ex.code(), ex.getMessage(), request);
        p.setProperty("helpUrl", RuleHelp.helpUrl(ex.code()));
        return p;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    ProblemDetail forbidden(RuntimeException ex, HttpServletRequest request) {
        audit.recordDenied("http:" + request.getMethod(), request.getRequestURI(), "Acceso denegado");
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tiene acceso a este recurso", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Debe iniciar sesión", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION", "Hay datos inválidos en la solicitud", request);
        p.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList());
        return p;
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    ProblemDetail badRequest(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION", "La solicitud no es válida", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "El archivo supera el tamaño permitido", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrent(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Otra persona modificó este registro; recargue y vuelva a intentar", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método no permitido", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso inexistente", request);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException ex, HttpServletRequest request) {
        Optional<ServerErrorMessage> server = serverMessage(ex);
        if (server.isPresent()) {
            ServerErrorMessage m = server.get();
            Matcher matcher = m.getMessage() == null ? null : RULE.matcher(m.getMessage());
            if (matcher != null && matcher.matches()) {
                return rule(matcher.group(1), matcher.group(2), request);
            }
            String[] mapped = m.getConstraint() == null ? null : CONSTRAINTS.get(m.getConstraint());
            if (mapped != null) {
                return rule(mapped[0], mapped[1], request);
            }
            if ("23505".equals(m.getSQLState())) {
                return problem(HttpStatus.CONFLICT, "DUPLICATE", "El registro ya existe", request);
            }
            if ("23514".equals(m.getSQLState()) || "23503".equals(m.getSQLState()) || "23502".equals(m.getSQLState())) {
                return problem(HttpStatus.UNPROCESSABLE_CONTENT, "INTEGRITY", "Los datos no cumplen las reglas de integridad", request);
            }
        }
        log.error("Error de base de datos no esperado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Ocurrió un error inesperado", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex, HttpServletRequest request) {
        log.error("Error no controlado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Ocurrió un error inesperado", request);
    }

    private ProblemDetail rule(String code, String message, HttpServletRequest request) {
        if (RECORDED_DENIALS.contains(code)) {
            audit.recordDenied("rule:" + code, request.getRequestURI(), message);
        }
        HttpStatus status = code.startsWith("SEC") || code.startsWith("LEY") || code.startsWith("RN")
                ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.BAD_REQUEST;
        ProblemDetail p = problem(status, code, message, request);
        p.setProperty("helpUrl", RuleHelp.helpUrl(code));
        return p;
    }

    private static Optional<ServerErrorMessage> serverMessage(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return Optional.of(psql.getServerErrorMessage());
            }
            if (t instanceof SQLException sql && sql.getNextException() instanceof PSQLException next && next.getServerErrorMessage() != null) {
                return Optional.of(next.getServerErrorMessage());
            }
            t = t.getCause();
        }
        return Optional.empty();
    }

    private static ProblemDetail problem(HttpStatus status, String code, String message, HttpServletRequest request) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, message);
        p.setType(URI.create("urn:unisfera:error:" + code));
        p.setTitle(code);
        p.setInstance(URI.create(request.getRequestURI()));
        p.setProperty("code", code);
        p.setProperty("requestId", RequestContext.requestId());
        return p;
    }
}
