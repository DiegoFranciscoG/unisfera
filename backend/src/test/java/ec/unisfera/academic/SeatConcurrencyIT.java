package ec.unisfera.academic;

import static org.assertj.core.api.Assertions.assertThat;

import ec.unisfera.academic.application.EnrollmentGate;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.support.Fixtures;
import ec.unisfera.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * T-02 · RN-05: 500 estudiantes intentan a la vez el último paralelo de 30 cupos. Resultado exacto:
 * 30 matriculados, 470 rechazados con mensaje claro, cero sobrecupo. Se repite 20 veces.
 */
class SeatConcurrencyIT extends IntegrationTest {

    private static final int STUDENTS = 500;
    private static final int CAPACITY = 30;

    @Autowired
    Fixtures fixtures;
    @Autowired
    EnrollmentGate enrollment;

    @RepeatedTest(20)
    void rn05_500ThreadsCompeteFor30SeatsAndNobodyOverbooks() throws Exception {
        Fixtures.Curriculum curriculum = fixtures.curriculum();
        Fixtures.Period period = fixtures.openPeriod();
        UUID section = fixtures.section(period.id(), curriculum.subject("A"), CAPACITY);
        List<UUID> students = fixtures.students(curriculum, period.id(), STUDENTS);

        AtomicInteger enrolled = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentHashMap<String, Integer> reasons = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID student : students) {
                pool.submit(() -> {
                    start.await();
                    try {
                        enrollment.enroll(student, List.of(section));
                        enrolled.incrementAndGet();
                    } catch (BusinessRuleException e) {
                        rejected.incrementAndGet();
                        reasons.merge(e.code(), 1, Integer::sum);
                    } catch (DataAccessException e) {
                        rejected.incrementAndGet();
                        reasons.merge(e.getMostSpecificCause().getMessage().contains("RN-05") ? "RN-05" : "OTHER", 1, Integer::sum);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();
        }

        assertThat(enrolled.get()).isEqualTo(CAPACITY);
        assertThat(rejected.get()).isEqualTo(STUDENTS - CAPACITY);
        assertThat(reasons).containsOnlyKeys("RN-05");
        assertThat(jdbc.sql("select enrolled_count from section where id = :s").param("s", section).query(Integer.class).single()).isEqualTo(CAPACITY);
        assertThat(jdbc.sql("select count(*) from enrollment_detail where section_id = :s and status = 'ENROLLED'").param("s", section)
                .query(Long.class).single()).isEqualTo(CAPACITY);
        // Atomicidad: ningún rechazado dejó una matrícula a medias ni rubros huérfanos
        assertThat(jdbc.sql("select count(*) from enrollment where period_id = :p").param("p", period.id()).query(Long.class).single())
                .isEqualTo(CAPACITY);
        assertThat(jdbc.sql("select count(distinct student_id) from charge where period_id = :p").param("p", period.id()).query(Long.class).single())
                .isEqualTo(CAPACITY);
    }
}
