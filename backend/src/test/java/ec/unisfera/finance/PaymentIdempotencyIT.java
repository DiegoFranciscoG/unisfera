package ec.unisfera.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.academic.application.EnrollmentService;
import ec.unisfera.finance.application.PaymentService;
import ec.unisfera.finance.gateway.PaymentGateway;
import ec.unisfera.finance.gateway.SimulatedPaymentGateway;
import ec.unisfera.support.Fixtures;
import ec.unisfera.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T-03 · RN-24: la misma notificación llega cinco veces (dos simultáneas) y se registra un solo pago. */
class PaymentIdempotencyIT extends IntegrationTest {

    @Autowired
    Fixtures fixtures;
    @Autowired
    EnrollmentService enrollment;
    @Autowired
    PaymentService payments;
    @Autowired
    SimulatedPaymentGateway gateway;

    @Test
    void rn24_fiveNotificationsTwoSimultaneousProduceOnePayment() throws Exception {
        UUID student = enrolledStudent();
        BigDecimal before = payments.outstanding(student);
        PaymentService.PaymentView intent = payments.createIntent(student, new BigDecimal("100.00"), "idem-" + UUID.randomUUID(), null);
        String body = gateway.payload(intent.reference(), PaymentGateway.Outcome.APPROVED, intent.amount());
        String signature = gateway.sign(body);

        payments.handleNotification(SimulatedPaymentGateway.NAME, body, signature);
        payments.handleNotification(SimulatedPaymentGateway.NAME, body, signature);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    go.await();
                    return payments.handleNotification(SimulatedPaymentGateway.NAME, body, signature);
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        PaymentService.NotificationResult last = payments.handleNotification(SimulatedPaymentGateway.NAME, body, signature);

        assertThat(last.status()).isEqualTo("CONFIRMED");
        assertThat(last.duplicate()).isTrue();
        assertThat(count("select count(*) from payment where id = :p and status = 'CONFIRMED'", intent.id())).isEqualTo(1);
        assertThat(count("select count(*) from account_entry where payment_id = :p and entry_type = 'PAYMENT'", intent.id())).isEqualTo(1);
        assertThat(count("select count(*) from invoice where payment_id = :p", intent.id())).isEqualTo(1);
        assertThat(jdbc.sql("select attempts from gateway_transaction where event_id = :e").param("e", "evt-" + intent.reference() + "-approved")
                .query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select sum(amount) from payment_allocation where payment_id = :p").param("p", intent.id())
                .query(BigDecimal.class).single()).isEqualByComparingTo("100.00");
        assertThat(payments.outstanding(student)).isEqualByComparingTo(before.subtract(new BigDecimal("100.00")));
    }

    @Test
    void rn24_sameIdempotencyKeyReturnsTheSamePayment() {
        UUID student = enrolledStudent();
        String key = "idem-" + UUID.randomUUID();
        PaymentService.PaymentView first = payments.createIntent(student, new BigDecimal("50.00"), key, null);
        PaymentService.PaymentView second = payments.createIntent(student, new BigDecimal("50.00"), key, null);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("select count(*) from payment where idempotency_key = :p", key)).isEqualTo(1);
    }

    @Test
    void rn24_forgedSignatureIsRejectedAndLogged() {
        UUID student = enrolledStudent();
        PaymentService.PaymentView intent = payments.createIntent(student, new BigDecimal("10.00"), "idem-" + UUID.randomUUID(), null);
        String body = gateway.payload(intent.reference(), PaymentGateway.Outcome.APPROVED, intent.amount());
        assertThatThrownBy(() -> payments.handleNotification(SimulatedPaymentGateway.NAME, body, "firma-falsa"))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        assertThat(count("select count(*) from payment where id = :p and status = 'PENDING'", intent.id())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from gateway_transaction where outcome = 'INVALID_SIGNATURE' and request_payload like :r")
                .param("r", "%" + intent.reference() + "%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rn25_paymentCannotExceedOutstanding() {
        UUID student = enrolledStudent();
        BigDecimal tooMuch = payments.outstanding(student).add(BigDecimal.ONE);
        assertThatThrownBy(() -> payments.createIntent(student, tooMuch, "idem-" + UUID.randomUUID(), null))
                .hasMessageContaining("saldo pendiente");
    }

    private UUID enrolledStudent() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID section = fixtures.section(p.id(), c.subject("A"), 40);
        UUID student = fixtures.student(c, p.id());
        enrollment.enroll(student, List.of(section));
        return student;
    }

    private long count(String sql, Object param) {
        return jdbc.sql(sql).param("p", param).query(Long.class).single();
    }
}
