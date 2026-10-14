package ec.unisfera.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.lessThan;

import ec.unisfera.reporting.application.ReportJobService;
import ec.unisfera.seed.DemoDataGenerator;
import ec.unisfera.seed.DemoScenario;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Recorrido de los tres portales sobre el escenario demo: cada pantalla obtiene sus datos
 * reales, los flujos administrativos se completan de punta a punta y ninguna respuesta es un 5xx.
 * Si una regla de negocio rechaza la acción, la respuesta debe ser un problema 4xx con su código.
 */
class PortalTourIT extends IntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guayaquil");

    @Autowired
    DemoDataGenerator generator;

    @Autowired
    TestUsers users;

    @Autowired
    ReportJobService reportJobs;

    private Map<String, String> demo;

    @BeforeEach
    void scenario() {
        demo = DemoScenario.ensure(generator);
    }

    @Test
    void studentPortalShowsRealDataAndHandlesRequests() {
        String t = DemoScenario.login(demo.get("STUDENT"));
        ok(as(t).get("/api/auth/me"));
        Response curriculum = ok(as(t).get("/api/me/curriculum"));
        assertThat(curriculum.jsonPath().getList("subjects")).isNotEmpty();
        Response offer = ok(as(t).get("/api/me/enrollment/offer"));
        ok(as(t).get("/api/me/enrollment"));
        ok(as(t).get("/api/me/schedule"));
        ok(as(t).get("/api/me/grades"));
        assertThat(ok(as(t).get("/api/me/kardex")).jsonPath().getList("entries")).isNotEmpty();
        assertThat(ok(as(t).get("/api/me/account")).jsonPath().getBoolean("summary.balances")).isTrue();
        ok(as(t).get("/api/me/payments"));
        ok(as(t).get("/api/me/withdrawals"));
        ok(as(t).get("/api/catalog/periods/current"));
        ok(as(t).get("/api/catalog/periods"));
        ok(as(t).get("/api/catalog/careers"));
        ok(as(t).get("/api/scholarships/calls/open"));
        ok(as(t).get("/api/me/scholarships/applications"));
        ok(as(t).get("/api/me/scholarships/awards"));

        List<String> sections = offer.jsonPath().getList("subjects.sections.flatten().sectionId");
        if (!sections.isEmpty()) {
            noServerError(as(t).body(Map.of("sectionIds", List.of(sections.getFirst()))).post("/api/me/enrollment/preview"));
        }

        Response notifications = ok(as(t).get("/api/me/notifications"));
        ok(as(t).get("/api/me/notifications/unread-count"));
        List<String> ids = notifications.jsonPath().getList("items.id");
        if (!ids.isEmpty()) {
            ok(as(t).post("/api/me/notifications/" + ids.getFirst() + "/read"));
        }

        Response profile = ok(as(t).get("/api/me/profile"));
        List<String> editable = profile.jsonPath().getList("editableFields");
        if (editable.contains("phoneMobile")) {
            ok(as(t).body(Map.of("phoneMobile", "0991234567")).patch("/api/me/profile"));
        }
        String contact = ok(as(t).body(Map.of("fullName", "Contacto Ficticio", "relationship", "MADRE", "phone", "0998765432"))
                .post("/api/me/profile/emergency-contacts")).path("id");
        ok(as(t).get("/api/me/profile/emergency-contacts"));
        ok(as(t).post("/api/me/profile/emergency-contacts/" + contact + "/deactivate"));

        String request = ok(as(t).body(Map.of("type", "CERTIFICATE", "subject", "Certificado de matrícula",
                "detail", "Necesito un certificado de matrícula para trámites personales.")).post("/api/me/requests")).path("id");
        String cancelled = ok(as(t).body(Map.of("type", "OTHER", "subject", "Consulta general",
                "detail", "Consulta sobre el calendario académico del período.")).post("/api/me/requests")).path("id");
        ok(as(t).post("/api/me/requests/" + cancelled + "/cancel"));
        assertThat(ok(as(t).get("/api/me/requests")).jsonPath().getList("id")).contains(request, cancelled);

        String admin = users.createAndLogin("REGISTRAR");
        ok(as(admin).get("/api/admin/requests"));
        ok(as(admin).body(Map.of("status", "IN_REVIEW", "resolution", "En revisión por secretaría")).post("/api/admin/requests/" + request + "/status"));
        ok(as(admin).body(Map.of("status", "APPROVED", "resolution", "Certificado emitido")).post("/api/admin/requests/" + request + "/status"));

        Response intent = as(t).header("Idempotency-Key", UUID.randomUUID().toString()).body(Map.of()).post("/api/me/payments/intents");
        noServerError(intent);
        if (intent.statusCode() == 200 || intent.statusCode() == 201) {
            ok(anonymous().get("/api/public/gateway/simulated/" + intent.path("reference")));
        }
        ok(anonymous().body(Map.of("username", demo.get("STUDENT"))).post("/api/auth/password/forgot"));
    }

    @Test
    void teacherWorksOnTheirSections() {
        String t = DemoScenario.login(demo.get("TEACHER"));
        List<Map<String, Object>> sections = ok(as(t).get("/api/teacher/sections")).jsonPath().getList("$");
        assertThat(sections).isNotEmpty();
        Map<String, Object> open = sections.stream().filter(s -> !"CLOSED".equals(s.get("actStatus"))).findFirst().orElse(sections.getFirst());
        String sectionId = (String) open.get("id");
        Response roster = ok(as(t).get("/api/teacher/sections/" + sectionId + "/roster"));
        List<Map<String, Object>> rows = roster.jsonPath().getList("rows");
        List<String> components = roster.jsonPath().getList("scheme.components.id");
        if (!rows.isEmpty() && !components.isEmpty()) {
            Map<String, Object> row = rows.getFirst();
            List<Map<String, Object>> grades = (List<Map<String, Object>>) row.get("grades");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("detailId", row.get("detailId"));
            item.put("componentId", components.getFirst());
            item.put("value", 84);
            item.put("version", grades.stream().filter(g -> components.getFirst().equals(g.get("componentId"))).map(g -> g.get("version")).findFirst().orElse(null));
            noServerError(as(t).body(Map.of("grades", List.of(item))).post("/api/teacher/sections/" + sectionId + "/grades"));

            Response session = as(t).body(Map.of("date", LocalDate.now(ZONE).toString(), "startsAt", "07:00", "topic", "Repaso de la unidad"))
                    .post("/api/teacher/sections/" + sectionId + "/sessions");
            noServerError(session);
            if (session.statusCode() == 200) {
                List<Map<String, String>> marks = rows.stream().map(r -> Map.of("detailId", (String) r.get("detailId"), "status", "PRESENT")).toList();
                ok(as(t).body(Map.of("marks", marks)).post("/api/teacher/sections/" + sectionId + "/sessions/" + session.path("id") + "/attendance"));
            }
        }
        noServerError(as(t).get("/api/teacher/sections/" + sectionId + "/act/preview"));
        ok(as(t).get("/api/teacher/acts"));
        RestAssured.given().header("Authorization", "Bearer " + t).get("/api/teacher/sections/" + UUID.randomUUID() + "/roster")
                .then().statusCode(lessThan(500));
    }

    @Test
    void catalogIsBuiltThroughTheApi() {
        String admin = users.createAndLogin("ACADEMIC_ADMIN");
        UUID faculty = jdbc.sql("select id from faculty limit 1").query(UUID.class).single();
        UUID campus = jdbc.sql("select id from campus limit 1").query(UUID.class).single();
        String code = "R" + randomCode(5);
        String career = ok(as(admin).body(body("facultyId", faculty, "campusId", campus, "code", code, "senescytCode", "SEN-" + code,
                "name", "Carrera de Recorrido " + code, "degreeTitle", "Licenciado/a en Recorridos", "modality", "PRESENCIAL",
                "educationLevel", "TERCER_NIVEL_GRADO", "durationPeriods", 8)).post("/api/admin/catalog/careers")).path("id");
        String curriculum = ok(as(admin).body(body("versionCode", "2026", "name", "Malla 2026", "validFrom", "2026-01-01",
                "approvalResolution", "RPC-SO-01-2026", "levels", 2)).post("/api/admin/catalog/careers/" + career + "/curricula")).path("id");
        String first = ok(as(admin).body(subject(1, code + "-01", "Fundamentos")).post("/api/admin/catalog/curricula/" + curriculum + "/subjects")).path("id");
        String second = ok(as(admin).body(subject(2, code + "-02", "Aplicaciones")).post("/api/admin/catalog/curricula/" + curriculum + "/subjects")).path("id");
        String twin = ok(as(admin).body(subject(2, code + "-03", "Laboratorio")).post("/api/admin/catalog/curricula/" + curriculum + "/subjects")).path("id");
        ok(as(admin).post("/api/admin/catalog/subjects/" + second + "/prerequisites/" + first));
        ok(as(admin).post("/api/admin/catalog/subjects/" + second + "/corequisites/" + twin));
        problem(as(admin).post("/api/admin/catalog/subjects/" + first + "/prerequisites/" + second));
        ok(as(admin).get("/api/catalog/careers/" + career + "/curricula"));
        Response tree = ok(as(admin).get("/api/catalog/curricula/" + curriculum));
        assertThat(tree.jsonPath().getList("levels")).hasSize(2);
        noServerError(as(admin).post("/api/admin/catalog/curricula/" + curriculum + "/activate"));

        LocalDate start = LocalDate.now(ZONE).plusYears(4).plusDays(ThreadLocalRandom.current().nextInt(0, 300));
        Response period = ok(as(admin).body(body("code", start.getYear() + "-" + randomCode(3), "name", "Período de recorrido", "type", "ORDINARY",
                "ordinaryEnrollmentFrom", start, "ordinaryEnrollmentTo", start.plusDays(5), "extraordinaryEnrollmentTo", start.plusDays(10),
                "specialEnrollmentTo", start.plusDays(15), "classesStart", start.plusDays(16), "classesEnd", start.plusDays(128),
                "gradesCloseDeadline", start.plusDays(140))).post("/api/admin/catalog/periods"));
        String periodId = period.path("id");
        noServerError(as(admin).post("/api/admin/catalog/periods/" + periodId + "/status/ENROLLMENT"));
        Response section = as(admin).body(body("subjectId", first, "code", "A1", "capacity", 30, "teacherId", null, "classroomId", null))
                .post("/api/admin/catalog/periods/" + periodId + "/sections");
        noServerError(section);
        if (section.statusCode() == 200) {
            ok(as(admin).body(body("weekday", 2, "start", "08:00", "end", "10:00", "classroomId", null))
                    .post("/api/admin/catalog/sections/" + section.path("id") + "/blocks"));
            ok(as(admin).get("/api/catalog/periods/" + periodId + "/sections"));
        }
        noServerError(as(admin).body(body("periodId", periodId, "careerId", career, "components", List.of(
                Map.of("name", "Gestión formativa", "kind", "FORMATIVE", "weight", 30),
                Map.of("name", "Examen parcial", "kind", "SUMMATIVE", "weight", 30),
                Map.of("name", "Examen final", "kind", "FINAL", "weight", 40)))).post("/api/admin/evaluation/schemes"));
        problem(as(admin).body(body("periodId", periodId, "careerId", career, "components", List.of(
                Map.of("name", "Única", "kind", "FINAL", "weight", 90)))).post("/api/admin/evaluation/schemes"));
    }

    @Test
    void peopleAdmissionAndAcademicRecords() {
        String admin = users.createAndLogin("SUPERADMIN");
        String personId = createPerson(admin, "Rosa Elena", "Quito Paredes");
        ok(as(admin).body(personBody(jdbc.sql("select id_number from person where id = :id").param("id", UUID.fromString(personId))
                .query(String.class).single(), "Rosa Elena", "Quito Paredes", "Av. Solano 1-50, Cuenca")).put("/api/admin/persons/" + personId));
        ok(as(admin).get("/api/admin/persons?q=Quito"));
        ok(as(admin).get("/api/admin/persons/" + personId));
        Response account = ok(as(admin).body(body("roles", List.of("TEACHER"), "institutionalEmail", null)).post("/api/admin/persons/" + personId + "/account"));
        String userId = account.path("userId");
        assertThat((String) account.path("temporaryPassword")).isNotBlank();
        ok(as(admin).get("/api/admin/users/" + userId));
        ok(as(admin).post("/api/admin/users/" + userId + "/temporary-password"));
        ok(as(admin).post("/api/admin/users/" + userId + "/roles/AUDITOR"));
        ok(as(admin).post("/api/admin/users/" + userId + "/roles/AUDITOR/revoke"));
        ok(as(admin).body(Map.of("personId", personId, "highestDegree", "Magíster en Educación", "dedication", "TIEMPO_COMPLETO",
                "category", "AGREGADO")).post("/api/admin/academic/teachers"));
        ok(as(admin).body(Map.of("reason", "Fin del contrato de prueba")).post("/api/admin/users/" + userId + "/disable"));

        UUID periodId = demoPeriod();
        Map<String, Object> program = jdbc.sql("""
                        select k.id career, c.id curriculum from career k join curriculum c on c.career_id = k.id
                        where k.code = 'SOF' and c.status = 'ACTIVE'""").query().singleRow();
        Response process = as(admin).body(body("periodId", periodId, "careerId", program.get("career"), "quota", 40,
                "opensOn", LocalDate.now(ZONE).minusDays(1), "closesOn", LocalDate.now(ZONE).plusDays(10), "minScore", 700))
                .post("/api/admin/academic/admission-processes");
        noServerError(process);
        if (process.statusCode() == 200) {
            String applicantPerson = createPerson(admin, "Mateo Andrés", "Calle Ordóñez");
            String applicant = ok(as(admin).body(Map.of("personId", applicantPerson))
                    .post("/api/admin/academic/admission-processes/" + process.path("id") + "/applicants")).path("id");
            Response admitted = ok(as(admin).body(Map.of("curriculumId", program.get("curriculum"), "score", 850))
                    .post("/api/admin/academic/applicants/" + applicant + "/admit"));
            String newStudent = admitted.path("studentId");
            ok(as(admin).get("/api/admin/academic/students/" + newStudent + "/curriculum"));
            List<String> sections = ok(as(admin).get("/api/catalog/periods/" + periodId + "/sections")).jsonPath().getList("items.id");
            noServerError(as(admin).body(Map.of("sectionIds", List.of(sections.getFirst()))).post("/api/admin/academic/students/" + newStudent + "/enrollment/preview"));
            ok(as(admin).body(body("toStatus", "WITHDRAWN", "process", null)).post("/api/admin/academic/students/" + newStudent + "/status"));
            ok(as(admin).get("/api/admin/academic/students/" + newStudent + "/status-history"));
        }
        String direct = createPerson(admin, "Lucía Fernanda", "Abad Neira");
        noServerError(as(admin).body(Map.of("personId", direct, "careerId", program.get("career"), "curriculumId", program.get("curriculum"),
                "cohortPeriodId", periodId)).post("/api/admin/academic/students"));

        UUID student = demoStudent();
        assertThat(ok(as(admin).get("/api/admin/academic/students?q=" + demo.get("STUDENT"))).jsonPath().getInt("total")).isPositive();
        Response progress = ok(as(admin).get("/api/admin/academic/students/" + student + "/curriculum"));
        ok(as(admin).get("/api/admin/academic/students/" + student + "/status-history"));
        ok(as(admin).get("/api/admin/students/" + student + "/kardex"));
        noServerError(as(admin).body(body("type", "LOAD_EXCEPTION", "studentId", student, "periodId", periodId, "subjectId", null, "sectionId", null,
                "causeCode", "CARGA", "reason", "Rendimiento sobresaliente en el período anterior", "resolutionRef", "RES-CA-2026-01"))
                .post("/api/admin/academic/authorizations"));
        List<String> blocked = progress.jsonPath().getList("subjects.findAll { it.status == 'BLOCKED' || it.status == 'AVAILABLE' }.id");
        if (!blocked.isEmpty()) {
            Response homologation = as(admin).body(body("studentId", student, "targetSubjectId", blocked.getLast(), "method", "KNOWLEDGE_VALIDATION",
                    "sourceInstitution", "Universidad de origen", "sourceSubjectName", "Examen de validación", "sourceHours", null, "sourceGrade", null,
                    "sourceScaleMax", null, "sourceApprovedOn", null, "coveragePercentage", null, "grade", 82)).post("/api/admin/academic/homologations");
            noServerError(homologation);
            if (homologation.statusCode() == 200) {
                noServerError(as(admin).body(Map.of("resolutionRef", "RES-HOM-2026-01"))
                        .post("/api/admin/academic/homologations/" + homologation.path("id") + "/approve"));
            }
        }
        UUID closedSection = jdbc.sql("select section_id from grade_act where status = 'CLOSED' limit 1").query(UUID.class).single();
        assertThat(ok(as(admin).get("/api/admin/sections/" + closedSection + "/acts")).jsonPath().getList("$")).isNotEmpty();
    }

    @Test
    void financeOperatesPaymentsInvoicesAndReconciliation() {
        String admin = users.createAndLogin("FINANCE");
        UUID student = demoStudent();
        UUID period = demoPeriod();
        ok(as(admin).get("/api/admin/finance/students/" + student + "/account"));
        ok(as(admin).get("/api/admin/finance/students/" + student + "/payments"));
        assertThat(ok(as(admin).get("/api/admin/finance/tariffs?periodId=" + period)).jsonPath().getInt("total")).isPositive();
        noServerError(as(admin).body(body("conceptCode", "CERTIFICADO", "careerId", null, "levelNumber", null, "periodId", period, "calcMode", "FLAT",
                "amount", 15.00, "reason", "Actualización del valor del certificado")).post("/api/admin/finance/tariffs"));
        Response payment = as(admin).header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("studentId", student, "amount", 10.00, "method", "CASH", "reference", "CAJA-" + randomCode(8)))
                .post("/api/admin/finance/payments");
        noServerError(payment);
        if (payment.statusCode() == 200) {
            String invoice = payment.path("invoiceId");
            if (invoice != null) {
                RestAssured.given().header("Authorization", "Bearer " + admin).get("/api/admin/finance/invoices/" + invoice + "/ride")
                        .then().statusCode(200).contentType("application/pdf");
                String studentToken = DemoScenario.login(demo.get("STUDENT"));
                RestAssured.given().header("Authorization", "Bearer " + studentToken).get("/api/me/invoices/" + invoice + "/ride")
                        .then().statusCode(200);
            }
            noServerError(as(admin).body(Map.of("reason", "Pago registrado por error de caja")).post("/api/admin/finance/payments/" + payment.path("id") + "/annul"));
        }
        ok(as(admin).post("/api/admin/finance/late-fees/run"));
        Response reconciliation = RestAssured.given().header("Authorization", "Bearer " + admin)
                .multiPart("file", "extracto.csv", "referencia;monto\nNO-EXISTE-1;10.00\nNO-EXISTE-1;10.00\n".getBytes(StandardCharsets.UTF_8), "text/csv")
                .queryParam("source", "BANK").queryParam("statementDate", LocalDate.now(ZONE).toString())
                .post("/api/admin/finance/reconciliations");
        ok(reconciliation);
        assertThat(reconciliation.jsonPath().getInt("discrepancies")).isEqualTo(2);
    }

    @Test
    void welfareRunsTheScholarshipCycleAndSensitiveCare() {
        String welfare = users.createAndLogin("WELFARE");
        String studentToken = DemoScenario.login(demo.get("STUDENT"));
        UUID period = demoPeriod();
        ok(as(welfare).get("/api/admin/welfare/calls"));
        String type = ok(as(welfare).body(body("code", "REC" + randomCode(5), "name", "Beca de recorrido", "description", "Beca creada por la prueba de recorrido",
                "mode", "PERCENT", "value", 20, "minAverage", null, "minAttendance", null, "partialMinAverage", null, "partialValue", null,
                "renewable", true, "coveredConcepts", List.of("ARANCEL"))).post("/api/admin/welfare/types")).path("id");
        String call = ok(as(welfare).body(body("typeId", type, "periodId", period, "name", "Convocatoria de recorrido", "quota", 5,
                "opensAt", Instant.now().minus(1, ChronoUnit.HOURS), "closesAt", Instant.now().plus(7, ChronoUnit.DAYS),
                "resultsBy", LocalDate.now(ZONE).plusDays(20),
                "documents", List.of(Map.of("code", "CERT_INGRESOS", "name", "Certificado de ingresos", "mandatory", true)),
                "criteria", List.of(Map.of("name", "Situación socioeconómica", "maxScore", 100, "weight", 100)))).post("/api/admin/welfare/calls")).path("id");
        assertThat(ok(as(studentToken).get("/api/scholarships/calls/open")).jsonPath().getList("id")).contains(call);

        String application = ok(as(studentToken).body(Map.of("callId", call, "statement", "Postulo por la situación económica de mi hogar."))
                .post("/api/me/scholarships/applications")).path("id");
        ok(as(studentToken).body(Map.of("statement", "Postulo por la situación económica de mi hogar y mi rendimiento."))
                .put("/api/me/scholarships/applications/" + application));
        problem(as(studentToken).post("/api/me/scholarships/applications/" + application + "/submit"));
        byte[] pdf = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        RestAssured.given().header("Authorization", "Bearer " + studentToken).multiPart("file", "ingresos.pdf", pdf, "application/pdf")
                .post("/api/me/scholarships/applications/" + application + "/documents/CERT_INGRESOS").then().statusCode(204);
        ok(as(studentToken).post("/api/me/scholarships/applications/" + application + "/submit"));
        assertThat(ok(as(welfare).get("/api/admin/welfare/calls/" + call + "/applications")).jsonPath().getList("status")).contains("SUBMITTED");
        assertThat(ok(as(welfare).get("/api/admin/welfare/types")).jsonPath().getList("id")).contains(type);
        String criterion = ok(as(welfare).get("/api/admin/welfare/calls/" + call + "/criteria")).jsonPath().getString("[0].id");
        ok(as(welfare).body(Map.of("scores", Map.of(criterion, 88))).post("/api/admin/welfare/applications/" + application + "/score"));
        String committee = users.createAndLogin("WELFARE");
        noServerError(as(committee).body(body("actNumber", "CB-" + randomCode(4), "sessionDate", LocalDate.now(ZONE), "summary", "Sesión ordinaria del comité",
                "members", List.of(Map.of("name", "Presidencia del comité", "role", "PRESIDENTE"), Map.of("name", "Bienestar", "role", "SECRETARIO"),
                        Map.of("name", "Representación estudiantil", "role", "VOCAL")),
                "approved", List.of(application), "rejected", List.of())).post("/api/admin/welfare/calls/" + call + "/resolution"));
        ok(as(studentToken).get("/api/me/scholarships/applications"));
        ok(as(studentToken).get("/api/me/scholarships/awards"));

        UUID closedPeriod = jdbc.sql("select id from academic_period where status = 'CLOSED' order by classes_start desc limit 1").query(UUID.class).single();
        noServerError(as(welfare).post("/api/admin/welfare/periods/" + closedPeriod + "/followup"));
        jdbc.sql("select id from scholarship_appeal where status = 'FILED' limit 1").query(UUID.class).optional().ifPresent(appeal ->
                noServerError(as(committee).body(Map.of("accept", false, "note", "Se ratifica la decisión del seguimiento")).post("/api/admin/welfare/appeals/" + appeal + "/decision")));

        ok(as(studentToken).body(Map.of("purpose", "WELFARE_SENSITIVE")).post("/api/me/consents"));
        ok(as(studentToken).get("/api/me/consents"));
        UUID student = demoStudent();
        ok(as(welfare).body(body("category", "ORIENTATION", "attendedAt", Instant.now().minus(2, ChronoUnit.HOURS), "summary", "Orientación vocacional",
                "details", "Conversación sobre organización del tiempo de estudio.", "followUpRequired", true))
                .post("/api/admin/welfare/students/" + student + "/attentions"));
        assertThat(ok(as(welfare).get("/api/admin/welfare/students/" + student + "/attentions")).jsonPath().getList("summary")).contains("Orientación vocacional");
        ok(as(studentToken).body(Map.of("purpose", "WELFARE_SENSITIVE")).post("/api/me/consents/revoke"));
        problem(as(welfare).body(body("category", "HEALTH", "attendedAt", Instant.now(), "summary", "Sin consentimiento", "details", null,
                "followUpRequired", false)).post("/api/admin/welfare/students/" + student + "/attentions"));

        ok(as(welfare).post("/api/admin/welfare/periods/" + period + "/dropout-alerts/detect"));
        List<String> alerts = ok(as(welfare).get("/api/admin/welfare/periods/" + period + "/dropout-alerts")).jsonPath().getList("id");
        if (!alerts.isEmpty()) {
            ok(as(welfare).body(Map.of("status", "IN_FOLLOW_UP", "note", "Contacto telefónico realizado")).post("/api/admin/welfare/dropout-alerts/" + alerts.getFirst()));
        }
    }

    @Test
    void reportsDashboardsParametersAndAudit() {
        String admin = users.createAndLogin("SUPERADMIN");
        UUID period = demoPeriod();
        List<String> codes = ok(as(admin).get("/api/reports")).jsonPath().getList("code");
        assertThat(codes).hasSizeGreaterThanOrEqualTo(9);
        for (String code : codes) {
            ok(as(admin).get("/api/reports/" + code + "?periodId=" + period + "&cutoff=" + LocalDate.now(ZONE)));
        }
        String xlsx = ok(as(admin).post("/api/reports/enrolled/jobs?format=XLSX&periodId=" + period)).path("jobId");
        String pdf = ok(as(admin).post("/api/reports/occupancy/jobs?format=PDF&periodId=" + period)).path("jobId");
        int guard = 0;
        while (reportJobs.processNext() && guard++ < 20) {
            assertThat(guard).isLessThan(20);
        }
        assertThat(ok(as(admin).get("/api/reports/jobs/" + xlsx)).<String>path("status")).isEqualTo("SUCCEEDED");
        assertThat(ok(as(admin).get("/api/reports/jobs/" + pdf)).<String>path("status")).isEqualTo("SUCCEEDED");
        ok(as(admin).get("/api/reports/jobs"));
        RestAssured.given().header("Authorization", "Bearer " + admin).get("/api/reports/jobs/" + xlsx + "/file").then().statusCode(200);
        assertThat(ok(as(admin).get("/api/admin/dashboard")).jsonPath().getList("kpis")).isNotEmpty();

        Response params = ok(as(admin).get("/api/parameters?size=100"));
        String key = params.jsonPath().getString("items[0].key");
        String value = params.jsonPath().getString("items[0].value");
        ok(as(admin).get("/api/parameters/" + key + "/history"));
        ok(as(admin).body(Map.of("value", value, "validFrom", Instant.now().plus(3650, ChronoUnit.DAYS).toString(),
                "reason", "Versión futura registrada por la prueba de recorrido")).post("/api/parameters/" + key + "/versions"));
        ok(anonymous().get("/api/public/settings"));
        assertThat(ok(as(admin).get("/api/audit?table=person&size=5")).jsonPath().getInt("total")).isPositive();
        ok(as(admin).get("/api/audit?action=DENIED"));

        String auditor = users.createAndLogin("AUDITOR");
        ok(as(auditor).get("/api/admin/dashboard"));
        as(auditor).body(Map.of("value", value, "reason", "Intento de escritura de solo lectura")).post("/api/parameters/" + key + "/versions")
                .then().statusCode(403);
        ok(anonymous().get("/api/public/manuals"));
    }

    @Test
    void accountSecurityFlows() {
        TestUsers.TestUser user = users.create("STUDENT");
        String token = users.login(user);
        ok(as(token).get("/api/auth/me"));
        String fresh = "Nueva-Clave-" + randomCode(10) + "7";
        ok(as(token).body(Map.of("currentPassword", user.password(), "newPassword", fresh)).post("/api/auth/password/change"));
        problem(anonymous().body(Map.of("username", user.username(), "password", user.password())).post("/api/auth/login"));
        Response relogin = ok(anonymous().body(Map.of("username", user.username(), "password", fresh)).post("/api/auth/login"));
        String second = relogin.path("accessToken");
        ok(as(second).post("/api/auth/logout-all"));
        problem(anonymous().body(Map.of("token", "token-inexistente-" + randomCode(20), "newPassword", fresh + "x")).post("/api/auth/password/reset"));
    }

    // ------------------------------------------------------------------ apoyo

    private UUID demoStudent() {
        return jdbc.sql("select s.id from student s join person p on p.id = s.person_id where p.id_number = :n")
                .param("n", demo.get("STUDENT")).query(UUID.class).single();
    }

    private UUID demoPeriod() {
        return jdbc.sql("""
                        select e.period_id from enrollment e join academic_period p on p.id = e.period_id
                        where e.student_id = :s order by p.classes_start desc limit 1""")
                .param("s", demoStudent()).query(UUID.class).single();
    }

    private String createPerson(String token, String names, String surnames) {
        String idNumber = TestIds.cedula(24, ThreadLocalRandom.current().nextLong(100_000, 999_999));
        return ok(as(token).body(personBody(idNumber, names, surnames, "Cuenca, Azuay")).post("/api/admin/persons")).path("id");
    }

    private static Map<String, Object> personBody(String idNumber, String names, String surnames, String address) {
        return body("idType", "CEDULA", "idNumber", idNumber, "firstNames", names, "lastNames", surnames, "birthDate", "1990-03-12",
                "sex", "MUJER", "maritalStatus", "CASADO", "nationality", "ECU", "address", address, "phoneMobile", "0991112233",
                "phoneHome", null, "personalEmail", "persona.ficticia@correo-ficticio.ec");
    }

    private static Map<String, Object> subject(int level, String code, String name) {
        return body("levelNumber", level, "code", code, "name", name, "teacherContactHours", 64, "practicalHours", 32, "autonomousHours", 48,
                "credits", 3.00, "organizationUnit", "BASICA", "titling", false);
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            map.put((String) pairs[i], v instanceof LocalDate || v instanceof Instant || v instanceof UUID ? v.toString() : v);
        }
        return map;
    }

    private static String randomCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static Response ok(Response r) {
        assertThat(r.statusCode()).as("%s", r.asString()).isBetween(200, 299);
        return r;
    }

    private static void noServerError(Response r) {
        assertThat(r.statusCode()).as("%s", r.asString()).isLessThan(500);
    }

    private static void problem(Response r) {
        assertThat(r.statusCode()).as("%s", r.asString()).isBetween(400, 499);
    }
}
