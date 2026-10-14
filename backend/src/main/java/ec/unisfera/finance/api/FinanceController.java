package ec.unisfera.finance.api;

import ec.unisfera.finance.application.FinanceQueryService;
import ec.unisfera.finance.application.InvoiceService;
import ec.unisfera.finance.application.LateFeeService;
import ec.unisfera.finance.application.PaymentService;
import ec.unisfera.finance.gateway.PaymentGateway;
import ec.unisfera.finance.gateway.SimulatedPaymentGateway;
import ec.unisfera.identity.security.AccessGuard;
import ec.unisfera.identity.security.PublicEndpoint;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.shared.domain.ForbiddenException;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Financiero")
@RestController
public class FinanceController {

    public record IntentRequest(@DecimalMin("0.01") BigDecimal amount) {
    }

    public record SimulateRequest(@NotBlank @Pattern(regexp = "APPROVE|REJECT|DELAY|DOUBLE") String scenario) {
    }

    public record ManualPaymentRequest(@NotNull UUID studentId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                                       @NotBlank @Pattern(regexp = "TRANSFER|CASH|CARD_POS") String method, @NotBlank @Size(max = 120) String reference) {
    }

    public record AnnulRequest(@NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record TariffRequest(@NotBlank @Size(max = 30) String conceptCode, UUID careerId, @Min(1) @Max(20) Integer levelNumber,
                                @NotNull UUID periodId, @NotBlank @Pattern(regexp = "FLAT|PER_CREDIT|PER_SUBJECT") String calcMode,
                                @NotNull @DecimalMin("0.00") BigDecimal amount, @NotBlank @Size(min = 10, max = 300) String reason) {
    }

    private final AccessGuard guard;
    private final PaymentService payments;
    private final FinanceQueryService finance;
    private final InvoiceService invoices;
    private final LateFeeService lateFees;
    private final SimulatedPaymentGateway simulator;

    public FinanceController(AccessGuard guard, PaymentService payments, FinanceQueryService finance, InvoiceService invoices,
                             LateFeeService lateFees, SimulatedPaymentGateway simulator) {
        this.guard = guard;
        this.payments = payments;
        this.finance = finance;
        this.invoices = invoices;
        this.lateFees = lateFees;
        this.simulator = simulator;
    }

    // ------------------------------------------------------------ estudiante
    @Operation(summary = "Mi estado de cuenta: cargos − descuentos − pagos = saldo")
    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/account")
    public FinanceQueryService.Statement myAccount(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return finance.statement(guard.currentStudentId(), PageQuery.of(page, size));
    }

    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/payments")
    public List<PaymentService.PaymentView> myPayments() {
        return payments.forStudent(guard.currentStudentId(), 30);
    }

    @Operation(summary = "Iniciar un pago en línea (cabecera Idempotency-Key obligatoria)")
    @RequiresPermission("SELF_SERVICE")
    @PostMapping("/api/me/payments/intents")
    public PaymentService.PaymentView intent(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                             @Valid @RequestBody IntentRequest body) {
        return payments.createIntent(guard.currentStudentId(), body.amount(), key, null);
    }

    @RequiresPermission("SELF_SERVICE")
    @GetMapping("/api/me/invoices/{invoiceId}/ride")
    public ResponseEntity<byte[]> myRide(@PathVariable UUID invoiceId) {
        if (!invoices.studentOfInvoice(invoiceId).equals(guard.currentStudentId())) {
            throw new ForbiddenException("invoice:" + invoiceId);
        }
        return pdf(invoices.ride(invoiceId), "factura-" + invoiceId + ".pdf");
    }

    // -------------------------------------------------------------- pasarela
    @Operation(summary = "Webhook firmado de la pasarela (HMAC-SHA256 en X-Signature)")
    @PublicEndpoint
    @PostMapping(value = "/api/public/gateway/{gateway}/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PaymentService.NotificationResult webhook(@PathVariable String gateway, @RequestHeader(name = "X-Signature", required = false) String signature,
                                                     @RequestBody String body) {
        return payments.handleNotification(gateway, body, signature);
    }

    @Operation(summary = "Simulador de la pasarela: el formulario alojado donde el estudiante 'paga'")
    @PublicEndpoint
    @PostMapping("/api/public/gateway/simulated/{reference}/pay")
    public Map<String, String> simulate(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{20,80}$") String reference,
                                        @Valid @RequestBody SimulateRequest body) {
        return Map.of("outcome", simulator.simulate(reference, SimulatedPaymentGateway.Scenario.valueOf(body.scenario())));
    }

    @PublicEndpoint
    @GetMapping("/api/public/gateway/simulated/{reference}")
    public Map<String, Object> simulatorInfo(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{20,80}$") String reference) {
        return simulator.query(reference)
                .<Map<String, Object>>map(n -> Map.of("reference", reference, "amount", n.amount(), "status", n.outcome().name()))
                .orElseThrow(() -> new ec.unisfera.shared.domain.NotFoundException("La sesión de pago"));
    }

    @ExceptionHandler(PaymentGateway.InvalidSignatureException.class)
    public ResponseEntity<Map<String, String>> invalidSignature() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "RN-24", "detail", "Firma del webhook inválida"));
    }

    // ------------------------------------------------------------ financiero
    @RequiresPermission("FINANCE_READ")
    @GetMapping("/api/admin/finance/students/{studentId}/account")
    public FinanceQueryService.Statement studentAccount(@PathVariable UUID studentId, @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return finance.statement(studentId, PageQuery.of(page, size));
    }

    @RequiresPermission("FINANCE_READ")
    @GetMapping("/api/admin/finance/students/{studentId}/payments")
    public List<PaymentService.PaymentView> studentPayments(@PathVariable UUID studentId) {
        return payments.forStudent(studentId, 50);
    }

    @RequiresPermission("PAYMENT_MANAGE")
    @PostMapping("/api/admin/finance/payments")
    public PaymentService.PaymentView registerManual(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                     @Valid @RequestBody ManualPaymentRequest r) {
        return payments.registerManual(r.studentId(), r.amount(), r.method(), r.reference(), key);
    }

    @RequiresPermission("PAYMENT_MANAGE")
    @PostMapping("/api/admin/finance/payments/{paymentId}/annul")
    public ResponseEntity<Void> annul(@PathVariable UUID paymentId, @Valid @RequestBody AnnulRequest r) {
        payments.annul(paymentId, r.reason());
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("FINANCE_READ")
    @GetMapping("/api/admin/finance/invoices/{invoiceId}/ride")
    public ResponseEntity<byte[]> ride(@PathVariable UUID invoiceId) {
        return pdf(invoices.ride(invoiceId), "factura-" + invoiceId + ".pdf");
    }

    @RequiresPermission("FINANCE_READ")
    @GetMapping("/api/admin/finance/tariffs")
    public PageResponse<FinanceQueryService.TariffView> tariffs(@RequestParam(required = false) UUID periodId,
                                                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return finance.tariffs(periodId, PageQuery.of(page, size));
    }

    @RequiresPermission("TARIFF_MANAGE")
    @PostMapping("/api/admin/finance/tariffs")
    public Map<String, UUID> publishTariff(@Valid @RequestBody TariffRequest r) {
        return Map.of("id", finance.publishTariff(r.conceptCode(), r.careerId(), r.levelNumber(), r.periodId(), r.calcMode(), r.amount(), r.reason()));
    }

    @RequiresPermission("PAYMENT_MANAGE")
    @PostMapping("/api/admin/finance/late-fees/run")
    public Map<String, Integer> runLateFees(@RequestParam(required = false) LocalDate asOf) {
        return Map.of("installments", lateFees.accrue(asOf == null ? LocalDate.now() : asOf));
    }

    @RequiresPermission("RECONCILIATION_MANAGE")
    @PostMapping(value = "/api/admin/finance/reconciliations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FinanceQueryService.ReconciliationResult reconcile(@RequestParam @Pattern(regexp = "BANK|GATEWAY") String source,
                                                              @RequestParam LocalDate statementDate, @RequestPart("file") MultipartFile file) throws IOException {
        return finance.reconcile(source, statementDate, file.getInputStream());
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(bytes);
    }
}
