# syntax=docker/dockerfile:1.7
# Imagen del backend. Contexto de construcción: raíz del repositorio (los manuales viven en docs/manuales).

# ---- Etapa 1: compilación con JDK
FROM eclipse-temurin:27_35-jdk-alpine AS build
WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY backend/src src
COPY docs/manuales /workspace/docs/manuales
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests -Dspotless.check.skip=true package

# ---- Etapa 2: ejecución con JRE, usuario sin privilegios
FROM eclipse-temurin:27_35-jre-alpine
RUN addgroup -S -g 10001 unisfera \
    && adduser -S -D -H -u 10001 -G unisfera unisfera \
    && mkdir -p /app/storage \
    && chown -R unisfera:unisfera /app
WORKDIR /app
COPY --from=build --chown=unisfera:unisfera /workspace/backend/target/unisfera.jar app.jar
USER 10001:10001
ENV STORAGE_PATH=/app/storage
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=4s --start-period=90s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/app.jar"]
