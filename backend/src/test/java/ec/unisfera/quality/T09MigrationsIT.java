package ec.unisfera.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** T-09 · La base se construye desde cero con todas las migraciones y las guardas de LEY-2 quedan en cada tabla. */
class T09MigrationsIT extends IntegrationTest {

    @Test
    void t09_allMigrationsAppliedSuccessfullyFromAnEmptyDatabase() {
        List<String> failed = jdbc.sql("select version from flyway_schema_history where not success").query(String.class).list();
        assertThat(failed).isEmpty();
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where success").query(Long.class).single()).isGreaterThanOrEqualTo(14);
        assertThat(jdbc.sql("""
                        select count(*) from user_account u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
                        where r.code = 'SUPERADMIN' and u.username = :id""").param("id", SUPERADMIN_ID).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void ley2_everyBusinessTableRejectsDelete() {
        List<String> unguarded = jdbc.sql("""
                        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = current_schema() and c.relkind in ('r', 'p') and not c.relispartition
                          and c.relname not like 'flyway%' and c.relname <> 'audit_log'
                          and not exists (select 1 from pg_trigger t where t.tgrelid = c.oid and t.tgname = 'trg_zz_no_delete')""")
                .query(String.class).list();
        assertThat(unguarded).isEmpty();
        assertThatThrownBy(() -> jdbc.sql("delete from parameter").update()).isInstanceOf(DataAccessException.class).hasMessageContaining("LEY-2");
        assertThatThrownBy(() -> jdbc.sql("truncate parameter").update()).isInstanceOf(DataAccessException.class).hasMessageContaining("LEY-2");
        assertThatThrownBy(() -> jdbc.sql("update audit_log set detail = 'x'").update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void ley1_parametersCannotBeOverwrittenOnlyVersioned() {
        assertThatThrownBy(() -> jdbc.sql("update parameter set value = '50' where key = 'grading.passing_grade' and valid_to is null").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("LEY-1");
    }

    @Test
    void sec01_databaseValidatesCedulaCheckDigit() {
        assertThat(jdbc.sql("select is_valid_cedula('1710034065')").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select is_valid_cedula('1710034066')").query(Boolean.class).single()).isFalse();
    }
}
