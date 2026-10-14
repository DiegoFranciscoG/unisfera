package ec.unisfera.seed;

import static org.assertj.core.api.Assertions.assertThat;

import ec.unisfera.support.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T-09 (seeds): el escenario completo se carga sin errores con todas las reglas de la base activas
 * y es coherente: quien reprobó tiene segunda matrícula, quien tiene beca tiene descuento y
 * seguimiento, y el estado de cuenta de cada estudiante cuadra.
 */
class DemoSeedIT extends IntegrationTest {

    @Autowired
    DemoDataGenerator generator;

    @Test
    void t09_seedLoadsACoherentScenario() {
        if (!generator.alreadySeeded()) {
            DemoDataGenerator.Result r = generator.generate(150, 20, "Demo-" + randomBase64(12));
            assertThat(r.subjects()).isEqualTo(180);
            assertThat(r.periods()).isEqualTo(5);
            assertThat(r.teachers()).isEqualTo(60);
            assertThat(r.demoUsers()).containsKeys("STUDENT", "TEACHER", "FINANCE", "WELFARE", "ACADEMIC_ADMIN", "REGISTRAR", "AUDITOR");
        }
        long secondAttempts = jdbc.sql("select count(*) from enrollment_detail where attempt_number = 2").query(Long.class).single();
        long failed = jdbc.sql("select count(*) from v_kardex where result <> 'APPROVED'").query(Long.class).single();
        assertThat(failed).isPositive();
        assertThat(secondAttempts).isPositive();
        long awardsWithoutDiscount = jdbc.sql("""
                        select count(*) from scholarship_award a join academic_period sp on sp.id = a.start_period_id
                        where exists (select 1 from charge c join academic_period cp on cp.id = c.period_id
                                      where c.student_id = a.student_id and cp.classes_start >= sp.classes_start and c.status = 'ACTIVE')
                          and not exists (select 1 from discount_applied d where d.origin_id = a.id)""").query(Long.class).single();
        assertThat(awardsWithoutDiscount).isZero();
        assertThat(jdbc.sql("select count(*) from scholarship_followup").query(Long.class).single()).isPositive();
        long unbalanced = jdbc.sql("""
                        select count(*) from v_account_statement s
                        where charges + late_fees - discounts - payments - credit_notes + reversals <> balance and s.student_id in (select st.id from student st join career c on c.id = st.career_id where c.code in ('SOF','CIV','ADE','CON','PSI','EDU'))""").query(Long.class).single();
        assertThat(unbalanced).isZero();
        BigDecimal ledgerVsInstallments = jdbc.sql("""
                        select coalesce(sum(abs(s.balance - i.pending)), 0) from v_account_statement s
                        join (select student_id, sum(case when status = 'VOID' then 0 else amount_due + late_fee_accrued - amount_paid end) pending
                              from installment group by student_id) i on i.student_id = s.student_id
                        where s.student_id in (select st.id from student st join career c on c.id = st.career_id where c.code in ('SOF','CIV','ADE','CON','PSI','EDU'))""").query(BigDecimal.class).single();
        assertThat(ledgerVsInstallments).isEqualByComparingTo("0");
        assertThat(jdbc.sql("""
                        select count(*) from payment p where p.status = 'CONFIRMED' and p.idempotency_key like 'seed-%'
                          and not exists (select 1 from invoice i where i.payment_id = p.id)""").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from enrollment e join academic_period p on p.id = e.period_id where p.status = 'ENROLLMENT'")
                .query(Long.class).single()).isPositive();
    }
}
