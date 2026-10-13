# Plan de construcción

Plazo pedido: **2 días**. Las 6 fases (F1–F6) se comprimen en los 7 bloques del portafolio (D1–D7). Cada bloque termina con tests en verde y commits pequeños (Conventional Commits).

| Bloque | Fase | Entregable | Criterio de salida |
|---|---|---|---|
| D1 | F1 | `investigacion.md`, `benchmark-universidades.md`, `reglas-negocio.md`, `modelo-datos.md`, `dependencias.md` | Fuentes con URL y fecha; supuestos marcados |
| D2 | F2 | Esqueleto Spring Boot 4.1.1, migraciones Flyway con triggers de LEY-2, auditoría, parámetros con vigencia, personas, usuarios, roles y permisos en BD, login con cédula + Argon2id + bloqueo + TOTP + refresco rotativo, superadministrador por migración, alta de estudiantes | `T09MigrationsIT`, `AuthFlowIT`, unitarios de cédula, contraseña y bloqueo |
| D3 | F3 | Carreras, mallas versionadas, asignaturas, prerrequisitos, períodos, paralelos, horarios y motor de matrícula con cupo concurrente | `SeatConcurrencyIT` (T-02), `DatabaseRulesIT`, T-05 |
| D3 | F4 | Esquemas de evaluación, notas, asistencia, cierre y rectificación de actas, kardex, promedios, retiros, homologaciones | `ActCloserTest`, `KardexIT`, T-06, T-10 |
| D4 | F5 | Tarifario, rubros, plan de cuotas, mora, pasarela simulada, idempotencia, factura con clave de acceso, notas de crédito, conciliación | T-03, T-04, `PaymentFlowIT` |
| D4 | F6 | Becas de punta a punta, atenciones cifradas, alertas de deserción, reportes Excel/PDF en cola | T-08, `ScholarshipFlowIT` |
| D5 | — | Frontend Angular 22 con diseño propio animado (tres portales en una app), seguridad transversal, Docker y CI | Build de producción, Playwright de 3 flujos |
| D6 | — | Generador de datos sintéticos con semilla fija, k6 del día de matrícula, capturas | `docker compose up --build` levanta todo con datos |
| D7 | — | Manuales, README final, pulido | Listo para "VOY A SUBIR" |
