package ec.unisfera.quality;

import static org.assertj.core.api.Assertions.assertThat;

import ec.unisfera.reporting.application.ReportService;
import ec.unisfera.support.Fixtures;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T-08 · RN-23: salud y psicología solo para BIENESTAR, cifradas, con lectura auditada y fuera de los reportes. */
class T08SensitiveDataIT extends IntegrationTest {

    @Autowired
    Fixtures fixtures;
    @Autowired
    TestUsers users;

    @Test
    void rn23_onlyWelfareRoleReadsAttentionsAndEveryReadIsAudited() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID student = fixtures.student(c, p.id());
        String path = "/api/admin/welfare/students/" + student + "/attentions";
        String welfare = users.createAndLogin("WELFARE");
        String secret = "Paciente refiere ansiedad por evaluaciones";

        // Sin consentimiento explícito no se registra (LOPDP art. 26)
        as(welfare).body(Map.of("category", "PSYCHOLOGY", "attendedAt", "2026-10-01T15:00:00Z", "summary", secret, "followUpRequired", false))
                .post(path).then().statusCode(422);
        UUID person = jdbc.sql("select person_id from student where id = :s").param("s", student).query(UUID.class).single();
        jdbc.sql("insert into consent (id, person_id, purpose, text_version) values (:id, :p, 'WELFARE_SENSITIVE', 'TEST')")
                .param("id", UUID.randomUUID()).param("p", person).update();
        as(welfare).body(Map.of("category", "PSYCHOLOGY", "attendedAt", "2026-10-01T15:00:00Z", "summary", secret, "followUpRequired", true))
                .post(path).then().statusCode(200);

        for (String role : List.of("SUPERADMIN", "ACADEMIC_ADMIN", "REGISTRAR", "FINANCE", "TEACHER", "STUDENT", "AUDITOR")) {
            assertThat(as(users.createAndLogin(role)).get(path).statusCode()).as(role).isEqualTo(403);
        }
        List<String> summaries = as(welfare).get(path).then().statusCode(200).extract().path("summary");
        assertThat(summaries).containsExactly(secret);

        byte[] stored = jdbc.sql("select summary_enc from welfare_attention where student_id = :s").param("s", student).query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("ansiedad");
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'READ_SENSITIVE' and record_id = :s").param("s", student.toString())
                .query(Long.class).single()).isEqualTo(1);
        String auditPayload = jdbc.sql("select coalesce(string_agg(new_values::text, ''), '') from audit_log where table_name = 'welfare_attention'")
                .query(String.class).single();
        assertThat(auditPayload).doesNotContain("ansiedad");
    }

    @Test
    void rn23_noReportReadsSensitiveDataAndSmallCellsAreSuppressed() {
        assertThat(ReportService.CATALOG).noneMatch(d -> d.code().contains("attention"));
        var table = new ec.unisfera.reporting.domain.ReportTable("x", "Prueba", "2026-10-13", List.of("grupo", "personas"),
                List.of(List.<Object>of("A", 3L), List.<Object>of("B", 12L)), List.of()).suppressSmallCells(List.of(1), 5);
        assertThat(table.rows().get(0).get(1)).isEqualTo("< 5");
        assertThat(table.rows().get(1).get(1)).isEqualTo(12L);
        assertThat(table.notes()).anyMatch(n -> n.contains("LOPDP"));
    }
}
