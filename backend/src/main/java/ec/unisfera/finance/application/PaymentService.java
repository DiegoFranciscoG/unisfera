package ec.unisfera.finance.application;

import ec.unisfera.finance.domain.PaymentAllocator;
import ec.unisfera.finance.gateway.PaymentGateway;
import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pagos idempotentes (RN-24): la clave de idempotencia y el identificador del evento de la
 * pasarela son únicos en la base y el pago se bloquea con FOR UPDATE antes de cambiar de estado.
 * Si la pasarela notifica cinco veces, el resultado es el mismo. Un pago confirmado se aplica en
 * el orden de prelación (RN-25), desbloquea lo bloqueado por deuda en ese instante (RN-27) y nunca
 * se elimina: se anula con nota de crédito (RN-28).
 */
@Service
public class PaymentService {

    public record PaymentView(UUID id, BigDecimal amount, String currency, String method, String status, String reference, Instant createdAt,
                              Instant confirmedAt, UUID invoiceId, String checkoutUrl) {
    }

    public record NotificationResult(UUID paymentId, String status, boolean duplicate) {
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final Map<String, PaymentGateway> gateways;
    private final InvoiceService invoices;
    private final NotificationService notifications;
    private final Clock clock;

    public PaymentService(JdbcClient jdbc, ParameterService parameters, List<PaymentGateway> gateways,
                          InvoiceService invoices, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.gateways = gateways.stream().collect(Collectors.toUnmodifiableMap(PaymentGateway::name, Function.identity()));
        this.invoices = invoices;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Intención de pago en línea. Repetir la misma clave devuelve el mismo pago (creación idempotente). */
    @Transactional
    public PaymentView createIntent(UUID studentId, BigDecimal requestedAmount, String idempotencyKey, String gatewayName) {
        String key = normalizeKey(idempotencyKey);
        PaymentView existing = findByKey(key);
        if (existing != null) {
            return existing;
        }
        BigDecimal outstanding = outstanding(studentId);
        BigDecimal amount = requestedAmount == null ? outstanding : requestedAmount;
        if (amount.signum() <= 0 || amount.compareTo(outstanding) > 0) {
            throw new BusinessRuleException("RN-25", "El monto debe ser mayor a cero y no superar su saldo pendiente (" + outstanding + ")");
        }
        PaymentGateway gateway = gateway(gatewayName);
        UUID id = UuidV7.next(clock);
        String reference = ec.unisfera.identity.application.SecureTokens.newToken();
        jdbc.sql("""
                        insert into payment (id, student_id, amount, currency, method, gateway, gateway_reference, idempotency_key, payer_ip)
                        values (:id, :s, :a, :c, 'GATEWAY', :g, :ref, :key, :ip)""")
                .param("id", id).param("s", studentId).param("a", amount).param("c", parameters.text(ParameterKeys.CURRENCY))
                .param("g", gateway.name()).param("ref", reference).param("key", key).param("ip", RequestContext.ip()).update();
        PaymentGateway.Checkout checkout = gateway.createCheckout(id, reference, amount, parameters.text(ParameterKeys.CURRENCY), "Pago de aranceles");
        jdbc.sql("""
                        insert into gateway_transaction (id, payment_id, gateway, operation, request_payload, response_payload, outcome)
                        values (:id, :p, :g, 'CREATE', :req, :resp, 'PENDING')""")
                .param("id", UuidV7.next(clock)).param("p", id).param("g", gateway.name())
                .param("req", "{\"amount\":\"" + amount + "\"}").param("resp", "{\"redirectUrl\":\"" + checkout.redirectUrl() + "\"}").update();
        return new PaymentView(id, amount, parameters.text(ParameterKeys.CURRENCY), "GATEWAY", "PENDING", reference, clock.instant(), null, null,
                checkout.redirectUrl());
    }

    /** Webhook de la pasarela. Firma inválida → 401 y queda registrado; duplicados → mismo resultado. */
    @Transactional(noRollbackFor = PaymentGateway.InvalidSignatureException.class)
    public NotificationResult handleNotification(String gatewayName, String body, String signature) {
        PaymentGateway gateway = gateway(gatewayName);
        PaymentGateway.Notification n;
        try {
            n = gateway.verifyAndParse(body, signature);
        } catch (PaymentGateway.InvalidSignatureException e) {
            jdbc.sql("insert into gateway_transaction (id, gateway, operation, request_payload, signature_valid, outcome) values (:id, :g, 'NOTIFICATION', :b, false, 'INVALID_SIGNATURE')")
                    .param("id", UuidV7.next(clock)).param("g", gateway.name()).param("b", body.length() > 2000 ? body.substring(0, 2000) : body).update();
            throw e;
        }
        int attempts = jdbc.sql("""
                        insert into gateway_transaction (id, gateway, operation, event_id, request_payload, signature_valid, outcome)
                        values (:id, :g, 'NOTIFICATION', :e, :b, true, :o)
                        on conflict (gateway, event_id) where operation = 'NOTIFICATION'
                        do update set attempts = gateway_transaction.attempts + 1, last_seen_at = now()
                        returning attempts""")
                .param("id", UuidV7.next(clock)).param("g", gateway.name()).param("e", n.eventId()).param("b", n.rawBody())
                .param("o", n.outcome().name()).query(Integer.class).single();
        return apply(gateway.name(), n, attempts > 1);
    }

    private NotificationResult apply(String gatewayName, PaymentGateway.Notification n, boolean duplicate) {
        var payment = jdbc.sql("select id, status, amount, student_id from payment where gateway = :g and gateway_reference = :r for update")
                .param("g", gatewayName).param("r", n.reference())
                .query((rs, x) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getObject(4, UUID.class)})
                .optional().orElseThrow(() -> new NotFoundException("El pago de la referencia"));
        UUID paymentId = (UUID) payment[0];
        String status = (String) payment[1];
        jdbc.sql("update gateway_transaction set payment_id = :p where gateway = :g and event_id = :e and payment_id is null")
                .param("p", paymentId).param("g", gatewayName).param("e", n.eventId()).update();
        if (!"PENDING".equals(status)) {
            return new NotificationResult(paymentId, status, true);
        }
        if (n.amount().compareTo((BigDecimal) payment[2]) != 0) {
            throw new BusinessRuleException("RN-24", "El monto notificado no coincide con el pago");
        }
        switch (n.outcome()) {
            case APPROVED -> confirm(paymentId, (UUID) payment[3], (BigDecimal) payment[2]);
            case REJECTED -> jdbc.sql("update payment set status = 'REJECTED', rejected_at = now(), rejection_reason = 'Rechazado por la pasarela' where id = :id")
                    .param("id", paymentId).update();
            case PENDING -> { }
        }
        String finalStatus = jdbc.sql("select status from payment where id = :id").param("id", paymentId).query(String.class).single();
        return new NotificationResult(paymentId, finalStatus, duplicate);
    }

    /** Pago registrado en ventanilla (efectivo, transferencia) por el rol financiero, también idempotente. */
    @Transactional
    public PaymentView registerManual(UUID studentId, BigDecimal amount, String method, String reference, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        PaymentView existing = findByKey(key);
        if (existing != null) {
            return existing;
        }
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into payment (id, student_id, amount, currency, method, gateway, gateway_reference, idempotency_key, registered_by)
                        values (:id, :s, :a, :c, :m, 'MANUAL', :ref, :key, :by)""")
                .param("id", id).param("s", studentId).param("a", amount).param("c", parameters.text(ParameterKeys.CURRENCY)).param("m", method)
                .param("ref", reference).param("key", key).param("by", RequestContext.actorId().orElse(null)).update();
        confirm(id, studentId, amount);
        return findByKey(key);
    }

    private void confirm(UUID paymentId, UUID studentId, BigDecimal amount) {
        List<PaymentAllocator.OpenInstallment> open = jdbc.sql("""
                        select i.id, i.number, i.due_date, i.amount_due, i.late_fee_accrued,
                               coalesce((select sum(a.amount) from payment_allocation a join payment p on p.id = a.payment_id
                                         where a.installment_id = i.id and a.kind = 'LATE_FEE' and p.status = 'CONFIRMED'), 0) fee_paid,
                               coalesce((select sum(a.amount) from payment_allocation a join payment p on p.id = a.payment_id
                                         where a.installment_id = i.id and a.kind = 'PRINCIPAL' and p.status = 'CONFIRMED'), 0) principal_paid
                        from installment i where i.student_id = :s and i.status = 'PENDING' for update of i""")
                .param("s", studentId)
                .query((rs, n) -> new PaymentAllocator.OpenInstallment(rs.getObject(1, UUID.class), rs.getInt(2), rs.getDate(3).toLocalDate(),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)))
                .list();
        List<PaymentAllocator.Allocation> allocations = PaymentAllocator.allocate(amount, open, parameters.list(ParameterKeys.ALLOCATION_ORDER));
        jdbc.sql("update payment set status = 'CONFIRMED', confirmed_at = now() where id = :id").param("id", paymentId).update();
        for (PaymentAllocator.Allocation a : allocations) {
            jdbc.sql("insert into payment_allocation (id, payment_id, installment_id, kind, amount) values (:id, :p, :i, :k, :a)")
                    .param("id", UuidV7.next(clock)).param("p", paymentId).param("i", a.installmentId()).param("k", a.kind()).param("a", a.amount()).update();
            jdbc.sql("""
                            update installment set amount_paid = amount_paid + :a,
                                   status = case when amount_paid + :a = amount_due + late_fee_accrued then 'PAID' else status end
                            where id = :i""")
                    .param("a", a.amount()).param("i", a.installmentId()).update();
        }
        UUID periodId = jdbc.sql("select period_id from installment where id = :i").param("i", allocations.getFirst().installmentId()).query(UUID.class).single();
        jdbc.sql("""
                        insert into account_entry (id, student_id, period_id, entry_type, direction, amount, payment_id, description)
                        values (:id, :s, :p, 'PAYMENT', -1, :a, :pay, 'Pago recibido')""")
                .param("id", UuidV7.next(clock)).param("s", studentId).param("p", periodId).param("a", amount).param("pay", paymentId).update();
        invoices.issueForPayment(paymentId);
        jdbc.sql("select u.id from student s join user_account u on u.person_id = s.person_id where s.id = :s").param("s", studentId)
                .query(UUID.class).optional()
                .ifPresent(userId -> notifications.notifyUser(userId, "PAYMENT", "Pago confirmado",
                        "Registramos su pago por " + parameters.text(ParameterKeys.CURRENCY) + " " + amount + ". Su factura ya está disponible.",
                        "/estudiante/cuenta"));
    }

    /** RN-28: un pago confirmado no se borra; se anula con nota de crédito, motivo y reverso contable. */
    @Transactional
    public void annul(UUID paymentId, String reason) {
        var payment = jdbc.sql("select student_id, amount, status from payment where id = :id for update").param("id", paymentId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3)})
                .optional().orElseThrow(() -> new NotFoundException("El pago"));
        if (!"CONFIRMED".equals(payment[2])) {
            throw new BusinessRuleException("RN-28", "Solo se anula un pago confirmado");
        }
        invoices.creditNoteForPayment(paymentId, reason);
        jdbc.sql("update payment set status = 'ANNULLED', annulled_at = now(), annulled_by = :by, annulment_reason = :r where id = :id")
                .param("by", RequestContext.actorId().orElseThrow()).param("r", reason).param("id", paymentId).update();
        jdbc.sql("""
                        select installment_id, amount from payment_allocation where payment_id = :p""").param("p", paymentId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2)}).list()
                .forEach(a -> jdbc.sql("update installment set amount_paid = amount_paid - :a, status = case when status = 'PAID' then 'PENDING' else status end where id = :i")
                        .param("a", a[1]).param("i", a[0]).update());
        jdbc.sql("""
                        insert into account_entry (id, student_id, entry_type, direction, amount, payment_id, reverses_entry_id, description)
                        select :id, student_id, 'REVERSAL', 1, amount, payment_id, id, 'Anulación de pago: ' || :r
                        from account_entry where payment_id = :p and entry_type = 'PAYMENT'""")
                .param("id", UuidV7.next(clock)).param("r", reason.length() > 150 ? reason.substring(0, 150) : reason).param("p", paymentId).update();
    }

    /** Pagos que la pasarela no notificó (escenario DELAY): se resuelven por consulta activa. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void syncPendingScheduled() {
        try {
            syncPending();
        } catch (RuntimeException e) {
            log.warn("Sincronización de pagos pendientes falló: {}", e.getMessage());
        }
    }

    @Transactional
    public int syncPending() {
        List<Object[]> pending = jdbc.sql("""
                        select gateway, gateway_reference from payment
                        where status = 'PENDING' and method = 'GATEWAY' and created_at < now() - interval '20 seconds' limit 100""")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2)}).list();
        int resolved = 0;
        for (Object[] p : pending) {
            PaymentGateway gateway = gateways.get((String) p[0]);
            if (gateway == null) {
                continue;
            }
            var status = gateway.query((String) p[1]);
            if (status.isPresent() && status.get().outcome() != PaymentGateway.Outcome.PENDING) {
                apply(gateway.name(), status.get(), false);
                resolved++;
            }
        }
        return resolved;
    }

    @Transactional(readOnly = true)
    public List<PaymentView> forStudent(UUID studentId, int limit) {
        return jdbc.sql("""
                        select p.id, p.amount, p.currency, p.method, p.status, p.gateway_reference, p.created_at, p.confirmed_at, i.id invoice_id
                        from payment p left join invoice i on i.payment_id = p.id where p.student_id = :s order by p.created_at desc limit :l""")
                .param("s", studentId).param("l", limit)
                .query((rs, n) -> new PaymentView(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("method"), rs.getString("status"), rs.getString("gateway_reference"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(), rs.getObject("invoice_id", UUID.class), null))
                .list();
    }

    public BigDecimal outstanding(UUID studentId) {
        return jdbc.sql("select coalesce(sum(amount_due + late_fee_accrued - amount_paid), 0) from installment where student_id = :s and status = 'PENDING'")
                .param("s", studentId).query(BigDecimal.class).single();
    }

    public UUID studentOfReference(String reference) {
        return jdbc.sql("select student_id from payment where gateway_reference = :r").param("r", reference).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("El pago"));
    }

    private PaymentView findByKey(String key) {
        return jdbc.sql("""
                        select p.id, p.amount, p.currency, p.method, p.status, p.gateway_reference, p.created_at, p.confirmed_at, i.id invoice_id
                        from payment p left join invoice i on i.payment_id = p.id where p.idempotency_key = :k""")
                .param("k", key)
                .query((rs, n) -> new PaymentView(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("method"), rs.getString("status"), rs.getString("gateway_reference"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(), rs.getObject("invoice_id", UUID.class), null))
                .optional().orElse(null);
    }

    private PaymentGateway gateway(String name) {
        PaymentGateway g = gateways.get(name == null ? ec.unisfera.finance.gateway.SimulatedPaymentGateway.NAME : name);
        if (g == null) {
            throw new NotFoundException("La pasarela " + name);
        }
        return g;
    }

    private static String normalizeKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9-]{8,80}$")) {
            throw new BusinessRuleException("RN-24", "Falta una clave de idempotencia válida (cabecera Idempotency-Key)");
        }
        return key;
    }
}
