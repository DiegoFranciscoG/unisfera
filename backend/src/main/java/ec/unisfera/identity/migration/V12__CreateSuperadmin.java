package ec.unisfera.identity.migration;

import ec.unisfera.identity.domain.CedulaValidator;
import ec.unisfera.identity.domain.PasswordPolicy;
import ec.unisfera.identity.security.PasswordHashing;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.UuidV7;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Superadministrador creado por migración. Su contraseña inicial llega solo por variable de
 * entorno (sin valor por defecto), se guarda con Argon2id y debe cambiarse en el primer ingreso.
 */
@Component
public class V12__CreateSuperadmin extends BaseJavaMigration {

    private static final int TEMP_PASSWORD_DAYS = 7;
    private static final int MIN_INITIAL_LENGTH = 12;

    private final AppProperties props;

    public V12__CreateSuperadmin(AppProperties props) {
        this.props = props;
    }

    @Override
    public void migrate(Context context) throws Exception {
        String idNumber = props.superadminIdNumber();
        if (!CedulaValidator.isValid(idNumber)) {
            throw new IllegalStateException("SUPERADMIN_ID_NUMBER debe ser una cédula válida (ficticia)");
        }
        PasswordPolicy.validate(props.superadminInitialPassword(), idNumber, MIN_INITIAL_LENGTH);
        Connection c = context.getConnection();
        UUID personId = UuidV7.next();
        UUID userId = UuidV7.next();
        try (PreparedStatement ps = c.prepareStatement("""
                insert into person (id, id_type, id_number, first_names, last_names, birth_date, sex, nationality)
                values (?, 'CEDULA', ?, 'Administrador', 'del Sistema', date '1990-01-01', 'HOMBRE', 'ECU')""")) {
            ps.setObject(1, personId);
            ps.setString(2, idNumber);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                insert into user_account (id, person_id, username, password_hash, must_change_password, password_expires_at)
                values (?, ?, ?, ?, true, ?)""")) {
            ps.setObject(1, userId);
            ps.setObject(2, personId);
            ps.setString(3, idNumber);
            ps.setString(4, PasswordHashing.encoder().encode(props.superadminInitialPassword()));
            ps.setTimestamp(5, Timestamp.from(Instant.now().plus(TEMP_PASSWORD_DAYS, ChronoUnit.DAYS)));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                insert into user_role (id, user_id, role_id) select ?, ?, id from role where code = 'SUPERADMIN'""")) {
            ps.setObject(1, UuidV7.next());
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
    }
}
