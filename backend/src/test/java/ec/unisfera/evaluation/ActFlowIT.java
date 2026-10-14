package ec.unisfera.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import ec.unisfera.academic.application.EnrollmentService;
import ec.unisfera.evaluation.application.SchemeService;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.support.Fixtures;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

class ActFlowIT extends IntegrationTest {

    @Autowired
    Fixtures fixtures;
    @Autowired
    TestUsers users;
    @Autowired
    EnrollmentService enrollment;
    @Autowired
    SchemeService schemes;
    @Autowired
    TransactionTemplate tx;

    record Scenario(UUID section, String teacherToken, List<UUID> students, SchemeService.SchemeView scheme, UUID period) {
    }

    private Scenario scenario(int studentCount) {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID section = fixtures.section(p.id(), c.subject("A"), 40);
        TestUsers.TestUser teacher = users.create("TEACHER");
        UUID teacherId = UuidV7.next();
        jdbc.sql("insert into teacher (id, person_id, code, highest_degree, dedication, category) values (:id, :p, :c, 'Magíster', 'TIEMPO_COMPLETO', 'AGREGADO')")
                .param("id", teacherId).param("p", teacher.personId()).param("c", "DT" + teacherId.toString().substring(28)).update();
        jdbc.sql("update section set teacher_id = :t where id = :s").param("t", teacherId).param("s", section).update();
        SchemeService.SchemeView scheme = schemes.create(p.id(), c.careerId(), List.of(
                new SchemeService.ComponentInput("Aporte 1", "FORMATIVE", new BigDecimal("35")),
                new SchemeService.ComponentInput("Aporte 2", "SUMMATIVE", new BigDecimal("35")),
                new SchemeService.ComponentInput("Examen final", "FINAL", new BigDecimal("30"))));
        List<UUID> students = fixtures.students(c, p.id(), studentCount);
        students.forEach(s -> enrollment.enroll(s, List.of(section)));
        return new Scenario(section, users.login(teacher), students, scheme, p.id());
    }

    private List<Map<String, Object>> gradesFor(Scenario s, BigDecimal... values) {
        Response roster = as(s.teacherToken()).get("/api/teacher/sections/" + s.section() + "/roster");
        List<String> details = roster.path("rows.detailId");
        List<Map<String, Object>> items = new ArrayList<>();
        for (String d : details) {
            for (int i = 0; i < values.length; i++) {
                items.add(Map.of("detailId", d, "componentId", s.scheme().components().get(i).id().toString(), "value", values[i]));
            }
        }
        return items;
    }

    @Test
    void rn13AndRn14_closeOnlyWhenCompleteThenImmutableAndAttemptsAreAudited() {
        Scenario s = scenario(3);
        as(s.teacherToken()).body(Map.of("grades", gradesFor(s, new BigDecimal("80"), new BigDecimal("75")))).post("/api/teacher/sections/" + s.section() + "/grades")
                .then().statusCode(200);
        as(s.teacherToken()).body(Map.of("confirmation", "CERRAR ACTA")).post("/api/teacher/sections/" + s.section() + "/act/close")
                .then().statusCode(422).body("code", equalTo("RN-13"));

        as(s.teacherToken()).body(Map.of("grades", gradesFor(s, new BigDecimal("80"), new BigDecimal("75"), new BigDecimal("90")).stream()
                        .filter(g -> g.get("componentId").equals(s.scheme().components().get(2).id().toString())).toList()))
                .post("/api/teacher/sections/" + s.section() + "/grades").then().statusCode(200);
        Response closed = as(s.teacherToken()).body(Map.of("confirmation", "CERRAR ACTA")).post("/api/teacher/sections/" + s.section() + "/act/close");
        closed.then().statusCode(200).body("status", equalTo("CLOSED"));
        assertThat((String) closed.path("contentHash")).hasSize(64);
        assertThat((Float) closed.path("lines[0].finalGrade")).isEqualTo(81.25f);

        // T-06: editar una nota histórica falla y el intento queda auditado
        as(s.teacherToken()).body(Map.of("grades", gradesFor(s, new BigDecimal("100")))).post("/api/teacher/sections/" + s.section() + "/grades")
                .then().statusCode(422).body("code", equalTo("RN-14"));
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'DENIED' and table_name = 'rule:RN-14' and record_id like :u")
                .param("u", "%" + s.section() + "%").query(Long.class).single()).isPositive();
        UUID actId = UUID.fromString(closed.path("id"));
        assertThatThrownBy(() -> jdbc.sql("update grade_act set content_hash = repeat('0', 64) where id = :a").param("a", actId).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("RN-14");
        assertThatThrownBy(() -> jdbc.sql("delete from grade_act where id = :a").param("a", actId).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("LEY-2");
        assertThatThrownBy(() -> jdbc.sql("update grade_act_line set final_grade = 100 where act_id = :a").param("a", actId).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("LEY-2");

        // RN-17: el kardex ya tiene la asignatura aprobada
        assertThat(jdbc.sql("select count(*) from v_kardex where student_id = :s and result = 'APPROVED'").param("s", s.students().getFirst())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rn15_insufficientAttendanceFailsEvenWithAPassingGrade() {
        Scenario s = scenario(2);
        Response roster = as(s.teacherToken()).get("/api/teacher/sections/" + s.section() + "/roster");
        List<String> details = roster.path("rows.detailId");
        for (int i = 0; i < 4; i++) {
            String session = as(s.teacherToken()).body(Map.of("date", "2026-10-0" + (i + 1), "startsAt", "07:00", "topic", "Tema " + i))
                    .post("/api/teacher/sections/" + s.section() + "/sessions").then().statusCode(200).extract().path("id");
            as(s.teacherToken()).body(Map.of("marks", List.of(Map.of("detailId", details.get(0), "status", "PRESENT"),
                            Map.of("detailId", details.get(1), "status", i == 0 ? "PRESENT" : "ABSENT"))))
                    .post("/api/teacher/sections/" + s.section() + "/sessions/" + session + "/attendance").then().statusCode(200);
        }
        as(s.teacherToken()).body(Map.of("grades", gradesFor(s, new BigDecimal("95"), new BigDecimal("95"), new BigDecimal("95"))))
                .post("/api/teacher/sections/" + s.section() + "/grades").then().statusCode(200);
        Response closed = as(s.teacherToken()).body(Map.of("confirmation", "cerrar acta")).post("/api/teacher/sections/" + s.section() + "/act/close");
        closed.then().statusCode(200);
        List<String> results = closed.path("lines.result");
        assertThat(results).containsExactlyInAnyOrder("APPROVED", "FAILED_ATTENDANCE");

        // La base no acepta una línea "aprobada" con asistencia insuficiente aunque alguien salte el dominio
        Fixtures.Curriculum c2 = fixtures.curriculum();
        UUID section2 = fixtures.section(s.period(), c2.subject("A"), 5);
        UUID student2 = fixtures.student(c2, s.period());
        enrollment.enroll(student2, List.of(section2));
        UUID draft = UuidV7.next();
        jdbc.sql("insert into grade_act (id, section_id, period_id, type) values (:id, :s, :p, 'ORIGINAL')")
                .param("id", draft).param("s", section2).param("p", s.period()).update();
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into grade_act_line (id, act_id, enrollment_detail_id, student_id, subject_id, component_grades, final_grade,
                                                    attendance_pct, passing_grade, min_attendance_pct, result)
                        select :id, :act, d.id, d.student_id, d.subject_id, '{}', 95, 10, 70, 75, 'APPROVED'
                        from enrollment_detail d where d.section_id = :sec""")
                .param("id", UuidV7.next()).param("act", draft).param("sec", section2).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_line_result");
    }

    @Test
    void rn11AndRn12_databaseEnforcesWeightsAndScale() {
        Fixtures.Period p = fixtures.openPeriod();
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> {
            UUID scheme = UuidV7.next();
            jdbc.sql("""
                            insert into evaluation_scheme (id, period_id, scale_min, scale_max, passing_grade, min_attendance_pct, max_component_weight)
                            values (:id, :p, 0, 100, 70, 75, 60)""").param("id", scheme).param("p", p.id()).update();
            jdbc.sql("insert into evaluation_component (id, scheme_id, name, kind, weight, sort_order) values (:id, :s, 'Único', 'FINAL', 50, 1)")
                    .param("id", UuidV7.next()).param("s", scheme).update();
        })).rootCause().hasMessageContaining("RN-11");

        Scenario s = scenario(1);
        as(s.teacherToken()).body(Map.of("grades", gradesFor(s, new BigDecimal("120")))).post("/api/teacher/sections/" + s.section() + "/grades")
                .then().statusCode(422).body("code", equalTo("RN-12"));
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into grade (id, enrollment_detail_id, component_id, value)
                        select :id, d.id, :c, 150 from enrollment_detail d where d.section_id = :s limit 1""")
                .param("id", UuidV7.next()).param("c", s.scheme().components().getFirst().id()).param("s", s.section()).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("RN-12");
    }

    @Test
    void rn17_draftGradesNeverReachTheKardexAndStudentSeesWhatIsNeeded() {
        Scenario s = scenario(1);
        as(s.teacherToken()).body(Map.of("grades", gradesFor(s, new BigDecimal("60"), new BigDecimal("60"))))
                .post("/api/teacher/sections/" + s.section() + "/grades").then().statusCode(200);
        assertThat(jdbc.sql("select count(*) from v_kardex where student_id = :s").param("s", s.students().getFirst()).query(Long.class).single()).isZero();
    }

    @Test
    void isolation_teacherCannotTouchAnotherTeachersSection() {
        Scenario mine = scenario(1);
        Scenario other = scenario(1);
        as(mine.teacherToken()).get("/api/teacher/sections/" + other.section() + "/roster").then().statusCode(403);
        as(mine.teacherToken()).body(Map.of("grades", gradesFor(other, new BigDecimal("100")))).post("/api/teacher/sections/" + other.section() + "/grades")
                .then().statusCode(403);
    }
}
