package ec.unisfera.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import ec.unisfera.identity.domain.Totp;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(TestUsers.class)
class AuthFlowIT extends IntegrationTest {

    @Autowired
    TestUsers users;

    @Test
    void sec01_superadminCreatedByMigrationMustChangePasswordThenEnrollMfa() {
        Response login = anonymous().body(Map.of("username", SUPERADMIN_ID, "password", SUPERADMIN_PASSWORD)).post("/api/auth/login");
        login.then().statusCode(200).body("status", equalTo("PASSWORD_CHANGE_REQUIRED"));

        // La cédula nunca puede ser parte de la contraseña
        anonymous().body(Map.of("challengeToken", login.path("challengeToken"), "newPassword", "Clave-" + SUPERADMIN_ID + "-x"))
                .post("/api/auth/password/change-required")
                .then().statusCode(422).body("code", equalTo("SEC-01"));

        Response relogin = anonymous().body(Map.of("username", SUPERADMIN_ID, "password", SUPERADMIN_PASSWORD)).post("/api/auth/login");
        Response changed = anonymous().body(Map.of("challengeToken", relogin.path("challengeToken"), "newPassword", "Nueva-Clave-Robusta-2026!"))
                .post("/api/auth/password/change-required");
        changed.then().statusCode(200).body("status", equalTo("MFA_ENROLLMENT_REQUIRED"));
        String secret = changed.path("mfaSecret");
        assertThat((String) changed.path("otpauthUri")).startsWith("otpauth://totp/UNISFERA:");

        Response verified = anonymous().body(Map.of("challengeToken", changed.path("challengeToken"),
                        "code", Totp.code(secret, Totp.step(Instant.now()))))
                .post("/api/auth/mfa/verify");
        verified.then().statusCode(200).body("status", equalTo("AUTHENTICATED"));
        as(verified.path("accessToken")).get("/api/auth/me").then().statusCode(200).body("roles[0]", equalTo("SUPERADMIN"));
    }

    @Test
    void sec02_sameMessageForUnknownUserAndWrongPassword() {
        TestUsers.TestUser user = users.create("STUDENT");
        Response unknown = anonymous().body(Map.of("username", "0999999999", "password", "cualquier-cosa-larga")).post("/api/auth/login");
        Response wrong = anonymous().body(Map.of("username", user.username(), "password", "cualquier-cosa-larga")).post("/api/auth/login");
        unknown.then().statusCode(401);
        wrong.then().statusCode(401);
        assertThat((String) unknown.path("detail")).isEqualTo(wrong.path("detail"));
        assertThat((String) unknown.path("code")).isEqualTo(wrong.path("code"));
    }

    @Test
    void sec03_progressiveLockoutAndAttemptsRecordedWithIp() {
        TestUsers.TestUser user = users.create("STUDENT");
        for (int i = 0; i < 5; i++) {
            anonymous().body(Map.of("username", user.username(), "password", "incorrecta-" + i)).post("/api/auth/login").then().statusCode(401);
        }
        // Bloqueada: ni la contraseña correcta entra
        anonymous().body(Map.of("username", user.username(), "password", user.password())).post("/api/auth/login").then().statusCode(401);
        long recorded = jdbc.sql("select count(*) from login_attempt where username_attempted = :u and not success and ip is not null")
                .param("u", user.username()).query(Long.class).single();
        assertThat(recorded).isEqualTo(6);
        Object lockedUntil = jdbc.sql("select locked_until from user_account where id = :id").param("id", user.userId()).query(Object.class).single();
        assertThat(lockedUntil).isNotNull();
    }

    @Test
    void sec04_mfaRequiredForAdministrativeRoles() {
        TestUsers.TestUser finance = users.create("FINANCE");
        anonymous().body(Map.of("username", finance.username(), "password", finance.password())).post("/api/auth/login")
                .then().statusCode(200).body("status", equalTo("MFA_REQUIRED"));
        TestUsers.TestUser student = users.create("STUDENT");
        anonymous().body(Map.of("username", student.username(), "password", student.password())).post("/api/auth/login")
                .then().statusCode(200).body("status", equalTo("AUTHENTICATED"));
    }

    @Test
    void refreshTokenRotatesAndReuseRevokesTheFamily() {
        TestUsers.TestUser user = users.create("STUDENT");
        Response login = anonymous().body(Map.of("username", user.username(), "password", user.password())).post("/api/auth/login");
        String firstRefresh = login.getCookie("unisfera_rt");
        assertThat(firstRefresh).isNotBlank();

        Response rotated = RestAssured.given().contentType(ContentType.JSON).cookie("unisfera_rt", firstRefresh).post("/api/auth/refresh");
        rotated.then().statusCode(200);
        String secondRefresh = rotated.getCookie("unisfera_rt");

        // Reutilizar el token ya rotado = robo: se revoca toda la familia
        RestAssured.given().contentType(ContentType.JSON).cookie("unisfera_rt", firstRefresh).post("/api/auth/refresh").then().statusCode(401);
        RestAssured.given().contentType(ContentType.JSON).cookie("unisfera_rt", secondRefresh).post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void logoutAllInvalidatesAccessTokensImmediately() {
        TestUsers.TestUser user = users.create("STUDENT");
        String token = users.login(user);
        as(token).get("/api/auth/me").then().statusCode(200);
        as(token).post("/api/auth/logout-all").then().statusCode(200);
        as(token).get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void studentCanOnlyEditFieldsMarkedAsEditable() {
        String token = users.createAndLogin("STUDENT");
        as(token).body(Map.of("phone_mobile", "0991234567")).patch("/api/me/profile").then().statusCode(200)
                .body("phoneMobile", equalTo("0991234567"));
        as(token).body(Map.of("first_names", "Otro Nombre")).patch("/api/me/profile").then().statusCode(422)
                .body("code", equalTo("SEC-05"));
    }
}
