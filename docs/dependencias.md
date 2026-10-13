# Dependencias y versiones

Verificadas el **2026-10-13** en la fuente oficial de cada artefacto (Maven Central, registro npm, Docker Hub). Nada de rangos abiertos: las versiones de Spring las fija el BOM de Spring Boot.

## Plataforma

| Componente | Versión | Fuente | Nota |
|---|---|---|---|
| Java (Temurin) | 25 LTS (`--release 25`) | Docker Hub `eclipse-temurin:25.0.4.1_1-*` | Compila también con JDK 26. |
| Spring Boot | 4.1.1 | Maven Central | Última GA (4.2.0 en milestone). Trae Spring Framework 7.0.9, Spring Security 7.1.1, Hibernate 7.4.5, Jackson 3.1.5, JUnit 6.0.3, Flyway 12.4.0, Testcontainers 2.0.5. |
| Maven | 3.9.x (wrapper) | maven.apache.org | |
| PostgreSQL | 18.6 | Docker Hub `postgres:18.6-alpine` | `uuidv7()` nativo. |
| Valkey | 8.1.10 | Docker Hub `valkey/valkey:8.1.10-alpine` | Compatible con el protocolo de Redis, licencia BSD (Redis 8 es RSALv2/SSPL/AGPL). |
| Angular | 22.2.2 | registry.npmjs.org | Standalone, señales, sin zone.js. |
| Node.js | 24.21.0 LTS | Docker Hub `node:24.21.0-bookworm-slim` | Angular 22 exige ^22.22.3 \|\| ^24.15.0 \|\| ≥ 26. |
| nginx sin privilegios | 1.31.2 | Docker Hub `nginxinc/nginx-unprivileged:1.31.2-alpine` | Sirve el frontend como usuario no root. |
| Mailpit | v1.31.5 | Docker Hub `axllent/mailpit:v1.31.5` | Correo de desarrollo. |
| k6 | 2.1.0 | Docker Hub `grafana/k6:2.1.0` | Prueba de carga. |

## Backend

| Dependencia | Versión | Para qué |
|---|---|---|
| spring-boot-starter-webmvc, security, data-jpa, validation, data-redis, actuator, mail, flyway, cache | BOM 4.1.1 | En Spring Boot 4 Flyway necesita su propio starter. |
| flyway-database-postgresql | BOM | Soporte PostgreSQL de Flyway. |
| org.postgresql:postgresql | BOM (42.7.13) | Driver JDBC. |
| org.bouncycastle:bcprov-jdk18on | 1.86 | Requerido por `Argon2PasswordEncoder`. |
| io.jsonwebtoken:jjwt-api/impl/jackson | 0.13.0 | JWT HS256. |
| org.mapstruct:mapstruct (+processor) | 1.6.3 | Mapeo entidad ↔ DTO en compilación (1.7.0 aún en Beta). |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | 3.1.1 | OpenAPI 3.1 + Swagger UI para Spring Boot 4. |
| com.github.ben-manes.caffeine:caffeine | BOM (3.2.4) | Caché local de parámetros y permisos. |
| org.apache.poi:poi-ooxml | 5.5.1 | Exportación a Excel y carga masiva. |
| com.github.librepdf:openpdf | 3.0.5 | PDF: actas, RIDE, estados de cuenta, manuales. |
| net.logstash.logback:logstash-logback-encoder | 9.0 | Logs JSON con request-id. |
| io.micrometer:micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp | BOM | Trazas OTLP (desactivadas por defecto). |

## Pruebas y calidad

| Herramienta | Versión | Nota |
|---|---|---|
| JUnit Jupiter | 6.0.3 (BOM) | |
| AssertJ | 3.27.7 (BOM) | |
| Mockito | 5.23.0 (BOM) | |
| Testcontainers (postgresql, junit-jupiter) | 2.0.5 (BOM) | PostgreSQL real, nunca H2. |
| REST Assured | 6.1.0 | Pruebas de API. |
| ArchUnit (core) | 1.5.1 | Usado como librería con JUnit 6. |
| JaCoCo | 0.8.15 | Umbral que rompe el build. |
| Spotless | 3.10.4 | Formato verificado en CI. |
| @playwright/test | 1.64.0 | Flujos críticos e2e. |
| gitleaks | 8.30.1 | Secretos en el historial completo. |
| Trivy | acción `aquasecurity/trivy-action` fijada | Vulnerabilidades de dependencias e imágenes. |

### Decisión: pruebas por propiedades sin jqwik

`jqwik 1.10.2` (última, Maven Central) depende de **JUnit Platform 1.14.4**, mientras que Spring Boot 4.1.1 trae **JUnit Platform 6.0.3**. Mezclar ambas plataformas en el mismo classpath es frágil. Las pruebas por propiedades (T-04 cuadre financiero, promedio ponderado, prelación de abonos) se escriben con generadores con **semilla fija** (`SplittableRandom`) sobre JUnit 6: miles de casos reproducibles, sin dependencia extra. Se reevaluará cuando jqwik publique soporte para JUnit 6.

## Frontend

| Paquete | Versión | Para qué |
|---|---|---|
| @angular/* | 22.2.2 | Núcleo, router, forms, cdk. |
| tailwindcss + @tailwindcss/postcss | 4.3.3 | Sistema visual único para los tres portales. |
| chart.js | 4.5.1 | Gráficos del tablero. |
| zod | 4.x | Validación en el cliente espejo de la del servidor. |
| @fontsource-variable/fraunces, @fontsource-variable/manrope, @fontsource/jetbrains-mono | fijas | Tipografías autoalojadas (sin CDN externo, compatible con CSP). |
| qrcode | fija | QR del segundo factor generado en el navegador (el secreto no viaja a terceros). |
