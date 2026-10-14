package ec.unisfera.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ec.unisfera.seed.DemoDataGenerator;
import ec.unisfera.seed.DemoScenario;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Reglas de bienestar y finanzas que se verifican de punta a punta: API y base de datos. */
class ScholarshipAndPaymentRulesIT extends IntegrationTest {

    @Autowired
    DemoDataGenerator generator;

    @Autowired
    TestUsers users;

    private Map<String, String> demo;

    @BeforeEach
    void scenario() {
        demo = DemoScenario.ensure(generator);
    }

    @Test
    void rn18AndRn19_applicationIsSubmittedOnlyCompleteAndThenFrozen() {
        String welfare = users.createAndLogin("WELFARE");
        String student = DemoScenario.login(demo.get("STUDENT"));
        UUID period = jdbc.sql("select id from academic_period where status = 'ENROLLMENT' order by classes_start desc limit 1").query(UUID.class).single();
        String type = welfare(welfare).body(Map.of("code", "R19" + ThreadLocalRandom.current().nextInt(10_000, 99_999), "name", "Beca de prueba RN-19",
                "description", "Beca para verificar el congelamiento de postulaciones", "mode", "PERCENT", "value", 15, "renewable", false,
                "coveredConcepts", List.of("ARANCEL"))).post("/api/admin/welfare/types").then().statusCode(200).extract().path("id");
        String call = welfare(welfare).body(Map.of("typeId", type, "periodId", period.toString(), "name", "Convocatoria RN-19", "quota", 3,
                "opensAt", Instant.now().minus(1, ChronoUnit.HOURS).toString(), "closesAt", Instant.now().plus(3, ChronoUnit.DAYS).toString(),
                "resultsBy", LocalDate.now().plusDays(10).toString(),
                "documents", List.of(Map.of("code", "ACTA_GRADO", "name", "Acta de grado de bachiller", "mandatory", true)),
                "criteria", List.of(Map.of("name", "Mérito", "maxScore", 10, "weight", 100)))).post("/api/admin/welfare/calls")
                .then().statusCode(200).extract().path("id");
        String application = as(student).body(Map.of("callId", call, "statement", "Postulación de prueba")).post("/api/me/scholarships/applications")
                .then().statusCode(200).extract().path("id");

        Response incomplete = as(student).post("/api/me/scholarships/applications/" + application + "/submit");
        assertThat(incomplete.statusCode()).isEqualTo(422);
        assertThat(incomplete.<String>path("code")).isEqualTo("RN-18");

        RestAssured.given().header("Authorization", "Bearer " + student)
                .multiPart("file", "acta.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII), "application/pdf")
                .post("/api/me/scholarships/applications/" + application + "/documents/ACTA_GRADO").then().statusCode(204);
        as(student).post("/api/me/scholarships/applications/" + application + "/submit").then().statusCode(204);

        Response edit = as(student).body(Map.of("statement", "Intento de editar después de enviar")).put("/api/me/scholarships/applications/" + application);
        assertThat(edit.statusCode()).isBetween(400, 499);
        assertThat(edit.<String>path("code")).isEqualTo("RN-19");
        assertThatThrownBy(() -> jdbc.sql("update scholarship_application set statement = 'manipulada' where id = :id")
                .param("id", UUID.fromString(application)).update()).hasMessageContaining("RN-19");
    }

    @Test
    void rn21_scholarshipDiscountIsNeverAppliedToChargesBeforeItsStartPeriod() {
        var row = jdbc.sql("""
                        select a.id award, c.id charge_id, c.student_id, c.period_id, c.net_amount
                        from scholarship_award a
                        join academic_period sp on sp.id = a.start_period_id
                        join charge c on c.student_id = a.student_id
                        join academic_period cp on cp.id = c.period_id
                        where cp.classes_start < sp.classes_start and a.status = 'ACTIVE' and a.retroactive_authorization_id is null
                        limit 1""").query().listOfRows();
        assumeTrue(!row.isEmpty(), "El escenario demo no tiene becas con cargos anteriores a su inicio");
        Map<String, Object> r = row.getFirst();
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into discount_applied (id, charge_id, student_id, period_id, origin_type, origin_id, amount, precedence)
                        values (:id, :c, :s, :p, 'SCHOLARSHIP', :a, 1.00, 1)""")
                .param("id", UuidV7.next()).param("c", r.get("charge_id")).param("s", r.get("student_id")).param("p", r.get("period_id"))
                .param("a", r.get("award")).update())
                .hasMessageContaining("RN-21");
    }

    @Test
    void rn28_confirmedPaymentIsNeverDeletedOrRewoundOnlyAnnulledWithCreditNote() {
        String finance = users.createAndLogin("FINANCE");
        UUID student = jdbc.sql("select s.id from student s join person p on p.id = s.person_id where p.id_number = :n")
                .param("n", demo.get("STUDENT")).query(UUID.class).single();
        Response payment = as(finance).header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("studentId", student.toString(), "amount", 5.00, "method", "TRANSFER", "reference", "TRF-" + UUID.randomUUID()))
                .post("/api/admin/finance/payments");
        assumeTrue(payment.statusCode() == 200, "El estudiante demo no tiene saldo pendiente");
        UUID paymentId = UUID.fromString(payment.path("id"));

        assertThatThrownBy(() -> jdbc.sql("delete from payment where id = :id").param("id", paymentId).update()).hasMessageContaining("LEY-2");
        assertThatThrownBy(() -> jdbc.sql("update payment set status = 'PENDING' where id = :id").param("id", paymentId).update())
                .hasMessageContaining("RN-28");

        as(finance).body(Map.of("reason", "Transferencia devuelta por el banco")).post("/api/admin/finance/payments/" + paymentId + "/annul")
                .then().statusCode(204);
        assertThat(jdbc.sql("select status from payment where id = :id").param("id", paymentId).query(String.class).single()).isEqualTo("ANNULLED");
        assertThat(jdbc.sql("select count(*) from credit_note where payment_id = :id").param("id", paymentId).query(Long.class).single()).isEqualTo(1);
        long balanced = jdbc.sql("""
                        select count(*) from v_account_statement
                        where student_id = :s and charges + late_fees - discounts - payments - credit_notes + reversals = balance""")
                .param("s", student).query(Long.class).single();
        assertThat(balanced).isEqualTo(1);
    }

    private io.restassured.specification.RequestSpecification welfare(String token) {
        return as(token);
    }
}
