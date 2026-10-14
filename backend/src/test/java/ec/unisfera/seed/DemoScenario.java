package ec.unisfera.seed;

import ec.unisfera.identity.domain.Totp;
import ec.unisfera.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escenario demo compartido por las pruebas que lo necesitan: se genera una sola vez por ejecución
 * con una contraseña aleatoria que solo existe en memoria.
 */
public final class DemoScenario {

    public static final String PASSWORD = "Demo-Recorrido-" + IntegrationTest.randomBase64(12).replaceAll("[^A-Za-z0-9]", "x") + "9";
    private static Map<String, String> users;
    private static final Map<String, String> MFA_SECRETS = new ConcurrentHashMap<>();

    private DemoScenario() {
    }

    public static synchronized Map<String, String> ensure(DemoDataGenerator generator) {
        if (users == null) {
            if (generator.alreadySeeded()) {
                throw new IllegalStateException("El escenario demo debe generarse solo desde DemoScenario");
            }
            users = generator.generate(150, 20, PASSWORD).demoUsers();
        }
        return users;
    }

    /** Ingreso real de una cuenta demo; si el rol exige segundo factor, completa el enrolamiento TOTP. */
    public static String login(String username) {
        Response first = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", PASSWORD)).post("/api/auth/login");
        first.then().statusCode(200);
        String status = first.path("status");
        if ("AUTHENTICATED".equals(status)) {
            return first.path("accessToken");
        }
        if ("MFA_ENROLLMENT_REQUIRED".equals(status)) {
            MFA_SECRETS.put(username, first.path("mfaSecret"));
        }
        String code = Totp.code(MFA_SECRETS.get(username), Totp.step(Instant.now()));
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("challengeToken", first.path("challengeToken"), "code", code))
                .post("/api/auth/mfa/verify").then().statusCode(200).extract().path("accessToken");
    }
}
