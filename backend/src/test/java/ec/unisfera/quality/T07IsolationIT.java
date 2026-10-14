package ec.unisfera.quality;

import static org.assertj.core.api.Assertions.assertThat;

import ec.unisfera.identity.security.AuthenticatedOnly;
import ec.unisfera.identity.security.PublicEndpoint;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.support.IntegrationTest;
import ec.unisfera.support.TestUsers;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * T-07 · Aislamiento por identidad contra TODOS los endpoints (no una muestra): se recorren los
 * mapeos registrados en Spring MVC y cada uno se invoca con identificadores ajenos.
 */
@Import(TestUsers.class)
class T07IsolationIT extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;
    @Autowired
    TestUsers users;

    record Endpoint(String method, String path, Set<String> permissions, boolean publicEndpoint, boolean selfOnly, boolean multipart) {
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> result = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (!h.getBeanType().getPackageName().startsWith("ec.unisfera")) {
                continue;
            }
            RequiresPermission rp = find(h, RequiresPermission.class);
            boolean pub = find(h, PublicEndpoint.class) != null;
            boolean self = find(h, AuthenticatedOnly.class) != null;
            for (String path : e.getKey().getPatternValues()) {
                for (var method : e.getKey().getMethodsCondition().getMethods()) {
                    boolean multipart = e.getKey().getConsumesCondition().getConsumableMediaTypes().stream()
                            .anyMatch(m -> m.getSubtype().contains("form-data"));
                    result.add(new Endpoint(method.name(), path, rp == null ? Set.of() : Set.of(rp.value()), pub, self, multipart));
                }
            }
        }
        return result;
    }

    @Test
    void everyEndpointDeclaresItsAccessPolicy() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (!h.getBeanType().getPackageName().startsWith("ec.unisfera")) {
                continue;
            }
            boolean declared = find(h, RequiresPermission.class) != null || find(h, PublicEndpoint.class) != null
                    || find(h, AuthenticatedOnly.class) != null;
            assertThat(declared).as("Política de acceso declarada en " + h).isTrue();
        }
    }

    @Test
    void sec05_studentGets403OnEveryEndpointThatIsNotHisOwn() {
        String student = users.createAndLogin("STUDENT");
        List<String> leaks = new ArrayList<>();
        for (Endpoint ep : endpoints()) {
            if (ep.publicEndpoint() || ep.selfOnly() || ep.permissions().contains("SELF_SERVICE")) {
                continue;
            }
            int status = call(student, ep).statusCode();
            if (status != 403) {
                leaks.add(ep.method() + " " + ep.path() + " → " + status);
            }
        }
        assertThat(leaks).as("Endpoints accesibles para un estudiante sin permiso").isEmpty();
    }

    @Test
    void sec05_teacherGets403OutsideHisPortal() {
        String teacher = users.createAndLogin("TEACHER");
        List<String> leaks = new ArrayList<>();
        for (Endpoint ep : endpoints()) {
            if (ep.publicEndpoint() || ep.selfOnly() || ep.permissions().contains("TEACHING_OWN_SECTIONS")) {
                continue;
            }
            int status = call(teacher, ep).statusCode();
            if (status != 403) {
                leaks.add(ep.method() + " " + ep.path() + " → " + status);
            }
        }
        assertThat(leaks).isEmpty();
        // y en su propio portal, un paralelo ajeno también es 403 (filtrado por identidad)
        String foreignSection = "/api/teacher/sections/" + UUID.randomUUID() + "/roster";
        assertThat(as(teacher).get(foreignSection).statusCode()).isEqualTo(403);
    }

    @Test
    void sec05_auditorCanReadButNeverModify() {
        String auditor = users.createAndLogin("AUDITOR");
        Set<String> writePermissions = Set.copyOf(jdbc.sql("select code from permission where is_write").query(String.class).list());
        List<String> leaks = new ArrayList<>();
        for (Endpoint ep : endpoints()) {
            boolean modifies = !"GET".equals(ep.method()) && !ep.permissions().isEmpty()
                    && ep.permissions().stream().allMatch(writePermissions::contains);
            if (modifies) {
                int status = call(auditor, ep).statusCode();
                if (status != 403) {
                    leaks.add(ep.method() + " " + ep.path() + " → " + status);
                }
            }
        }
        assertThat(leaks).isEmpty();
        assertThat(as(auditor).get("/api/audit").statusCode()).isEqualTo(200);
    }

    @Test
    void unauthenticatedRequestsAreRejectedByDefault() {
        List<String> leaks = new ArrayList<>();
        for (Endpoint ep : endpoints()) {
            if (ep.publicEndpoint()) {
                continue;
            }
            int status = call(null, ep).statusCode();
            if (status != 401) {
                leaks.add(ep.method() + " " + ep.path() + " → " + status);
            }
        }
        assertThat(leaks).isEmpty();
    }

    private Response call(String token, Endpoint ep) {
        String path = ep.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        var spec = token == null ? io.restassured.RestAssured.given()
                : io.restassured.RestAssured.given().header("Authorization", "Bearer " + token);
        if (ep.multipart()) {
            return spec.multiPart("file", "extracto.csv", "referencia;monto".getBytes()).request(ep.method(), path);
        }
        return spec.contentType(io.restassured.http.ContentType.JSON).body("{}").request(ep.method(), path);
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod h, Class<A> type) {
        A m = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), type);
        return m != null ? m : AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), type);
    }
}
