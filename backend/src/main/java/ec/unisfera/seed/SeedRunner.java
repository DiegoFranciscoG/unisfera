package ec.unisfera.seed;

import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Carga el escenario de demostración al arrancar si SEED_DEMO_DATA=true y la base está vacía.
 * Queda en la bitácora de procesos y en la auditoría con el superadministrador como actor.
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final SeedProperties props;
    private final DemoDataGenerator generator;
    private final JdbcClient jdbc;

    public SeedRunner(SeedProperties props, DemoDataGenerator generator, JdbcClient jdbc) {
        this.props = props;
        this.generator = generator;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled() || generator.alreadySeeded()) {
            return;
        }
        if (props.demoPassword() == null || props.demoPassword().length() < 12) {
            throw new IllegalStateException("SEED_DEMO_DATA=true exige DEMO_PASSWORD (mínimo 12 caracteres) para las cuentas de demostración");
        }
        UUID superadmin = jdbc.sql("""
                        select u.id from user_account u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
                        where r.code = 'SUPERADMIN' limit 1""").query(UUID.class).optional().orElse(null);
        UUID processId = UuidV7.next();
        jdbc.sql("insert into process_log (id, process, status, actor_id) values (:id, 'DEMO_SEED', 'STARTED', :a)")
                .param("id", processId).param("a", superadmin).update();
        long started = System.nanoTime();
        log.info("Generando escenario de demostración (semilla {}): {} estudiantes", DemoDataGenerator.SEED, props.students());
        DemoDataGenerator.Result result = RequestContext.runAs(superadmin, "DEMO_SEED",
                () -> generator.generate(props.students(), props.loadTestStudents(), props.demoPassword()));
        jdbc.sql("update process_log set status = 'SUCCEEDED', finished_at = now(), summary = cast(:s as jsonb) where id = :id")
                .param("s", "{\"students\":" + result.students() + ",\"enrollments\":" + result.enrollments() + ",\"payments\":" + result.payments() + "}")
                .param("id", processId).update();
        log.info("Escenario listo en {} s: {} estudiantes, {} docentes, {} asignaturas, {} períodos, {} matrículas, {} pagos",
                (System.nanoTime() - started) / 1_000_000_000L, result.students(), result.teachers(), result.subjects(), result.periods(),
                result.enrollments(), result.payments());
        result.demoUsers().forEach((role, user) -> log.info("Cuenta demo {} → usuario {}", role, user));
    }
}
