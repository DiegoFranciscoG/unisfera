package ec.unisfera.support;

import ec.unisfera.identity.domain.Totp;
import ec.unisfera.identity.security.CryptoService;
import ec.unisfera.shared.domain.UuidV7;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Crea cuentas de prueba directamente en la base y obtiene tokens recorriendo el flujo real de ingreso. */
@Component
public class TestUsers {

    public record TestUser(UUID userId, UUID personId, String username, String password, String mfaSecret) {
    }

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 400_000 + 100_000);
    public static final String PASSWORD = "Prueba-Segura-" + IntegrationTest.randomBase64(9).replaceAll("[^A-Za-z]", "x");

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final CryptoService crypto;
    private String cachedHash;

    public TestUsers(JdbcClient jdbc, PasswordEncoder encoder, CryptoService crypto) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.crypto = crypto;
    }

    public TestUser create(String... roles) {
        String idNumber = IntegrationTest.TestIds.cedula(2 + (int) (SEQUENCE.get() % 20), SEQUENCE.incrementAndGet());
        UUID personId = UuidV7.next();
        UUID userId = UuidV7.next();
        jdbc.sql("""
                        insert into person (id, id_type, id_number, first_names, last_names, birth_date, sex, nationality)
                        values (:id, 'CEDULA', :n, 'Prueba', :ln, date '2001-05-05', 'MUJER', 'ECU')""")
                .param("id", personId).param("n", idNumber).param("ln", "Usuario " + idNumber).update();
        boolean mfa = requiresMfa(roles);
        String secret = mfa ? Totp.newSecret() : null;
        jdbc.sql("""
                        insert into user_account (id, person_id, username, password_hash, must_change_password, password_expires_at,
                                                  mfa_enabled, mfa_secret_enc, institutional_email, institutional_email_verified)
                        values (:id, :p, :u, :h, false, now() + interval '90 days', :mfa, :sec, :mail, true)""")
                .param("id", userId).param("p", personId).param("u", idNumber).param("h", hash())
                .param("mfa", mfa).param("sec", secret == null ? null : crypto.encrypt(secret, "user_account.mfa_secret_enc"))
                .param("mail", idNumber + "@test.unisfera.edu.ec").update();
        for (String role : roles) {
            jdbc.sql("insert into user_role (id, user_id, role_id) select :id, :u, id from role where code = :r")
                    .param("id", UuidV7.next()).param("u", userId).param("r", role).update();
        }
        return new TestUser(userId, personId, idNumber, PASSWORD, secret);
    }

    /** Recorre el flujo real: contraseña y, si el rol lo exige, segundo factor. */
    public String login(TestUser user) {
        Response first = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", user.username(), "password", user.password()))
                .post("/api/auth/login");
        first.then().statusCode(200);
        String status = first.path("status");
        if ("AUTHENTICATED".equals(status)) {
            return first.path("accessToken");
        }
        String code = Totp.code(user.mfaSecret(), Totp.step(Instant.now()));
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("challengeToken", first.path("challengeToken"), "code", code))
                .post("/api/auth/mfa/verify")
                .then().statusCode(200).extract().path("accessToken");
    }

    public String createAndLogin(String... roles) {
        return login(create(roles));
    }

    private boolean requiresMfa(String[] roles) {
        if (roles.length == 0) {
            return false;
        }
        return jdbc.sql("select count(*) from role where code in (:r) and requires_mfa").param("r", java.util.List.of(roles))
                .query(Long.class).single() > 0;
    }

    private synchronized String hash() {
        if (cachedHash == null) {
            cachedHash = encoder.encode(PASSWORD);
        }
        return cachedHash;
    }
}
