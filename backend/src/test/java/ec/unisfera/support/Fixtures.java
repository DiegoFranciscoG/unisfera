package ec.unisfera.support;

import ec.unisfera.shared.domain.UuidV7;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Constructores de escenarios con valores por defecto sensatos: cada prueba declara solo lo que
 * le importa. Escriben directo en la base (con todas sus reglas activas).
 */
@Component
public class Fixtures {

    public record Curriculum(UUID careerId, UUID curriculumId, Map<String, UUID> subjects) {
        public UUID subject(String code) {
            return subjects.get(code);
        }
    }

    public record Period(UUID id, String code) {
    }

    private static final ZoneId ZONE = ZoneId.of("America/Guayaquil");
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 300_000 + 500_000);

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private UUID facultyId;
    private UUID campusId;

    public Fixtures(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public synchronized void ensureInstitution() {
        if (facultyId != null) {
            return;
        }
        UUID inst = jdbc.sql("select id from institution limit 1").query(UUID.class).optional().orElse(null);
        if (inst == null) {
            inst = UuidV7.next();
            jdbc.sql("""
                            insert into institution (id, name, legal_name, ruc, kind, city, address, email_domain, sri_establishment, sri_emission_point)
                            values (:id, 'Universidad de Prueba', 'Universidad de Prueba', '0190000000001', 'PRIVATE_COFINANCED', 'Cuenca',
                                    'Av. de Prueba 1-23', 'test.unisfera.edu.ec', '001', '001')""")
                    .param("id", inst).update();
        }
        facultyId = UuidV7.next();
        campusId = UuidV7.next();
        String suffix = Long.toString(SEQ.incrementAndGet());
        jdbc.sql("insert into faculty (id, institution_id, code, name) values (:id, :i, :c, 'Facultad de Pruebas')")
                .param("id", facultyId).param("i", inst).param("c", "F" + suffix.substring(suffix.length() - 6)).update();
        jdbc.sql("insert into campus (id, institution_id, code, name, city) values (:id, :i, :c, 'Campus Central', 'Cuenca')")
                .param("id", campusId).param("i", inst).param("c", "C" + suffix.substring(suffix.length() - 6)).update();
    }

    /**
     * Malla de tres niveles: A y B (nivel 1); C requiere A, D requiere B y es correquisito de E (nivel 2); F requiere C (nivel 3).
     */
    public Curriculum curriculum() {
        ensureInstitution();
        String code = "K" + Long.toString(SEQ.incrementAndGet()).substring(1);
        UUID career = UuidV7.next();
        jdbc.sql("""
                        insert into career (id, faculty_id, campus_id, code, senescyt_code, name, degree_title, modality, education_level, duration_periods)
                        values (:id, :f, :c, :code, :sen, 'Ingeniería de Pruebas', 'Ingeniero/a de Pruebas', 'PRESENCIAL', 'TERCER_NIVEL_GRADO', 8)""")
                .param("id", career).param("f", facultyId).param("c", campusId).param("code", code).param("sen", "SEN-" + code).update();
        UUID curriculum = UuidV7.next();
        jdbc.sql("""
                        insert into curriculum (id, career_id, version_code, name, valid_from, approval_resolution, status)
                        values (:id, :c, 'V1', 'Malla 2026', date '2024-01-01', 'RPC-TEST-001', 'ACTIVE')""")
                .param("id", curriculum).param("c", career).update();
        Map<Integer, UUID> levels = new HashMap<>();
        for (int l = 1; l <= 3; l++) {
            UUID id = UuidV7.next();
            levels.put(l, id);
            jdbc.sql("insert into curriculum_level (id, curriculum_id, number, name) values (:id, :c, :n, :name)")
                    .param("id", id).param("c", curriculum).param("n", l).param("name", "Nivel " + l).update();
        }
        Map<String, UUID> subjects = new HashMap<>();
        String[][] defs = {{"A", "1"}, {"B", "1"}, {"C", "2"}, {"D", "2"}, {"E", "2"}, {"F", "3"}, {"G", "1"}, {"H", "1"}};
        for (String[] d : defs) {
            UUID id = UuidV7.next();
            subjects.put(d[0], id);
            jdbc.sql("""
                            insert into subject (id, curriculum_id, level_id, code, name, teacher_contact_hours, practical_hours, autonomous_hours,
                                                 credits, organization_unit)
                            values (:id, :c, :l, :code, :name, 64, 32, 48, 3.00, 'BASICA')""")
                    .param("id", id).param("c", curriculum).param("l", levels.get(Integer.parseInt(d[1]))).param("code", "S-" + d[0])
                    .param("name", "Asignatura " + d[0]).update();
        }
        prerequisite(subjects.get("C"), subjects.get("A"));
        prerequisite(subjects.get("D"), subjects.get("B"));
        prerequisite(subjects.get("F"), subjects.get("C"));
        jdbc.sql("insert into subject_corequisite (subject_id, corequisite_subject_id) values (:a, :b), (:b, :a)")
                .param("a", subjects.get("D")).param("b", subjects.get("E")).update();
        return new Curriculum(career, curriculum, subjects);
    }

    public void prerequisite(UUID subject, UUID required) {
        jdbc.sql("insert into subject_prerequisite (subject_id, required_subject_id) values (:s, :r)").param("s", subject).param("r", required).update();
    }

    /** Período con la matrícula ordinaria abierta hoy y tarifas por defecto. */
    public Period openPeriod() {
        LocalDate today = LocalDate.now(ZONE);
        return period(today.minusDays(2), today.plusDays(2), today.plusDays(5), today.plusDays(8), today.plusDays(9));
    }

    public Period period(LocalDate ordFrom, LocalDate ordTo, LocalDate extraTo, LocalDate specialTo, LocalDate classesStart) {
        UUID id = UuidV7.next();
        String code = classesStart.getYear() + "-" + randomCode();
        jdbc.sql("""
                        insert into academic_period (id, code, name, type, ordinary_enrollment_from, ordinary_enrollment_to, extraordinary_enrollment_to,
                                                     special_enrollment_to, classes_start, classes_end, grades_close_deadline, status)
                        values (:id, :code, :name, 'ORDINARY', :of, :ot, :et, :st, :cs, :ce, :gd, 'ENROLLMENT')""")
                .param("id", id).param("code", code).param("name", "Período " + code).param("of", Date.valueOf(ordFrom)).param("ot", Date.valueOf(ordTo))
                .param("et", Date.valueOf(extraTo)).param("st", Date.valueOf(specialTo)).param("cs", Date.valueOf(classesStart))
                .param("ce", Date.valueOf(classesStart.plusWeeks(16))).param("gd", Date.valueOf(classesStart.plusWeeks(18))).update();
        tariff("MATRICULA", id, "FLAT", new BigDecimal("198.00"));
        tariff("ARANCEL", id, "PER_CREDIT", new BigDecimal("45.00"));
        return new Period(id, code);
    }

    public void tariff(String concept, UUID periodId, String mode, BigDecimal amount) {
        jdbc.sql("""
                        insert into tariff (id, concept_id, period_id, calc_mode, amount)
                        select :id, id, :p, :m, :a from charge_concept where code = :c""")
                .param("id", UuidV7.next()).param("p", periodId).param("m", mode).param("a", amount).param("c", concept).update();
    }

    public UUID section(UUID periodId, UUID subjectId, int capacity, int weekday, LocalTime start, LocalTime end) {
        UUID id = UuidV7.next();
        jdbc.sql("insert into section (id, period_id, subject_id, code, capacity) values (:id, :p, :s, :c, :cap)")
                .param("id", id).param("p", periodId).param("s", subjectId).param("c", randomCode().substring(0, 3)).param("cap", capacity).update();
        jdbc.sql("insert into schedule_block (id, section_id, weekday, start_time, end_time) values (:id, :s, :w, :st, :en)")
                .param("id", UuidV7.next()).param("s", id).param("w", weekday).param("st", Time.valueOf(start)).param("en", Time.valueOf(end)).update();
        return id;
    }

    public UUID section(UUID periodId, UUID subjectId, int capacity) {
        int hour = 7 + ThreadLocalRandom.current().nextInt(0, 1);
        return section(periodId, subjectId, capacity, 1 + (int) (SEQ.incrementAndGet() % 6), LocalTime.of(hour, 0), LocalTime.of(hour + 2, 0));
    }

    /** Estudiantes ADMITTED en lote (rápido para las pruebas de carga y concurrencia). */
    public List<UUID> students(Curriculum curriculum, UUID cohortPeriodId, int count) {
        List<Object[]> persons = new ArrayList<>();
        List<Object[]> students = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long n = SEQ.incrementAndGet();
            UUID personId = UuidV7.next();
            UUID studentId = UuidV7.next();
            persons.add(new Object[]{personId, IntegrationTest.TestIds.cedula(3 + (int) (n % 20), n), "Estudiante", "Prueba " + n});
            students.add(new Object[]{studentId, personId, "T" + n, curriculum.careerId(), curriculum.curriculumId(), cohortPeriodId});
            ids.add(studentId);
        }
        template.batchUpdate("""
                insert into person (id, id_type, id_number, first_names, last_names, birth_date, sex, nationality)
                values (?, 'CEDULA', ?, ?, ?, date '2004-01-01', 'HOMBRE', 'ECU')""", persons);
        template.batchUpdate("insert into student (id, person_id, code, career_id, curriculum_id, cohort_period_id) values (?, ?, ?, ?, ?, ?)", students);
        return ids;
    }

    public UUID student(Curriculum curriculum, UUID cohortPeriodId) {
        return students(curriculum, cohortPeriodId, 1).getFirst();
    }

    /** Registra una asignatura aprobada en el kardex mediante una homologación aprobada (vía legítima). */
    public void approved(UUID studentId, UUID subjectId) {
        jdbc.sql("""
                        insert into homologation (id, student_id, target_subject_id, method, source_institution, source_subject_name, grade,
                                                  status, resolution_ref, decided_at)
                        values (:id, :s, :t, 'KNOWLEDGE_VALIDATION', 'Universidad de Prueba', 'Examen de validación', 85, 'APPROVED', 'RES-TEST', now())""")
                .param("id", UuidV7.next()).param("s", studentId).param("t", subjectId).update();
    }

    private static String randomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }
}
