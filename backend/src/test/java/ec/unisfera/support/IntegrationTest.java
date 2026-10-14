package ec.unisfera.support;

import ec.unisfera.identity.domain.CedulaValidator;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: PostgreSQL 18.6 y Valkey reales (nunca H2), compartidos
 * por todas las clases. Los secretos se generan aleatoriamente en cada ejecución: no existen
 * en ningún archivo del repositorio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String SUPERADMIN_ID = TestIds.cedula(1, 0);
    public static final String SUPERADMIN_PASSWORD = "Sa-" + randomBase64(18);

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("unisfera").withUsername("unisfera").withPassword(randomBase64(18));
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.10-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        VALKEY.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
        registry.add("app.cors-allowed-origins", () -> "http://localhost:4200");
        registry.add("app.jwt-secret", () -> randomBase64(48));
        registry.add("app.sensitive-data-key", () -> randomBase64(32));
        registry.add("app.act-signing-key", () -> randomBase64(48));
        registry.add("app.gateway-webhook-secret", () -> randomBase64(48));
        registry.add("app.superadmin-id-number", () -> SUPERADMIN_ID);
        registry.add("app.superadmin-initial-password", () -> SUPERADMIN_PASSWORD);
        registry.add("app.cookie-secure", () -> "false");
        registry.add("app.storage-path", () -> System.getProperty("java.io.tmpdir") + "/unisfera-test-storage");
        registry.add("app.workers-enabled", () -> "false");
    }

    @Autowired
    protected Environment environment;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ec.unisfera.parameters.application.ParameterService parameters;

    private static volatile boolean testParametersPublished;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = Integer.parseInt(environment.getProperty("local.server.port"));
        RestAssured.basePath = "";
        publishTestParameters();
    }

    /** Todas las pruebas llegan desde 127.0.0.1: se publica (como en producción, con versión y motivo) un límite mayor. */
    private synchronized void publishTestParameters() {
        if (!testParametersPublished) {
            parameters.publish("security.rate_limit_login_per_minute", "100000", null, "Pruebas automatizadas desde una sola IP");
            testParametersPublished = true;
        }
    }

    protected RequestSpecification anonymous() {
        return RestAssured.given().contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    protected RequestSpecification as(String accessToken) {
        return anonymous().header("Authorization", "Bearer " + accessToken);
    }

    public static String randomBase64(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    /** Cédulas ficticias válidas para las pruebas. */
    public static final class TestIds {
        private TestIds() {
        }

        public static String cedula(int province, long sequence) {
            String nine = String.format("%02d%d%06d", province, (int) (sequence % 6), sequence % 1_000_000);
            return nine + CedulaValidator.checkDigit(nine);
        }

        public static List<String> many(int province, int count, long offset) {
            return java.util.stream.LongStream.range(offset, offset + count).mapToObj(i -> cedula(province, i)).toList();
        }
    }
}
