package ec.unisfera.identity.security;

import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.web.RequestContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny-by-default: solo el ingreso, la recuperación, los webhooks firmados y la salud son
 * públicos. API sin estado (la sesión vive en el token), CORS con orígenes explícitos y
 * cabeceras de seguridad.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, TokenService tokens, PermissionResolver permissions,
                                           RevokedSessionStore revoked, UserDirectory directory) throws Exception {
        http.csrf(c -> c.disable())
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/api/public/**", "/actuator/health", "/actuator/health/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(res, HttpServletResponse.SC_UNAUTHORIZED,
                                "UNAUTHENTICATED", "Debe iniciar sesión"))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(res, HttpServletResponse.SC_FORBIDDEN,
                                "FORBIDDEN", "No tiene acceso a este recurso")))
                .addFilterBefore(new JwtAuthenticationFilter(tokens, permissions, revoked, directory),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordHashing.encoder();
    }

    /** Sin usuarios en memoria: la autenticación es propia (AuthService). */
    @Bean
    public UserDetailsService noUserDetails() {
        return username -> {
            throw new UsernameNotFoundException("No se usa");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Content-Disposition"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    static void writeProblem(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        String requestId = RequestContext.requestId() == null ? "" : RequestContext.requestId();
        res.getWriter().write("{\"type\":\"urn:unisfera:error:" + code + "\",\"title\":\"" + code + "\",\"status\":" + status
                + ",\"detail\":\"" + message + "\",\"code\":\"" + code + "\",\"requestId\":\"" + requestId + "\"}");
    }
}
