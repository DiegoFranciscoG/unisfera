package ec.unisfera.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfig {

    /** Reloj único del sistema: los servicios nunca llaman a Instant.now() directamente. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public java.util.concurrent.ExecutorService backgroundExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public Executor taskExecutor(java.util.concurrent.ExecutorService backgroundExecutor) {
        return backgroundExecutor;
    }

    @Bean
    public OpenAPI unisferaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("UNISFERA API")
                        .version("1.0.0")
                        .description("ERP de administración universitaria. Cada error de negocio trae el código de la regla (RN-xx) y el enlace al manual.")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
