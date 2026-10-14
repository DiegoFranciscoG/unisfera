package ec.unisfera.shared.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Marca la transacción en curso con un proceso explícito (por ejemplo, un reingreso: RN-30). */
@Component
public class DbSession {

    private final JdbcClient jdbc;

    public DbSession(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void declareProcess(String processCode) {
        jdbc.sql("select set_config('unisfera.process', :p, true)").param("p", processCode).query(String.class).single();
    }
}
