package ec.unisfera.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.academic.application.EnrollmentService;
import ec.unisfera.finance.application.PaymentService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import ec.unisfera.support.Fixtures;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@Import({Fixtures.class, TestUsers.class})
class EnrollmentFlowIT extends IntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guayaquil");

    @Autowired
    Fixtures fixtures;
    @Autowired
    EnrollmentService enrollment;
    @Autowired
    PaymentService payments;
    @Autowired
    TestUsers users;

    @Test
    void rn02_subjectWithPendingPrerequisiteIsRejected() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID student = fixtures.student(c, p.id());
        UUID sectionC = fixtures.section(p.id(), c.subject("C"), 30);
        assertRule(() -> enrollment.enroll(student, List.of(sectionC)), "RN-02");
        fixtures.approved(student, c.subject("A"));
        enrollment.enroll(student, List.of(sectionC));
    }

    @Test
    void rn02_corequisitesMustBeTakenTogether() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID student = fixtures.student(c, p.id());
        fixtures.approved(student, c.subject("B"));
        UUID sectionD = fixtures.section(p.id(), c.subject("D"), 30, 1, LocalTime.of(7, 0), LocalTime.of(9, 0));
        UUID sectionE = fixtures.section(p.id(), c.subject("E"), 30, 2, LocalTime.of(7, 0), LocalTime.of(9, 0));
        assertRule(() -> enrollment.enroll(student, List.of(sectionD)), "RN-02");
        enrollment.enroll(student, List.of(sectionD, sectionE));
    }

    @Test
    void rn03_databaseRejectsSubjectFromAnotherCurriculumEvenIfTheDomainIsBypassed() {
        Fixtures.Curriculum mine = fixtures.curriculum();
        Fixtures.Curriculum other = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID student = fixtures.student(mine, p.id());
        UUID foreignSection = fixtures.section(p.id(), other.subject("A"), 30);
        assertRule(() -> enrollment.enroll(student, List.of(foreignSection)), "RN-03");
        UUID enrollmentId = insertEnrollment(student, p.id(), "ORDINARY", null);
        assertThatThrownBy(() -> insertDetail(enrollmentId, other.subject("A"), foreignSection, 1, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("RN-03");
    }

    @Test
    void rn06_scheduleClashIsRejectedByDomainAndByTheExclusionConstraint() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID student = fixtures.student(c, p.id());
        UUID a = fixtures.section(p.id(), c.subject("A"), 30, 3, LocalTime.of(8, 0), LocalTime.of(10, 0));
        UUID b = fixtures.section(p.id(), c.subject("B"), 30, 3, LocalTime.of(9, 0), LocalTime.of(11, 0));
        assertRule(() -> enrollment.enroll(student, List.of(a, b)), "RN-06");
        UUID enrollmentId = insertEnrollment(student, p.id(), "ORDINARY", null);
        insertDetail(enrollmentId, c.subject("A"), a, 1, null);
        assertThatThrownBy(() -> insertDetail(enrollmentId, c.subject("B"), b, 1, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ex_student_schedule_clash");
    }

    @Test
    void rn07_thirdAttemptNeedsAuthorizationAndFourthIsImpossible() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period current = fixtures.openPeriod();
        UUID student = fixtures.student(c, current.id());
        pastAttempt(student, c, c.subject("G"), 400);
        pastAttempt(student, c, c.subject("G"), 300);
        UUID section = fixtures.section(current.id(), c.subject("G"), 30);
        assertRule(() -> enrollment.enroll(student, List.of(section)), "RN-07");

        UUID admin = users.create("ACADEMIC_ADMIN").userId();
        jdbc.sql("""
                        insert into exception_authorization (id, type, student_id, subject_id, cause_code, reason, authorized_by)
                        values (:id, 'THIRD_ENROLLMENT', :s, :sub, 'ENFERMEDAD', 'Enfermedad debidamente comprobada', :by)""")
                .param("id", UuidV7.next()).param("s", student).param("sub", c.subject("G")).param("by", admin).update();
        EnrollmentService.Confirmation ok = enrollment.enroll(student, List.of(section));
        assertThat(ok.details().getFirst().attempt()).isEqualTo(3);

        // Cuarta: la base la rechaza aunque alguien salte el dominio
        Fixtures.Period next = fixtures.openPeriod();
        UUID nextSection = fixtures.section(next.id(), c.subject("G"), 30);
        UUID enrollmentId = insertEnrollment(student, next.id(), "ORDINARY", null);
        assertThatThrownBy(() -> insertDetail(enrollmentId, c.subject("G"), nextSection, 4, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("RN-07");
    }

    @Test
    void rn07_withdrawalsDoNotCountAsAttempts() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period current = fixtures.openPeriod();
        UUID student = fixtures.student(c, current.id());
        UUID detail = pastAttempt(student, c, c.subject("H"), 500);
        jdbc.sql("update enrollment_detail set status = 'WITHDRAWN' where id = :d").param("d", detail).update();
        pastAttempt(student, c, c.subject("H"), 450);
        UUID section = fixtures.section(current.id(), c.subject("H"), 30);
        assertThat(enrollment.enroll(student, List.of(section)).details().getFirst().attempt()).isEqualTo(2);
    }

    @Test
    void rn01_outsideTheWindowNothingIsRegistered() {
        Fixtures.Curriculum c = fixtures.curriculum();
        LocalDate today = LocalDate.now(ZONE);
        Fixtures.Period closed = fixtures.period(today.minusDays(40), today.minusDays(30), today.minusDays(25), today.minusDays(20), today.minusDays(15));
        UUID student = fixtures.student(c, closed.id());
        UUID section = fixtures.section(closed.id(), c.subject("A"), 30);
        assertRule(() -> enrollment.enroll(student, List.of(section)), "RN-01");
        assertThatThrownBy(() -> insertEnrollment(student, closed.id(), "ORDINARY", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("RN-01");
    }

    @Test
    void rn08AndRn27_overdueDebtBlocksAndAConfirmedPaymentUnblocksImmediately() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period current = fixtures.openPeriod();
        UUID student = fixtures.student(c, current.id());
        UUID oldDetail = pastAttempt(student, c, c.subject("A"), 200);
        UUID oldEnrollment = jdbc.sql("select enrollment_id from enrollment_detail where id = :d").param("d", oldDetail).query(UUID.class).single();
        UUID plan = UuidV7.next();
        UUID oldPeriod = jdbc.sql("select period_id from enrollment where id = :e").param("e", oldEnrollment).query(UUID.class).single();
        jdbc.sql("insert into payment_plan (id, student_id, period_id, enrollment_id, installments_count, total) values (:id, :s, :p, :e, 1, 120)")
                .param("id", plan).param("s", student).param("p", oldPeriod).param("e", oldEnrollment).update();
        jdbc.sql("insert into installment (id, plan_id, student_id, period_id, number, due_date, amount_due) values (:id, :pl, :s, :p, 1, :d, 120)")
                .param("id", UuidV7.next()).param("pl", plan).param("s", student).param("p", oldPeriod)
                .param("d", Date.valueOf(LocalDate.now(ZONE).minusDays(60))).update();
        UUID section = fixtures.section(current.id(), c.subject("B"), 30);
        assertRule(() -> enrollment.enroll(student, List.of(section)), "RN-08");

        RequestContext.runAs(users.create("FINANCE").userId(), null, () ->
                payments.registerManual(student, new BigDecimal("120.00"), "CASH", "REC-" + UUID.randomUUID(), "idem-" + UUID.randomUUID()));
        assertThat(enrollment.enroll(student, List.of(section)).details()).hasSize(1);
    }

    @Test
    void rn10_annulmentKeepsTheRecordReversesChargesAndFreesTheSeat() {
        Fixtures.Curriculum c = fixtures.curriculum();
        Fixtures.Period p = fixtures.openPeriod();
        UUID student = fixtures.student(c, p.id());
        UUID section = fixtures.section(p.id(), c.subject("A"), 30);
        EnrollmentService.Confirmation done = enrollment.enroll(student, List.of(section));
        BigDecimal balance = balance(student);
        assertThat(balance).isPositive();

        UUID admin = users.create("REGISTRAR").userId();
        RequestContext.runAs(admin, null, () -> {
            enrollment.annul(done.enrollmentId(), "Matrícula registrada con documentación falsa", "RRA art. 72");
            return null;
        });
        assertThat(jdbc.sql("select status from enrollment where id = :e").param("e", done.enrollmentId()).query(String.class).single()).isEqualTo("ANNULLED");
        assertThat(jdbc.sql("select enrolled_count from section where id = :s").param("s", section).query(Integer.class).single()).isZero();
        assertThat(balance(student)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.sql("select count(*) from account_entry where student_id = :s and entry_type = 'REVERSAL'").param("s", student)
                .query(Long.class).single()).isPositive();
        assertThatThrownBy(() -> jdbc.sql("delete from enrollment where id = :e").param("e", done.enrollmentId()).update())
                .hasMessageContaining("LEY-2");
    }

    @Test
    void rn09_extraordinaryEnrollmentAddsTheSurchargeFromTheParameter() {
        Fixtures.Curriculum c = fixtures.curriculum();
        LocalDate today = LocalDate.now(ZONE);
        Fixtures.Period p = fixtures.period(today.minusDays(10), today.minusDays(1), today.plusDays(3), today.plusDays(6), today.plusDays(7));
        UUID student = fixtures.student(c, p.id());
        UUID section = fixtures.section(p.id(), c.subject("A"), 30);
        assertThat(enrollment.enroll(student, List.of(section)).type()).isEqualTo("EXTRAORDINARY");
        BigDecimal surcharge = jdbc.sql("""
                        select ch.base_amount from charge ch join charge_concept cc on cc.id = ch.concept_id
                        where ch.student_id = :s and cc.code = 'RECARGO_EXTRA'""").param("s", student).query(BigDecimal.class).single();
        assertThat(surcharge).isEqualByComparingTo("19.80");
    }

    // ------------------------------------------------------------------ apoyo
    private void assertRule(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private BigDecimal balance(UUID student) {
        return jdbc.sql("select coalesce(sum(direction * amount), 0) from account_entry where student_id = :s").param("s", student)
                .query(BigDecimal.class).single();
    }

    /** Intento previo en un período pasado (matrícula registrada dentro de su ventana histórica). */
    private UUID pastAttempt(UUID student, Fixtures.Curriculum c, UUID subject, int daysAgo) {
        LocalDate start = LocalDate.now(ZONE).minusDays(daysAgo + ThreadLocalRandom.current().nextInt(0, 20));
        Fixtures.Period past = fixtures.period(start, start.plusDays(5), start.plusDays(10), start.plusDays(12), start.plusDays(14));
        UUID section = fixtures.section(past.id(), subject, 30);
        UUID enrollmentId = UuidV7.next();
        jdbc.sql("insert into enrollment (id, student_id, period_id, type, enrolled_at) values (:id, :s, :p, 'ORDINARY', :at)")
                .param("id", enrollmentId).param("s", student).param("p", past.id())
                .param("at", Timestamp.from(start.plusDays(1).atTime(10, 0).atZone(ZONE).toInstant())).update();
        return insertDetail(enrollmentId, subject, section, 1, null);
    }

    private UUID insertEnrollment(UUID student, UUID period, String type, UUID special) {
        UUID id = UuidV7.next();
        jdbc.sql("insert into enrollment (id, student_id, period_id, type, special_authorization_id) values (:id, :s, :p, :t, :sp)")
                .param("id", id).param("s", student).param("p", period).param("t", type).param("sp", special).update();
        return id;
    }

    private UUID insertDetail(UUID enrollmentId, UUID subject, UUID section, int attempt, UUID third) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                        insert into enrollment_detail (id, enrollment_id, student_id, period_id, subject_id, section_id, attempt_number,
                                                       third_enrollment_authorization_id)
                        select :id, e.id, e.student_id, e.period_id, :sub, :sec, :a, :third from enrollment e where e.id = :e""")
                .param("id", id).param("e", enrollmentId).param("sub", subject).param("sec", section).param("a", attempt).param("third", third).update();
        return id;
    }
}
