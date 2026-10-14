package ec.unisfera.seed;

import ec.unisfera.academic.application.EnrollmentService;
import ec.unisfera.evaluation.domain.ActHasher;
import ec.unisfera.evaluation.domain.GradeMath;
import ec.unisfera.finance.domain.AccessKeyGenerator;
import ec.unisfera.finance.domain.ChargeCalculator;
import ec.unisfera.finance.domain.InstallmentPlanner;
import ec.unisfera.finance.domain.LateFeeCalculator;
import ec.unisfera.finance.domain.PaymentAllocator;
import ec.unisfera.identity.security.CryptoService;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.welfare.domain.WelfareRules;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generador del escenario de demostración con semilla fija: el mismo comando produce siempre el
 * mismo escenario (las fechas se anclan a la fecha de ejecución para que la matrícula esté abierta).
 * Todo se inserta con las reglas de la base activas: si el generador intentara algo incoherente
 * (sobrecupo, cuarta matrícula, pesos ≠ 100, mora mal calculada...), la carga fallaría.
 */
@Component
public class DemoDataGenerator {

    public static final long SEED = 20261013L;
    private static final Logger log = LoggerFactory.getLogger(DemoDataGenerator.class);
    private static final int LEVELS = 5;
    private static final int PER_LEVEL = 3;
    private static final int HISTORICAL_PERIODS = 4;
    private static final int SECTION_CAPACITY = 40;
    private static final int SECTION_FILL = 35;
    private static final int TEACHERS_PER_CAREER = 10;
    private static final String[] COMPONENTS = {"Aporte 1", "Aporte 2", "Examen final"};
    private static final String[] COMPONENT_KINDS = {"FORMATIVE", "SUMMATIVE", "FINAL"};
    private static final BigDecimal[] WEIGHTS = {new BigDecimal("35"), new BigDecimal("35"), new BigDecimal("30")};

    public record Result(int students, int teachers, int subjects, int periods, int enrollments, int payments, Map<String, String> demoUsers) {
    }

    record Career(UUID id, String code, String name, String degree, int faculty, BigDecimal pricePerCredit, String[][] subjects) {
    }

    record Subject(UUID id, UUID curriculumId, int careerIdx, int version, int level, int column, String name, int hours, BigDecimal credits) {
        int index() {
            return (level - 1) * PER_LEVEL + column;
        }
    }

    record Period(UUID id, String code, LocalDate ordinaryFrom, LocalDate ordinaryTo, LocalDate extraTo, LocalDate specialTo,
                  LocalDate classesStart, LocalDate classesEnd, UUID schemeId, List<UUID> components) {
    }

    static final class Student {
        UUID id;
        UUID personId;
        UUID userId;
        String idNumber;
        int career;
        int version;
        int cohort;
        double ability;
        String status = "ADMITTED";
        final Map<UUID, Integer> attempts = new HashMap<>();
        final Set<UUID> approved = new HashSet<>();
        UUID award;
        boolean dropped;
    }

    private static final Career[] CAREERS = {
            new Career(null, "SOF", "Software", "Ingeniero/a de Software", 0, new BigDecimal("55.00"), new String[][]{
                    {"Fundamentos de Programación", "Matemática Discreta", "Comunicación Académica"},
                    {"Programación Orientada a Objetos", "Cálculo Diferencial", "Arquitectura de Computadores"},
                    {"Estructuras de Datos", "Cálculo Integral", "Sistemas Operativos"},
                    {"Bases de Datos", "Estadística", "Redes de Computadoras"},
                    {"Ingeniería de Software", "Investigación Operativa", "Seguridad Informática"}}),
            new Career(null, "CIV", "Ingeniería Civil", "Ingeniero/a Civil", 0, new BigDecimal("57.00"), new String[][]{
                    {"Dibujo Técnico", "Álgebra Lineal", "Química General"},
                    {"Topografía", "Cálculo I", "Física I"},
                    {"Geomática", "Cálculo II", "Mecánica de Materiales"},
                    {"Vías de Comunicación", "Ecuaciones Diferenciales", "Hormigón Armado I"},
                    {"Proyectos Viales", "Hidráulica", "Hormigón Armado II"}}),
            new Career(null, "ADE", "Administración de Empresas", "Licenciado/a en Administración de Empresas", 1, new BigDecimal("50.00"), new String[][]{
                    {"Fundamentos de Administración", "Matemática Financiera", "Economía General"},
                    {"Proceso Administrativo", "Estadística Descriptiva", "Microeconomía"},
                    {"Gestión del Talento Humano", "Estadística Inferencial", "Macroeconomía"},
                    {"Marketing Estratégico", "Investigación de Mercados", "Finanzas Corporativas"},
                    {"Gerencia Estratégica", "Formulación de Proyectos", "Comercio Internacional"}}),
            new Career(null, "CON", "Contabilidad y Auditoría", "Licenciado/a en Contabilidad y Auditoría", 1, new BigDecimal("50.00"), new String[][]{
                    {"Contabilidad General", "Matemática Básica", "Derecho Mercantil"},
                    {"Contabilidad Intermedia", "Matemática Aplicada", "Legislación Tributaria"},
                    {"Contabilidad de Costos", "Estadística Aplicada", "Tributación I"},
                    {"Auditoría Financiera", "Presupuestos", "Tributación II"},
                    {"Auditoría de Gestión", "Análisis Financiero", "NIIF Aplicadas"}}),
            new Career(null, "PSI", "Psicología Clínica", "Psicólogo/a Clínico/a", 2, new BigDecimal("58.00"), new String[][]{
                    {"Introducción a la Psicología", "Biología Humana", "Antropología"},
                    {"Psicología del Desarrollo", "Neuroanatomía", "Psicología Social"},
                    {"Psicopatología I", "Neuropsicología", "Psicometría I"},
                    {"Psicopatología II", "Psicofarmacología", "Psicometría II"},
                    {"Psicoterapia Clínica", "Evaluación Clínica", "Psicología de la Salud"}}),
            new Career(null, "EDU", "Educación Básica", "Licenciado/a en Educación Básica", 2, new BigDecimal("48.00"), new String[][]{
                    {"Fundamentos de la Educación", "Lengua y Comunicación", "Desarrollo Infantil"},
                    {"Didáctica General", "Didáctica de la Lengua", "Psicología del Aprendizaje"},
                    {"Planificación Curricular", "Didáctica de la Matemática", "Necesidades Educativas Especiales"},
                    {"Evaluación Educativa", "Didáctica de las Ciencias", "Educación Inclusiva"},
                    {"Gestión Educativa", "Investigación Educativa", "Práctica Docente"}})};

    private static final String[][] FACULTIES = {{"FING", "Facultad de Ingenierías"}, {"FCEA", "Facultad de Ciencias Económicas y Administrativas"},
            {"FSAE", "Facultad de Ciencias de la Salud y Educación"}};

    private final JdbcTemplate jt;
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final CryptoService crypto;
    private final ParameterService parameters;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private final EnrollmentService enrollment;

    private SplittableRandom random;
    private SyntheticPeople people;
    private LocalDate today;
    private ZoneId zone;
    private Currency currency;
    private UUID institutionId;
    private UUID campusId;
    private final UUID[] facultyIds = new UUID[FACULTIES.length];
    private final UUID[] careerIds = new UUID[CAREERS.length];
    private final UUID[][] curriculumIds = new UUID[CAREERS.length][2];
    private final List<Subject> subjects = new ArrayList<>();
    private final Map<UUID, Subject> subjectById = new HashMap<>();
    private final List<Period> periods = new ArrayList<>();
    private final List<UUID> teacherIds = new ArrayList<>();
    private final List<UUID> classroomIds = new ArrayList<>();
    private final List<Student> students = new ArrayList<>();
    private final Map<String, UUID> conceptIds = new HashMap<>();
    private final Map<String, String> demoUsers = new LinkedHashMap<>();
    private final Map<String, UUID> scholarshipTypes = new LinkedHashMap<>();
    private UUID adminUserId;
    private UUID welfareUserId;
    private UUID demoTeacherId;
    private String passwordHash;
    private UUID sampleFileId;
    private int enrollmentCount;
    private int paymentCount;
    private long invoiceSequence;

    public DemoDataGenerator(JdbcTemplate jt, JdbcClient jdbc, PasswordEncoder encoder, CryptoService crypto, ParameterService parameters,
                             AppProperties props, TransactionTemplate tx, EnrollmentService enrollment) {
        this.jt = jt;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.crypto = crypto;
        this.parameters = parameters;
        this.props = props;
        this.tx = tx;
        this.enrollment = enrollment;
    }

    public boolean alreadySeeded() {
        return jdbc.sql("select count(*) from career where code = 'SOF'").query(Long.class).single() > 0;
    }

    public Result generate(int studentCount, int loadTestStudents, String demoPassword) {
        random = new SplittableRandom(SEED);
        people = new SyntheticPeople(random);
        people.reserve(jdbc.sql("select id_number from person").query(String.class).list());
        zone = props.zoneId();
        today = LocalDate.now(zone);
        currency = parameters.currency();
        passwordHash = encoder.encode(demoPassword);
        invoiceSequence = jdbc.sql("select coalesce(max(next_value), 1) - 1 from invoice_sequence where establishment = '001' and emission_point = '001' and doc_type = '01'")
                .query(Long.class).single();
        tx.executeWithoutResult(s -> {
            institution();
            catalog();
            periods();
            staff();
            scholarshipCatalog();
        });
        int perCohort = Math.max(1, studentCount / ((HISTORICAL_PERIODS + 1) * CAREERS.length));
        tx.executeWithoutResult(s -> createStudents(perCohort));
        for (int p = 0; p < HISTORICAL_PERIODS; p++) {
            final int period = p;
            tx.executeWithoutResult(s -> historicalPeriod(period));
            log.info("Período histórico {} generado", periods.get(p).code());
        }
        tx.executeWithoutResult(s -> {
            welfareCases();
            jt.update("""
                    insert into invoice_sequence (establishment, emission_point, doc_type, next_value) values ('001', '001', '01', ?)
                    on conflict (establishment, emission_point, doc_type) do update set next_value = excluded.next_value""", invoiceSequence + 1);
        });
        currentPeriod(loadTestStudents);
        exportLoadTestUsers();
        return new Result(students.size(), teacherIds.size(), subjects.size(), periods.size(), enrollmentCount, paymentCount, demoUsers);
    }

    // ------------------------------------------------------------------ institución y catálogo
    private void institution() {
        institutionId = jdbc.sql("select id from institution limit 1").query(UUID.class).optional().orElse(null);
        if (institutionId != null) {
            createCampusAndFaculties();
            return;
        }
        institutionId = UuidV7.next();
        jt.update("""
                insert into institution (id, name, legal_name, ruc, kind, city, address, email_domain, sri_establishment, sri_emission_point)
                values (?, 'Universidad UNISFERA', 'Universidad UNISFERA (institución ficticia de demostración)', '0190099999001',
                        'PRIVATE_COFINANCED', 'Cuenca', 'Av. de los Cuatro Ríos s/n, Cuenca', 'unisfera.edu.ec', '001', '001')""", institutionId);
        createCampusAndFaculties();
    }

    private void createCampusAndFaculties() {
        campusId = UuidV7.next();
        jt.update("insert into campus (id, institution_id, code, name, city, address) values (?, ?, 'TOM', 'Campus Tomebamba', 'Cuenca', 'Av. de los Cuatro Ríos s/n')",
                campusId, institutionId);
        for (int f = 0; f < FACULTIES.length; f++) {
            facultyIds[f] = UuidV7.next();
            jt.update("insert into faculty (id, institution_id, code, name) values (?, ?, ?, ?)", facultyIds[f], institutionId, FACULTIES[f][0], FACULTIES[f][1]);
        }
        for (int r = 0; r < CAREERS.length * 2 * PER_LEVEL; r++) {
            UUID id = UuidV7.next();
            classroomIds.add(id);
            jt.update("insert into classroom (id, campus_id, code, name, capacity, kind) values (?, ?, ?, ?, ?, ?)", id, campusId,
                    "A-" + (101 + r), "Aula " + (101 + r), SECTION_CAPACITY + 5, r % 6 == 5 ? "LABORATORIO" : "AULA");
        }
        for (String code : List.of("MATRICULA", "ARANCEL", "RECARGO_EXTRA", "MORA")) {
            conceptIds.put(code, jdbc.sql("select id from charge_concept where code = :c").param("c", code).query(UUID.class).single());
        }
    }

    private void catalog() {
        for (int c = 0; c < CAREERS.length; c++) {
            Career career = CAREERS[c];
            careerIds[c] = UuidV7.next();
            jt.update("""
                    insert into career (id, faculty_id, campus_id, code, senescyt_code, name, degree_title, modality, education_level, duration_periods)
                    values (?, ?, ?, ?, ?, ?, ?, 'PRESENCIAL', 'TERCER_NIVEL_GRADO', ?)""",
                    careerIds[c], facultyIds[career.faculty()], campusId, career.code(), "DEMO-" + career.code() + "-" + (1000 + c * 7),
                    career.name(), career.degree(), LEVELS);
            for (int v = 0; v < 2; v++) {
                UUID curriculum = UuidV7.next();
                curriculumIds[c][v] = curriculum;
                String version = v == 0 ? "2019" : "2025";
                jt.update("""
                        insert into curriculum (id, career_id, version_code, name, valid_from, valid_to, approval_resolution, status)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""", curriculum, careerIds[c], version, "Malla " + version + " · " + career.name(),
                        Date.valueOf(v == 0 ? LocalDate.of(2019, 9, 1) : LocalDate.of(2025, 4, 1)), v == 0 ? Date.valueOf(LocalDate.of(2025, 3, 31)) : null,
                        "RPC-DEMO-" + version + "-" + career.code(), v == 0 ? "CLOSED" : "ACTIVE");
                UUID[] levelIds = new UUID[LEVELS];
                for (int l = 0; l < LEVELS; l++) {
                    levelIds[l] = UuidV7.next();
                    jt.update("insert into curriculum_level (id, curriculum_id, number, name) values (?, ?, ?, ?)", levelIds[l], curriculum, l + 1,
                            "Nivel " + (l + 1));
                }
                for (int l = 0; l < LEVELS; l++) {
                    for (int k = 0; k < PER_LEVEL; k++) {
                        UUID id = UuidV7.next();
                        int contact = k == 2 ? 48 : 64;
                        int practical = k == 0 ? 48 : 32;
                        int autonomous = 96;
                        int hours = contact + practical + autonomous;
                        BigDecimal credits = BigDecimal.valueOf(hours).divide(BigDecimal.valueOf(48), 2, RoundingMode.HALF_UP);
                        String name = career.subjects()[l][k] + (v == 0 ? "" : " (2025)");
                        jt.update("""
                                insert into subject (id, curriculum_id, level_id, code, name, teacher_contact_hours, practical_hours, autonomous_hours,
                                                     credits, organization_unit, is_titling)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, curriculum, levelIds[l],
                                career.code() + version.substring(2) + "-" + (l + 1) + "0" + (k + 1), name, contact, practical, autonomous, credits,
                                l < 2 ? "BASICA" : l < 4 ? "PROFESIONAL" : "INTEGRACION_CURRICULAR", l == LEVELS - 1 && k == 0);
                        Subject subject = new Subject(id, curriculum, c, v, l + 1, k, name, hours, credits);
                        subjects.add(subject);
                        subjectById.put(id, subject);
                    }
                }
                for (Subject s : subjects) {
                    if (s.curriculumId().equals(curriculum) && s.level() > 1) {
                        Subject required = find(c, v, s.level() - 1, s.column());
                        jt.update("insert into subject_prerequisite (subject_id, required_subject_id) values (?, ?)", s.id(), required.id());
                    }
                }
            }
        }
    }

    private void periods() {
        LocalDate currentStart = today.plusDays(12);
        for (int p = HISTORICAL_PERIODS; p >= 0; p--) {
            LocalDate start = currentStart.minusMonths(6L * p);
            String code = start.getYear() + "-" + (start.getMonthValue() <= 6 ? "1" : "2");
            UUID id = UuidV7.next();
            Period period = new Period(id, code, start.minusDays(21), start.minusDays(3), start.plusDays(7), start.plusDays(14), start,
                    start.plusWeeks(16), UuidV7.next(), new ArrayList<>());
            jt.update("""
                    insert into academic_period (id, code, name, type, ordinary_enrollment_from, ordinary_enrollment_to, extraordinary_enrollment_to,
                                                 special_enrollment_to, classes_start, classes_end, grades_close_deadline, status)
                    values (?, ?, ?, 'ORDINARY', ?, ?, ?, ?, ?, ?, ?, ?)""", id, code, "Período académico " + code,
                    Date.valueOf(period.ordinaryFrom()), Date.valueOf(period.ordinaryTo()), Date.valueOf(period.extraTo()), Date.valueOf(period.specialTo()),
                    Date.valueOf(start), Date.valueOf(period.classesEnd()), Date.valueOf(period.classesEnd().plusDays(14)), p == 0 ? "ENROLLMENT" : "CLOSED");
            jt.update("""
                    insert into evaluation_scheme (id, period_id, scale_min, scale_max, passing_grade, min_attendance_pct, max_component_weight)
                    values (?, ?, ?, ?, ?, ?, ?)""", period.schemeId(), id, parameters.decimal(ParameterKeys.SCALE_MIN), parameters.decimal(ParameterKeys.SCALE_MAX),
                    parameters.decimal(ParameterKeys.PASSING_GRADE), parameters.decimal(ParameterKeys.MIN_ATTENDANCE),
                    parameters.decimal(ParameterKeys.MAX_COMPONENT_WEIGHT));
            for (int i = 0; i < COMPONENTS.length; i++) {
                UUID comp = UuidV7.next();
                period.components().add(comp);
                jt.update("insert into evaluation_component (id, scheme_id, name, kind, weight, sort_order) values (?, ?, ?, ?, ?, ?)", comp,
                        period.schemeId(), COMPONENTS[i], COMPONENT_KINDS[i], WEIGHTS[i], i + 1);
            }
            // Tarifario versionado: el precio sube cada año (el histórico no cambia)
            int yearsFromBase = HISTORICAL_PERIODS - p;
            BigDecimal inflation = BigDecimal.ONE.add(new BigDecimal("0.03").multiply(BigDecimal.valueOf(yearsFromBase / 2)));
            tariff("MATRICULA", null, id, "FLAT", new BigDecimal("198.00").multiply(inflation).setScale(2, RoundingMode.HALF_UP));
            for (int c = 0; c < CAREERS.length; c++) {
                tariff("ARANCEL", careerIds[c], id, "PER_CREDIT", CAREERS[c].pricePerCredit().multiply(inflation).setScale(2, RoundingMode.HALF_UP));
            }
            periods.add(period);
        }
    }

    private void tariff(String concept, UUID careerId, UUID periodId, String mode, BigDecimal amount) {
        jt.update("insert into tariff (id, concept_id, career_id, period_id, calc_mode, amount, change_reason) values (?, ?, ?, ?, ?, ?, ?)",
                UuidV7.next(), conceptIds.get(concept), careerId, periodId, mode, amount, "Tarifario del período (escenario de demostración)");
    }

    // ------------------------------------------------------------------ personal y cuentas demo
    private void staff() {
        adminUserId = account(people.next(30, 55, today), "ACADEMIC_ADMIN", "Administración académica");
        account(people.next(28, 50, today), "REGISTRAR", "Secretaría");
        account(people.next(30, 55, today), "FINANCE", "Financiero");
        welfareUserId = account(people.next(30, 55, today), "WELFARE", "Bienestar");
        account(people.next(35, 60, today), "AUDITOR", "Auditoría");
        String[] degrees = {"Magíster en Ingeniería de Software", "PhD en Ingeniería Civil", "Magíster en Administración de Empresas",
                "Magíster en Contabilidad y Finanzas", "PhD en Psicología Clínica", "Magíster en Educación"};
        for (int c = 0; c < CAREERS.length; c++) {
            for (int t = 0; t < TEACHERS_PER_CAREER; t++) {
                SyntheticPeople.Person person = people.next(30, 62, today);
                UUID personId = person(person);
                UUID teacherId = UuidV7.next();
                jt.update("insert into teacher (id, person_id, code, highest_degree, dedication, category) values (?, ?, ?, ?, ?, ?)", teacherId, personId,
                        "D" + String.format("%05d", 101 + teacherIds.size()), degrees[c], t < 6 ? "TIEMPO_COMPLETO" : "MEDIO_TIEMPO",
                        new String[]{"AUXILIAR", "AGREGADO", "PRINCIPAL"}[t % 3]);
                teacherIds.add(teacherId);
                if (c == 0 && t == 0) {
                    demoTeacherId = teacherId;
                    UUID user = userAccount(personId, person.idNumber(), "TEACHER");
                    demoUsers.put("TEACHER", person.idNumber());
                    log.debug("Docente demo {}", user);
                }
            }
        }
    }

    private UUID account(SyntheticPeople.Person p, String role, String label) {
        UUID personId = person(p);
        UUID user = userAccount(personId, p.idNumber(), role);
        demoUsers.put(role, p.idNumber());
        log.debug("Cuenta demo {} ({})", label, role);
        return user;
    }

    private UUID person(SyntheticPeople.Person p) {
        UUID id = UuidV7.next();
        jt.update("""
                insert into person (id, id_type, id_number, first_names, last_names, birth_date, sex, marital_status, nationality, address, phone_mobile, personal_email)
                values (?, 'CEDULA', ?, ?, ?, ?, ?, 'SOLTERO', 'ECU', 'Cuenca, Azuay', ?, ?)""", id, p.idNumber(), p.firstNames(), p.lastNames(),
                Date.valueOf(p.birthDate()), p.sex(), p.phone(), "persona." + p.idNumber() + "@correo-ficticio.ec");
        return id;
    }

    private UUID userAccount(UUID personId, String idNumber, String role) {
        UUID id = UuidV7.next();
        jt.update("""
                insert into user_account (id, person_id, username, password_hash, must_change_password, password_changed_at, password_expires_at,
                                          institutional_email, institutional_email_verified)
                values (?, ?, ?, ?, false, now(), now() + interval '180 days', ?, true)""",
                id, personId, idNumber, passwordHash, idNumber + "@unisfera.edu.ec");
        jt.update("insert into user_role (id, user_id, role_id) select ?, ?, id from role where code = ?", UuidV7.next(), id, role);
        return id;
    }

    // ------------------------------------------------------------------ becas
    private void scholarshipCatalog() {
        scholarshipType("EXCELENCIA", "Excelencia académica", "Reconoce el promedio sobresaliente (modelo UPS)", "30", "85", "80", "80", "15", "ARANCEL");
        scholarshipType("SOCIOECONOMICA", "Beca socioeconómica", "Para estudiantes de los quintiles 1 y 2 (modelo UDA)", "50", "70", "75", null, null,
                "ARANCEL", "MATRICULA");
        scholarshipType("DEPORTIVA", "Mérito deportivo", "Deportistas de alto rendimiento (LOES art. 77)", "25", "72", "75", null, null, "ARANCEL");
        scholarshipType("DISCAPACIDAD", "Inclusión por discapacidad", "Personas con discapacidad (LOES art. 77)", "40", "70", "70", null, null,
                "ARANCEL", "MATRICULA");
        byte[] pdf = samplePdf();
        try {
            Path dir = Path.of(props.storagePath()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            sampleFileId = UuidV7.next();
            Files.write(dir.resolve(sampleFileId + ".pdf"), pdf);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        jt.update("""
                insert into stored_file (id, original_name, stored_name, content_type, size_bytes, sha256, purpose)
                values (?, 'respaldo-sintetico.pdf', ?, 'application/pdf', ?, repeat('a', 64), 'SCHOLARSHIP')""", sampleFileId, sampleFileId + ".pdf",
                (long) pdf.length);
    }

    private void scholarshipType(String code, String name, String description, String value, String minAvg, String minAtt, String partialAvg,
                                 String partialValue, String... concepts) {
        UUID id = UuidV7.next();
        jt.update("""
                insert into scholarship_type (id, code, name, description, mode, value, min_average, min_attendance_pct, partial_min_average,
                                              partial_value, renewable)
                values (?, ?, ?, ?, 'PERCENT', ?, ?, ?, ?, ?, true)""", id, code, name, description, new BigDecimal(value), new BigDecimal(minAvg),
                new BigDecimal(minAtt), partialAvg == null ? null : new BigDecimal(partialAvg), partialValue == null ? null : new BigDecimal(partialValue));
        for (String concept : concepts) {
            jt.update("insert into scholarship_type_concept (scholarship_type_id, concept_id) values (?, ?)", id, conceptIds.get(concept));
        }
        scholarshipTypes.put(code, id);
    }

    // ------------------------------------------------------------------ estudiantes
    private void createStudents(int perCohort) {
        List<Object[]> personRows = new ArrayList<>();
        List<Object[]> studentRows = new ArrayList<>();
        List<Object[]> userRows = new ArrayList<>();
        List<Object[]> roleRows = new ArrayList<>();
        for (int cohort = 0; cohort <= HISTORICAL_PERIODS; cohort++) {
            for (int c = 0; c < CAREERS.length; c++) {
                for (int i = 0; i < perCohort; i++) {
                    students.add(newStudent(cohort, c, personRows, studentRows, userRows, roleRows));
                }
            }
        }
        flushStudents(personRows, studentRows, userRows, roleRows);
        Student demo = students.stream().filter(s -> s.cohort == 1 && s.career == 0).findFirst().orElse(students.getFirst());
        demo.ability = 84;
        demoUsers.put("STUDENT", demo.idNumber);
    }

    private Student newStudent(int cohort, int career, List<Object[]> personRows, List<Object[]> studentRows, List<Object[]> userRows,
                               List<Object[]> roleRows) {
        Student s = new Student();
        SyntheticPeople.Person p = people.next(18, 24, today);
        s.id = UuidV7.next();
        s.personId = UuidV7.next();
        s.userId = UuidV7.next();
        s.idNumber = p.idNumber();
        s.career = career;
        s.cohort = cohort;
        s.version = cohort <= 1 ? 0 : 1;
        s.ability = Math.max(52, Math.min(97, 78 + random.nextGaussian() * 8));
        Period cohortPeriod = periods.get(cohort);
        long seq = jdbc.sql("select nextval('student_code_seq')").query(Long.class).single();
        String code = "E" + cohortPeriod.code().substring(0, 4) + String.format("%06d", seq);
        personRows.add(new Object[]{s.personId, p.idNumber(), p.firstNames(), p.lastNames(), Date.valueOf(p.birthDate()), p.sex(), p.phone()});
        studentRows.add(new Object[]{s.id, s.personId, code, careerIds[career], curriculumIds[career][s.version], cohortPeriod.id(),
                code.toLowerCase() + "@unisfera.edu.ec"});
        userRows.add(new Object[]{s.userId, s.personId, p.idNumber(), passwordHash, code.toLowerCase() + "@unisfera.edu.ec"});
        roleRows.add(new Object[]{UuidV7.next(), s.userId});
        return s;
    }

    private void flushStudents(List<Object[]> personRows, List<Object[]> studentRows, List<Object[]> userRows, List<Object[]> roleRows) {
        jt.batchUpdate("""
                insert into person (id, id_type, id_number, first_names, last_names, birth_date, sex, marital_status, nationality, address, phone_mobile)
                values (?, 'CEDULA', ?, ?, ?, ?, ?, 'SOLTERO', 'ECU', 'Cuenca, Azuay', ?)""", personRows);
        jt.batchUpdate("""
                insert into student (id, person_id, code, career_id, curriculum_id, cohort_period_id, institutional_email)
                values (?, ?, ?, ?, ?, ?, ?)""", studentRows);
        jt.batchUpdate("""
                insert into user_account (id, person_id, username, password_hash, must_change_password, password_changed_at, password_expires_at,
                                          institutional_email, institutional_email_verified)
                values (?, ?, ?, ?, false, now(), now() + interval '180 days', ?, true)""", userRows);
        jt.batchUpdate("insert into user_role (id, user_id, role_id) select ?, ?, id from role where code = 'STUDENT'", roleRows);
    }

    // ------------------------------------------------------------------ períodos históricos
    private void historicalPeriod(int p) {
        Period period = periods.get(p);
        Map<Student, List<Subject>> plan = new LinkedHashMap<>();
        for (Student s : students) {
            if (s.cohort > p || s.dropped || !Set.of("ADMITTED", "ENROLLED", "REGULAR").contains(s.status)) {
                continue;
            }
            List<Subject> chosen = chooseSubjects(s);
            if (!chosen.isEmpty()) {
                plan.put(s, chosen);
            }
        }
        Map<UUID, List<UUID>> sectionsBySubject = createSections(period, plan);
        Map<UUID, Integer> sectionLoad = new HashMap<>();
        Map<UUID, List<Object[]>> detailsBySection = new HashMap<>();
        for (Map.Entry<Student, List<Subject>> e : plan.entrySet()) {
            Student s = e.getKey();
            boolean extraordinary = random.nextInt(100) < 8;
            LocalDate day = extraordinary ? period.ordinaryTo().plusDays(1 + random.nextInt(5)) : period.ordinaryFrom().plusDays(random.nextInt(18));
            Instant enrolledAt = day.atTime(9 + random.nextInt(8), random.nextInt(60)).atZone(zone).toInstant();
            UUID enrollmentId = UuidV7.next();
            jt.update("insert into enrollment (id, student_id, period_id, type, enrolled_at, registered_by) values (?, ?, ?, ?, ?, ?)", enrollmentId, s.id,
                    period.id(), extraordinary ? "EXTRAORDINARY" : "ORDINARY", Timestamp.from(enrolledAt), adminUserId);
            enrollmentCount++;
            for (Subject subject : e.getValue()) {
                int attempt = s.attempts.getOrDefault(subject.id(), 0) + 1;
                UUID authorization = null;
                if (attempt == 3) {
                    authorization = UuidV7.next();
                    jt.update("""
                            insert into exception_authorization (id, type, student_id, period_id, subject_id, cause_code, reason, resolution_ref,
                                                                 authorized_by, authorized_at, used_at)
                            values (?, 'THIRD_ENROLLMENT', ?, ?, ?, 'ENFERMEDAD_COMPROBADA', 'Enfermedad debidamente comprobada con certificado médico',
                                    ?, ?, ?, ?)""", authorization, s.id, period.id(), subject.id(), "CF-" + period.code() + "-" + (enrollmentCount % 1000),
                            adminUserId, Timestamp.from(enrolledAt.minusSeconds(86_400)), Timestamp.from(enrolledAt));
                }
                List<UUID> options = sectionsBySubject.get(subject.id());
                UUID section = options.stream().filter(x -> sectionLoad.getOrDefault(x, 0) < SECTION_FILL).findFirst().orElse(options.getLast());
                sectionLoad.merge(section, 1, Integer::sum);
                UUID detailId = UuidV7.next();
                jt.update("""
                        insert into enrollment_detail (id, enrollment_id, student_id, period_id, subject_id, section_id, attempt_number,
                                                       third_enrollment_authorization_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""", detailId, enrollmentId, s.id, period.id(), subject.id(), section, attempt, authorization);
                detailsBySection.computeIfAbsent(section, k -> new ArrayList<>()).add(new Object[]{detailId, s, subject});
                s.attempts.merge(subject.id(), 1, Integer::sum);
            }
            if ("ADMITTED".equals(s.status)) {
                jt.update("update student set status = 'ENROLLED' where id = ?", s.id);
                jt.update("update student set status = 'REGULAR' where id = ?", s.id);
                s.status = "REGULAR";
            }
            bill(s, period, enrollmentId, e.getValue(), extraordinary, day, p == HISTORICAL_PERIODS - 1);
        }
        gradesAndActs(period, detailsBySection);
        if (p == 1 || p == HISTORICAL_PERIODS - 1) {
            scholarshipCall(p);
        }
        followups(p);
        for (Student s : plan.keySet()) {
            if (!s.equals(demoStudent()) && random.nextInt(100) < 4) {
                jt.update("update student set status = 'WITHDRAWN' where id = ?", s.id);
                s.status = "WITHDRAWN";
                s.dropped = true;
            }
        }
    }

    private Student demoStudent() {
        String id = demoUsers.get("STUDENT");
        return students.stream().filter(s -> s.idNumber.equals(id)).findFirst().orElseThrow();
    }

    private List<Subject> chooseSubjects(Student s) {
        List<Subject> candidates = new ArrayList<>();
        for (Subject subject : subjects) {
            if (subject.careerIdx() != s.career || subject.version() != s.version || s.approved.contains(subject.id())) {
                continue;
            }
            int attempts = s.attempts.getOrDefault(subject.id(), 0);
            if (attempts >= 3 || (attempts == 2 && random.nextInt(10) < 3)) {
                continue;
            }
            if (subject.level() > 1 && !s.approved.contains(find(s.career, s.version, subject.level() - 1, subject.column()).id())) {
                continue;
            }
            candidates.add(subject);
        }
        candidates.sort((a, b) -> Integer.compare(s.attempts.getOrDefault(b.id(), 0), s.attempts.getOrDefault(a.id(), 0)) != 0
                ? Integer.compare(s.attempts.getOrDefault(b.id(), 0), s.attempts.getOrDefault(a.id(), 0)) : Integer.compare(a.index(), b.index()));
        return candidates.stream().limit(PER_LEVEL).toList();
    }

    private Map<UUID, List<UUID>> createSections(Period period, Map<Student, List<Subject>> plan) {
        Map<UUID, Integer> demand = new HashMap<>();
        plan.values().forEach(list -> list.forEach(s -> demand.merge(s.id(), 1, Integer::sum)));
        Map<UUID, List<UUID>> result = new HashMap<>();
        for (Map.Entry<UUID, Integer> d : demand.entrySet()) {
            Subject subject = subjectById.get(d.getKey());
            int count = (d.getValue() + SECTION_FILL - 1) / SECTION_FILL;
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(section(period, subject, i, SECTION_CAPACITY));
            }
            result.put(subject.id(), ids);
        }
        return result;
    }

    /** Horario sin choques por construcción: cada asignatura de una malla tiene su franja (día y banda) propia. */
    private UUID section(Period period, Subject subject, int letter, int capacity) {
        UUID id = UuidV7.next();
        int teacher = subject.careerIdx() * TEACHERS_PER_CAREER + (subject.index() + subject.version() * 5) % TEACHERS_PER_CAREER;
        int roomIndex = (subject.careerIdx() * 2 + subject.version()) * PER_LEVEL + Math.min(letter, PER_LEVEL - 1);
        UUID room = letter < PER_LEVEL ? classroomIds.get(roomIndex) : null;
        jt.update("insert into section (id, period_id, subject_id, code, capacity, teacher_id, classroom_id) values (?, ?, ?, ?, ?, ?, ?)", id, period.id(),
                subject.id(), String.valueOf((char) ('A' + letter)), capacity, teacherIds.get(teacher), room);
        int weekday = 1 + subject.index() % 5;
        int band = subject.index() / 5;
        LocalTime start = LocalTime.of(7 + band * 2 + (letter >= PER_LEVEL ? 6 : 0), 0);
        jt.update("insert into schedule_block (id, section_id, weekday, start_time, end_time, classroom_id) values (?, ?, ?, ?, ?, ?)", UuidV7.next(), id,
                weekday, Time.valueOf(start), Time.valueOf(start.plusHours(2)), room);
        return id;
    }

    // ------------------------------------------------------------------ notas y actas
    private void gradesAndActs(Period period, Map<UUID, List<Object[]>> detailsBySection) {
        BigDecimal passing = parameters.decimal(ParameterKeys.PASSING_GRADE);
        BigDecimal minAttendance = parameters.decimal(ParameterKeys.MIN_ATTENDANCE);
        GradeMath.Scale scale = new GradeMath.Scale(parameters.decimal(ParameterKeys.SCALE_MIN), parameters.decimal(ParameterKeys.SCALE_MAX), passing, minAttendance);
        List<GradeMath.Component> components = new ArrayList<>();
        for (int i = 0; i < COMPONENTS.length; i++) {
            components.add(new GradeMath.Component(period.components().get(i), COMPONENTS[i], WEIGHTS[i]));
        }
        Instant gradedAt = period.classesEnd().atTime(18, 0).atZone(zone).toInstant();
        for (Map.Entry<UUID, List<Object[]>> e : detailsBySection.entrySet()) {
            List<ActHasher.Line> lines = new ArrayList<>();
            List<Object[]> gradeRows = new ArrayList<>();
            for (Object[] d : e.getValue()) {
                UUID detail = (UUID) d[0];
                Student s = (Student) d[1];
                Subject subject = (Subject) d[2];
                if (random.nextInt(100) < 2) {
                    withdraw(detail, period);
                    s.attempts.merge(subject.id(), -1, Integer::sum);
                    continue;
                }
                Map<UUID, BigDecimal> grades = new HashMap<>();
                for (GradeMath.Component c : components) {
                    double v = Math.max(0, Math.min(100, s.ability + random.nextGaussian() * 9 - (subject.level() - 1) * 1.5));
                    BigDecimal value = BigDecimal.valueOf(Math.round(v * 2) / 2.0).setScale(2, RoundingMode.HALF_UP);
                    grades.put(c.id(), value);
                    gradeRows.add(new Object[]{UuidV7.next(), detail, c.id(), value, Timestamp.from(gradedAt)});
                }
                BigDecimal attendance = random.nextInt(100) < 3 ? BigDecimal.valueOf(55 + random.nextInt(19))
                        : BigDecimal.valueOf(80 + random.nextInt(21));
                BigDecimal fin = GradeMath.finalGrade(components, grades).orElseThrow();
                GradeMath.Result result = GradeMath.result(fin, attendance.setScale(2), scale);
                lines.add(new ActHasher.Line(detail, grades, fin, attendance.setScale(2), result.name()));
                if (result == GradeMath.Result.APPROVED) {
                    s.approved.add(subject.id());
                }
            }
            jt.batchUpdate("insert into grade (id, enrollment_detail_id, component_id, value, recorded_at) values (?, ?, ?, ?, ?)", gradeRows);
            if (lines.isEmpty()) {
                continue;
            }
            UUID act = UuidV7.next();
            jt.update("insert into grade_act (id, section_id, period_id, type) values (?, ?, ?, 'ORIGINAL')", act, e.getKey(), period.id());
            List<Object[]> lineRows = new ArrayList<>();
            for (ActHasher.Line l : lines) {
                StringBuilder json = new StringBuilder("{");
                l.componentGrades().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(g -> {
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append('"').append(g.getKey()).append("\":").append(g.getValue().toPlainString());
                });
                json.append('}');
                lineRows.add(new Object[]{UuidV7.next(), act, json.toString(), l.finalGrade(), l.attendancePct(), passing, minAttendance, l.result(),
                        l.enrollmentDetailId()});
            }
            jt.batchUpdate("""
                    insert into grade_act_line (id, act_id, enrollment_detail_id, student_id, subject_id, component_grades, final_grade, attendance_pct,
                                                passing_grade, min_attendance_pct, result)
                    select ?, ?, d.id, d.student_id, d.subject_id, cast(? as jsonb), ?, ?, ?, ?, ? from enrollment_detail d where d.id = ?""", lineRows);
            String hash = ActHasher.hash(e.getKey(), lines);
            Instant closedAt = period.classesEnd().plusDays(5).atTime(17, 0).atZone(zone).toInstant();
            jt.update("update grade_act set status = 'CLOSED', content_hash = ?, teacher_signature = ?, closed_by = ?, closed_at = ? where id = ?", hash,
                    crypto.sign(hash + "|" + adminUserId + "|" + closedAt), adminUserId, Timestamp.from(closedAt), act);
        }
    }

    private void withdraw(UUID detail, Period period) {
        jt.update("""
                insert into withdrawal (id, enrollment_detail_id, type, reason, status, requested_at, decided_at, decided_by, decision_note)
                values (?, ?, 'VOLUNTARY', 'Retiro voluntario por carga laboral', 'APPROVED', ?, ?, ?, 'Dentro del plazo')""", UuidV7.next(), detail,
                Timestamp.from(period.classesStart().plusDays(10).atStartOfDay(zone).toInstant()),
                Timestamp.from(period.classesStart().plusDays(12).atStartOfDay(zone).toInstant()), adminUserId);
        jt.update("update enrollment_detail set status = 'WITHDRAWN' where id = ?", detail);
    }

    // ------------------------------------------------------------------ cartera
    private void bill(Student s, Period period, UUID enrollmentId, List<Subject> chosen, boolean extraordinary, LocalDate day, boolean lastHistorical) {
        BigDecimal credits = chosen.stream().map(Subject::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ChargeCalculator.Tariff> tariffs = List.of(
                tariffFor("MATRICULA", "ENROLLMENT", null, period),
                tariffFor("ARANCEL", "TUITION", careerIds[s.career], period));
        ChargeCalculator.Tariff surcharge = new ChargeCalculator.Tariff(null, conceptIds.get("RECARGO_EXTRA"), "RECARGO_EXTRA",
                "Recargo por matrícula extraordinaria", "SURCHARGE", ChargeCalculator.CalcMode.FLAT, BigDecimal.ZERO, false, BigDecimal.ZERO);
        List<ChargeCalculator.DiscountSource> discounts = new ArrayList<>();
        if (s.award != null) {
            var award = jdbc.sql("select a.value, a.type_id, a.status from scholarship_award a where a.id = :a").param("a", s.award)
                    .query((rs, n) -> new Object[]{rs.getBigDecimal(1), rs.getObject(2, UUID.class), rs.getString(3)}).single();
            if ("ACTIVE".equals(award[2])) {
                Set<String> covered = Set.copyOf(jdbc.sql("""
                                select c.code from scholarship_type_concept t join charge_concept c on c.id = t.concept_id where t.scholarship_type_id = :t""")
                        .param("t", award[1]).query(String.class).list());
                discounts.add(new ChargeCalculator.DiscountSource("SCHOLARSHIP", s.award, "PERCENT", (BigDecimal) award[0], covered));
            }
        }
        List<ChargeCalculator.ChargeLine> lines = ChargeCalculator.calculate(new ChargeCalculator.Input(tariffs, credits, chosen.size(), extraordinary,
                parameters.decimal(ParameterKeys.EXTRAORDINARY_SURCHARGE), surcharge, discounts, parameters.list(ParameterKeys.DISCOUNT_PRECEDENCE), currency));
        Timestamp at = Timestamp.from(day.atTime(12, 0).atZone(zone).toInstant());
        BigDecimal total = BigDecimal.ZERO;
        for (ChargeCalculator.ChargeLine line : lines) {
            UUID charge = UuidV7.next();
            jt.update("""
                    insert into charge (id, student_id, period_id, enrollment_id, concept_id, tariff_id, description, quantity, unit_amount, base_amount,
                                        discount_amount, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", charge, s.id, period.id(), enrollmentId, line.conceptId(), line.tariffId(), line.description(),
                    line.quantity(), line.unitAmount(), line.base(), line.discountTotal(), at);
            entry(s.id, period.id(), "CHARGE", 1, line.base(), charge, null, null, line.description(), at);
            for (ChargeCalculator.DiscountLine d : line.discounts()) {
                jt.update("""
                        insert into discount_applied (id, charge_id, student_id, period_id, origin_type, origin_id, percentage, amount, precedence)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), charge, s.id, period.id(), d.originType(), d.originId(), d.percentage(), d.amount(),
                        d.precedence());
                entry(s.id, period.id(), "DISCOUNT", -1, d.amount(), charge, null, null, "Descuento por beca · " + line.description(), at);
            }
            total = total.add(line.net());
        }
        UUID plan = UuidV7.next();
        int installments = parameters.integer(ParameterKeys.INSTALLMENTS);
        jt.update("insert into payment_plan (id, student_id, period_id, enrollment_id, installments_count, total) values (?, ?, ?, ?, ?, ?)", plan, s.id,
                period.id(), enrollmentId, installments, total);
        List<InstallmentPlanner.Planned> planned = InstallmentPlanner.plan(total, installments,
                day.plusDays(parameters.integer(ParameterKeys.FIRST_INSTALLMENT_DAYS)), parameters.integer(ParameterKeys.INSTALLMENT_INTERVAL_DAYS), currency);
        boolean defaulter = lastHistorical && !s.equals(demoStudent()) && random.nextInt(100) < 5;
        for (InstallmentPlanner.Planned p : planned) {
            UUID installment = UuidV7.next();
            jt.update("insert into installment (id, plan_id, student_id, period_id, number, due_date, amount_due) values (?, ?, ?, ?, ?, ?, ?)", installment,
                    plan, s.id, period.id(), p.number(), Date.valueOf(p.dueDate()), p.amount());
            boolean unpaid = defaulter && p.number() >= installments - 1;
            if (unpaid) {
                accrueLateFee(s, period, installment, p, today);
                continue;
            }
            boolean late = random.nextInt(100) < 15;
            LocalDate paidOn = late ? p.dueDate().plusDays(10 + random.nextInt(30)) : p.dueDate().minusDays(random.nextInt(5));
            if (paidOn.isAfter(today)) {
                continue;
            }
            BigDecimal fee = late ? accrueLateFee(s, period, installment, p, paidOn) : BigDecimal.ZERO;
            pay(s, period, installment, p, p.amount().add(fee), fee, paidOn);
        }
    }

    private ChargeCalculator.Tariff tariffFor(String concept, String kind, UUID career, Period period) {
        return jdbc.sql("""
                        select id, amount, calc_mode from tariff where concept_id = :c and period_id = :p and career_id is not distinct from :car and status = 'ACTIVE'""")
                .param("c", conceptIds.get(concept)).param("p", period.id()).param("car", career)
                .query((rs, n) -> new ChargeCalculator.Tariff(rs.getObject(1, UUID.class), conceptIds.get(concept), concept,
                        "MATRICULA".equals(concept) ? "Matrícula" : "Arancel por crédito", kind, ChargeCalculator.CalcMode.valueOf(rs.getString(3)),
                        rs.getBigDecimal(2), true, BigDecimal.ZERO))
                .single();
    }

    /** RN-26: la mora usa la tasa y la gracia vigentes al vencimiento de la cuota. */
    private BigDecimal accrueLateFee(Student s, Period period, UUID installment, InstallmentPlanner.Planned p, LocalDate asOf) {
        Instant due = p.dueDate().atStartOfDay(zone).toInstant();
        LateFeeCalculator.RuleAtDueDate rule = new LateFeeCalculator.RuleAtDueDate(parameters.decimal(ParameterKeys.LATE_FEE_MONTHLY_RATE, due),
                parameters.integer(ParameterKeys.GRACE_DAYS, due));
        BigDecimal fee = LateFeeCalculator.accruedAt(p.dueDate(), asOf, p.amount(), rule, currency);
        if (fee.signum() > 0) {
            jt.update("update installment set late_fee_accrued = ?, last_late_fee_on = ? where id = ?", fee, Date.valueOf(asOf), installment);
            entry(s.id, period.id(), "LATE_FEE", 1, fee, null, installment, null, "Interés por mora (tasa vigente al " + p.dueDate() + ")",
                    Timestamp.from(asOf.atTime(1, 15).atZone(zone).toInstant()));
        }
        return fee;
    }

    private void pay(Student s, Period period, UUID installment, InstallmentPlanner.Planned p, BigDecimal amount, BigDecimal fee, LocalDate paidOn) {
        UUID payment = UuidV7.next();
        int roll = random.nextInt(100);
        String method = roll < 60 ? "GATEWAY" : roll < 90 ? "TRANSFER" : "CASH";
        Timestamp at = Timestamp.from(paidOn.atTime(10 + random.nextInt(9), random.nextInt(60)).atZone(zone).toInstant());
        jt.update("""
                insert into payment (id, student_id, amount, currency, method, gateway, gateway_reference, status, idempotency_key, confirmed_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)""", payment, s.id, amount, currency.getCurrencyCode(), method,
                "GATEWAY".equals(method) ? "SIMULATED" : "MANUAL", "SEED-" + payment, "seed-" + payment, at, at);
        List<PaymentAllocator.Allocation> allocations = PaymentAllocator.allocate(amount, List.of(new PaymentAllocator.OpenInstallment(installment,
                p.number(), p.dueDate(), p.amount(), fee, BigDecimal.ZERO, BigDecimal.ZERO)), parameters.list(ParameterKeys.ALLOCATION_ORDER));
        for (PaymentAllocator.Allocation a : allocations) {
            jt.update("insert into payment_allocation (id, payment_id, installment_id, kind, amount, created_at) values (?, ?, ?, ?, ?, ?)", UuidV7.next(),
                    payment, installment, a.kind(), a.amount(), at);
        }
        jt.update("update installment set amount_paid = ?, status = 'PAID' where id = ?", amount, installment);
        entry(s.id, period.id(), "PAYMENT", -1, amount, null, null, payment, "Pago recibido", at);
        invoice(payment, s, amount, paidOn, at);
        paymentCount++;
    }

    private void invoice(UUID payment, Student s, BigDecimal amount, LocalDate date, Timestamp at) {
        invoiceSequence++;
        String key = AccessKeyGenerator.generate(date, "01", "0190099999001", "1", "001", "001", invoiceSequence,
                String.format("%08d", random.nextInt(100_000_000)));
        jt.update("""
                insert into invoice (id, payment_id, establishment, emission_point, sequential, access_key, environment, issue_date, buyer_id_type, buyer_id,
                                     buyer_name, subtotal_zero, subtotal_taxed, tax_amount, total, sri_status, authorization_number, authorized_at, xml, created_at)
                select ?, ?, '001', '001', ?, ?, '1', ?, '05', p.id_number, p.first_names || ' ' || p.last_names, ?, 0, 0, ?, 'AUTHORIZED', ?, ?,
                       '<factura id="comprobante" version="1.1.0"><infoTributaria><claveAcceso>' || ? || '</claveAcceso></infoTributaria></factura>', ?
                from person p where p.id = ?""", UuidV7.next(), payment, invoiceSequence, key, Date.valueOf(date), amount, amount, key, at, key, at, s.personId);
    }

    private void entry(UUID student, UUID period, String type, int direction, BigDecimal amount, UUID charge, UUID installment, UUID payment, String description,
                       Timestamp at) {
        jt.update("""
                insert into account_entry (id, student_id, period_id, entry_type, direction, amount, charge_id, installment_id, payment_id, description,
                                           occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), student, period, type, direction, amount, charge, installment, payment,
                description.length() > 200 ? description.substring(0, 200) : description, at);
    }

    // ------------------------------------------------------------------ becas: convocatoria, comité, seguimiento
    private void scholarshipCall(int p) {
        Period period = periods.get(p);
        Period start = periods.get(p + 1);
        List<String> codes = new ArrayList<>(scholarshipTypes.keySet());
        for (String code : codes) {
            UUID call = UuidV7.next();
            Instant opens = period.classesStart().plusDays(20).atStartOfDay(zone).toInstant();
            Instant closes = period.classesStart().plusDays(50).atStartOfDay(zone).toInstant();
            jt.update("""
                    insert into scholarship_call (id, type_id, period_id, name, quota, opens_at, closes_at, results_by, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')""", call, scholarshipTypes.get(code), start.id(), "Convocatoria " + code.toLowerCase() + " " + start.code(),
                    30, Timestamp.from(opens), Timestamp.from(closes), Date.valueOf(period.classesStart().plusDays(70)));
            UUID doc1 = UuidV7.next();
            UUID doc2 = UuidV7.next();
            jt.update("insert into call_required_document (id, call_id, code, name, mandatory) values (?, ?, 'SOLICITUD', 'Solicitud firmada', true)", doc1, call);
            jt.update("insert into call_required_document (id, call_id, code, name, mandatory) values (?, ?, 'RESPALDO', 'Documento de respaldo', true)", doc2, call);
            UUID crit1 = UuidV7.next();
            UUID crit2 = UuidV7.next();
            jt.update("insert into evaluation_criterion (id, call_id, name, max_score, weight, sort_order) values (?, ?, 'Situación socioeconómica', 10, 60, 1)",
                    crit1, call);
            jt.update("insert into evaluation_criterion (id, call_id, name, max_score, weight, sort_order) values (?, ?, 'Rendimiento académico', 100, 40, 2)",
                    crit2, call);
            UUID resolution = UuidV7.next();
            jt.update("insert into committee_resolution (id, call_id, act_number, session_date, summary) values (?, ?, ?, ?, ?)", resolution, call,
                    "CB-" + start.code() + "-" + code, Date.valueOf(period.classesStart().plusDays(60)), "Resolución del Comité de Becas · " + code);
            for (String[] m : new String[][]{{"Presidencia del Comité", "Vicerrectorado"}, {"Dirección de Bienestar", "Bienestar"},
                    {"Representación estudiantil", "Estudiantes"}}) {
                jt.update("insert into committee_member (id, resolution_id, member_name, member_role) values (?, ?, ?, ?)", UuidV7.next(), resolution, m[0], m[1]);
            }
            for (Student s : students) {
                if (s.cohort > p || s.dropped || s.award != null || random.nextInt(100) >= 4) {
                    continue;
                }
                if ("EXCELENCIA".equals(code) && s.ability < 84) {
                    continue;
                }
                UUID application = UuidV7.next();
                jt.update("insert into scholarship_application (id, call_id, student_id, statement, created_at) values (?, ?, ?, ?, ?)", application, call, s.id,
                        "Solicito la beca para continuar mis estudios con normalidad.", Timestamp.from(opens.plusSeconds(86_400)));
                for (UUID doc : List.of(doc1, doc2)) {
                    jt.update("insert into application_document (id, application_id, required_document_id, file_id) values (?, ?, ?, ?)", UuidV7.next(),
                            application, doc, sampleFileId);
                }
                jt.update("update scholarship_application set status = 'SUBMITTED', submitted_at = ? where id = ?", Timestamp.from(opens.plusSeconds(172_800)),
                        application);
                jt.update("update scholarship_application set status = 'UNDER_REVIEW' where id = ?", application);
                BigDecimal socio = BigDecimal.valueOf(3 + random.nextInt(8));
                BigDecimal academic = BigDecimal.valueOf(Math.min(100, Math.round(s.ability)));
                jt.update("insert into application_score (id, application_id, criterion_id, score) values (?, ?, ?, ?)", UuidV7.next(), application, crit1, socio);
                jt.update("insert into application_score (id, application_id, criterion_id, score) values (?, ?, ?, ?)", UuidV7.next(), application, crit2, academic);
                BigDecimal total = WelfareRules.totalScore(List.of(new WelfareRules.Criterion(crit1, BigDecimal.TEN, new BigDecimal("60")),
                                new WelfareRules.Criterion(crit2, new BigDecimal("100"), new BigDecimal("40"))),
                        Map.of(crit1, socio, crit2, academic));
                boolean approved = total.compareTo(new BigDecimal("60")) >= 0 || s.equals(demoStudent());
                jt.update("update scholarship_application set total_score = ?, status = ?, committee_resolution_id = ?, decided_at = ? where id = ?", total,
                        approved ? "APPROVED" : "REJECTED", resolution, Timestamp.from(period.classesStart().plusDays(60).atStartOfDay(zone).toInstant()), application);
                if (approved) {
                    s.award = UuidV7.next();
                    jt.update("""
                            insert into scholarship_award (id, application_id, student_id, type_id, start_period_id, mode, value, conditions)
                            select ?, ?, ?, t.id, ?, t.mode, t.value, 'Mantener promedio ≥ ' || t.min_average || ' y asistencia ≥ ' || t.min_attendance_pct || ' %'
                            from scholarship_type t where t.id = ?""", s.award, application, s.id, start.id(), scholarshipTypes.get(code));
                }
            }
            jt.update("update scholarship_call set status = 'RESOLVED' where id = ?", call);
        }
    }

    private void followups(int p) {
        Period period = periods.get(p);
        for (Student s : students) {
            if (s.award == null) {
                continue;
            }
            var award = jdbc.sql("""
                            select a.status, sp.classes_start, t.min_average, t.min_attendance_pct, t.partial_min_average, t.partial_value
                            from scholarship_award a join academic_period sp on sp.id = a.start_period_id join scholarship_type t on t.id = a.type_id
                            where a.id = :a""").param("a", s.award)
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getDate(2).toLocalDate(), new WelfareRules.MaintenanceRule(rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6))})
                    .single();
            if (!"ACTIVE".equals(award[0]) || ((LocalDate) award[1]).isAfter(period.classesStart())) {
                continue;
            }
            var stats = jdbc.sql("""
                            select sum(sb.total_hours * l.final_grade) / nullif(sum(sb.total_hours), 0), avg(l.attendance_pct)
                            from grade_act_line l join grade_act a on a.id = l.act_id and a.status = 'CLOSED' join subject sb on sb.id = l.subject_id
                            where l.student_id = :s and a.period_id = :p""").param("s", s.id).param("p", period.id())
                    .query((rs, n) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
            if (stats[0] == null) {
                continue;
            }
            BigDecimal average = stats[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal attendance = stats[1].setScale(2, RoundingMode.HALF_UP);
            WelfareRules.Maintenance m = WelfareRules.maintenance((WelfareRules.MaintenanceRule) award[2], average, attendance);
            UUID followup = UuidV7.next();
            jt.update("""
                    insert into scholarship_followup (id, award_id, period_id, average, attendance_pct, complies, outcome, reason, evaluated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", followup, s.award, period.id(), average, attendance, m.complies(), m.outcome().name(), m.reason(),
                    Timestamp.from(period.classesEnd().plusDays(20).atStartOfDay(zone).toInstant()));
            if (m.outcome() == WelfareRules.Outcome.SUSPENDED) {
                jt.update("update scholarship_award set status = 'SUSPENDED' where id = ?", s.award);
                if (random.nextBoolean()) {
                    jt.update("insert into scholarship_appeal (id, followup_id, statement) values (?, ?, ?)", UuidV7.next(), followup,
                            "Solicito reconsiderar la suspensión: tuve una calamidad doméstica documentada durante el período.");
                }
            } else if (m.outcome() == WelfareRules.Outcome.REDUCED) {
                jt.update("update scholarship_award a set value = t.partial_value from scholarship_type t where t.id = a.type_id and a.id = ?", s.award);
            }
        }
    }

    private void welfareCases() {
        int recorded = 0;
        for (Student s : students) {
            if (s.dropped || random.nextInt(100) >= 3) {
                continue;
            }
            UUID consent = UuidV7.next();
            jt.update("insert into consent (id, person_id, purpose, text_version) values (?, ?, 'WELFARE_SENSITIVE', 'LOPDP-2026-1')", consent, s.personId);
            String[][] cases = {{"PSYCHOLOGY", "Acompañamiento por ansiedad ante evaluaciones", "Se acuerdan técnicas de respiración y seguimiento quincenal."},
                    {"HEALTH", "Control médico por cefalea recurrente", "Se recomienda evaluación oftalmológica."},
                    {"ORIENTATION", "Orientación vocacional y organización del tiempo", "Plan semanal de estudio elaborado con el estudiante."}};
            String[] c = cases[random.nextInt(cases.length)];
            jt.update("""
                    insert into welfare_attention (id, student_id, category, attended_at, professional_user_id, summary_enc, details_enc, follow_up_required,
                                                   consent_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), s.id, c[0],
                    Timestamp.from(today.minusDays(10 + random.nextInt(120)).atTime(11, 0).atZone(zone).toInstant()), welfareUserId,
                    crypto.encrypt(c[1], "welfare_attention.summary"), crypto.encrypt(c[2], "welfare_attention.details"), random.nextBoolean(), consent);
            recorded++;
        }
        log.info("Atenciones de bienestar sintéticas registradas (cifradas): {}", recorded);
    }

    // ------------------------------------------------------------------ período actual (con los servicios reales)
    private void currentPeriod(int loadTestStudents) {
        Period current = periods.get(HISTORICAL_PERIODS);
        Map<Student, List<Subject>> plan = new LinkedHashMap<>();
        for (Student s : students) {
            if (!s.dropped && Set.of("ADMITTED", "ENROLLED", "REGULAR").contains(s.status)) {
                List<Subject> chosen = chooseSubjects(s);
                if (!chosen.isEmpty()) {
                    plan.put(s, chosen);
                }
            }
        }
        Map<UUID, List<UUID>> sections = tx.execute(st -> {
            Map<UUID, List<UUID>> created = createSections(current, plan);
            // Día de matrícula: los paralelos de primer nivel tienen cupo limitado frente a los aspirantes de la prueba de carga
            if (loadTestStudents > 0) {
                for (int c = 0; c < CAREERS.length; c++) {
                    for (int k = 0; k < PER_LEVEL; k++) {
                        Subject first = find(c, 1, 1, k);
                        created.computeIfAbsent(first.id(), x -> new ArrayList<>()).add(section(current, first, 1 + created.getOrDefault(first.id(),
                                List.of()).size(), SECTION_CAPACITY));
                    }
                }
            }
            return created;
        });
        Map<UUID, Integer> load = new HashMap<>();
        int enrolledNow = 0;
        for (Map.Entry<Student, List<Subject>> e : plan.entrySet()) {
            Student s = e.getKey();
            boolean leaveForEnrollmentDay = s.cohort == HISTORICAL_PERIODS || (!s.equals(demoStudent()) && random.nextInt(100) < 30);
            if (leaveForEnrollmentDay) {
                continue;
            }
            List<UUID> chosenSections = new ArrayList<>();
            for (Subject subject : e.getValue()) {
                List<UUID> options = sections.get(subject.id());
                UUID section = options.stream().filter(x -> load.getOrDefault(x, 0) < SECTION_FILL).findFirst().orElse(options.getLast());
                load.merge(section, 1, Integer::sum);
                chosenSections.add(section);
            }
            try {
                if (s.attempts.values().stream().anyMatch(a -> a == 2)) {
                    for (Subject subject : e.getValue()) {
                        if (s.attempts.getOrDefault(subject.id(), 0) == 2) {
                            tx.executeWithoutResult(st -> jt.update("""
                                    insert into exception_authorization (id, type, student_id, period_id, subject_id, cause_code, reason, authorized_by)
                                    values (?, 'THIRD_ENROLLMENT', ?, ?, ?, 'CALAMIDAD_DOMESTICA', 'Calamidad doméstica debidamente documentada', ?)""",
                                    UuidV7.next(), s.id, current.id(), subject.id(), adminUserId));
                        }
                    }
                }
                enrollment.enroll(s.id, chosenSections);
                enrolledNow++;
                enrollmentCount++;
            } catch (RuntimeException ex) {
                log.debug("Matrícula demo omitida para {}: {}", s.idNumber, ex.getMessage());
            }
        }
        log.info("Matrículas del período {} registradas con el motor real: {}", current.code(), enrolledNow);
        tx.executeWithoutResult(st -> demoTeacherSections(current));
        if (loadTestStudents > 0) {
            tx.executeWithoutResult(st -> {
                List<Object[]> personRows = new ArrayList<>();
                List<Object[]> studentRows = new ArrayList<>();
                List<Object[]> userRows = new ArrayList<>();
                List<Object[]> roleRows = new ArrayList<>();
                for (int i = 0; i < loadTestStudents; i++) {
                    Student s = newStudent(HISTORICAL_PERIODS, i % CAREERS.length, personRows, studentRows, userRows, roleRows);
                    s.version = 1;
                    students.add(s);
                }
                flushStudents(personRows, studentRows, userRows, roleRows);
            });
        }
    }

    /** El docente demo tiene paralelos del período en curso con notas parciales y asistencia registrada. */
    private void demoTeacherSections(Period current) {
        List<UUID> sections = jdbc.sql("select id from section where teacher_id = :t and period_id = :p and enrolled_count > 0")
                .param("t", demoTeacherId).param("p", current.id()).query(UUID.class).list();
        for (UUID section : sections.stream().limit(2).toList()) {
            List<UUID> details = jdbc.sql("select id from enrollment_detail where section_id = :s and status = 'ENROLLED'").param("s", section)
                    .query(UUID.class).list();
            for (UUID d : details) {
                BigDecimal v = BigDecimal.valueOf(60 + random.nextInt(40));
                jt.update("insert into grade (id, enrollment_detail_id, component_id, value) values (?, ?, ?, ?)", UuidV7.next(), d,
                        current.components().getFirst(), v);
            }
            for (int i = 0; i < 4; i++) {
                UUID session = UuidV7.next();
                jt.update("insert into class_session (id, section_id, period_id, session_date, starts_at, topic) values (?, ?, ?, ?, ?, ?)", session, section,
                        current.id(), Date.valueOf(current.classesStart().minusDays(i + 1)), Time.valueOf(LocalTime.of(7, 0)), "Sesión de nivelación " + (i + 1));
                for (UUID d : details) {
                    jt.update("""
                            insert into attendance (id, period_id, class_session_id, enrollment_detail_id, status) values (?, ?, ?, ?, ?)""",
                            UuidV7.next(), current.id(), session, d, random.nextInt(100) < 85 ? "PRESENT" : "ABSENT");
                }
            }
        }
    }

    private void exportLoadTestUsers() {
        List<String> ids = jdbc.sql("""
                        select p.id_number from student s join person p on p.id = s.person_id
                        where s.status = 'ADMITTED' and not exists (select 1 from enrollment e where e.student_id = s.id) order by s.code""")
                .query(String.class).list();
        try {
            Path dir = Path.of(props.storagePath()).toAbsolutePath().normalize().resolve("seed");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("students.csv"), "username\n" + String.join("\n", ids) + "\n");
            log.info("Usuarios para la prueba de carga: {} (storage/seed/students.csv)", ids.size());
        } catch (IOException e) {
            log.warn("No se pudo exportar students.csv: {}", e.getMessage());
        }
    }

    private Subject find(int career, int version, int level, int column) {
        return subjects.stream().filter(s -> s.careerIdx() == career && s.version() == version && s.level() == level && s.column() == column)
                .findFirst().orElseThrow();
    }

    private static byte[] samplePdf() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        org.openpdf.text.Document doc = new org.openpdf.text.Document();
        org.openpdf.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new org.openpdf.text.Paragraph("Documento de respaldo sintético · UNISFERA (datos ficticios de demostración)"));
        doc.close();
        return out.toByteArray();
    }
}
