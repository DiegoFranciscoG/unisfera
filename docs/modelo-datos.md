# Modelo de datos

PostgreSQL 18. Migraciones versionadas con Flyway en `backend/src/main/resources/db/migration`. Derivado de las fuentes de [`investigacion.md`](investigacion.md) (#n) y de las reglas de [`reglas-negocio.md`](reglas-negocio.md).

## Convenciones

- Identificadores `uuid` **v7** (ordenados por tiempo: buena localidad de índice y no enumerables). Se generan en Java (`UuidV7`) o con `uuidv7()` de PostgreSQL 18.
- Toda tabla de negocio lleva `created_at`, `created_by`, `updated_at`, `updated_by`, `version` (bloqueo optimista) y, donde aplica, `annulled_at`, `annulled_by`, `annulment_reason`.
- Fechas y horas en `timestamptz` (UTC); fechas de calendario (`date`) interpretadas en `America/Guayaquil`.
- Dinero en `numeric(12,2)`; porcentajes en `numeric(5,2)`; notas en `numeric(5,2)`. Nunca coma flotante.
- **LEY-2**: trigger `forbid_delete()` en DELETE y TRUNCATE de todas las tablas de negocio. **Auditoría**: trigger `audit_row_change()` escribe en `audit_log` solo las columnas que cambiaron, con actor, IP y request-id que la aplicación fija por transacción (`set_config('unisfera.actor_id', …, true)`). Las columnas sensibles (`*_enc`, `password_hash`, `*token_hash`) se enmascaran.
- Tablas grandes particionadas: `audit_log` por mes (RANGE) y `attendance` por período (LIST), con partición `DEFAULT` y función que crea particiones nuevas.

## Institucional y paramétrico

```mermaid
erDiagram
  INSTITUTION ||--o{ CAMPUS : tiene
  INSTITUTION ||--o{ FACULTY : tiene
  FACULTY ||--o{ CAREER : ofrece
  CAMPUS ||--o{ CAREER : "se dicta en"
  CAREER ||--o{ CURRICULUM : "versiones de malla"
  CURRICULUM ||--o{ CURRICULUM_LEVEL : niveles
  CURRICULUM_LEVEL ||--o{ SUBJECT : asignaturas
  SUBJECT ||--o{ SUBJECT_PREREQUISITE : requiere
  SUBJECT ||--o{ SUBJECT_COREQUISITE : "junto con"
  SUBJECT ||--o{ CURRICULUM_EQUIVALENCE : "equivale a"
  CAMPUS ||--o{ CLASSROOM : aulas
  ACADEMIC_PERIOD ||--o{ SECTION : paralelos
  SUBJECT ||--o{ SECTION : "se abre en"
  SECTION ||--o{ SCHEDULE_BLOCK : horario
  CLASSROOM ||--o{ SCHEDULE_BLOCK : ocupa

  CAREER { uuid id PK
    varchar code UK
    varchar senescyt_code "código SENESCYT"
    varchar modality "RRA art. 54"
    varchar degree_title
    int duration_periods }
  CURRICULUM { uuid id PK
    uuid career_id FK
    varchar version_code "UK con career"
    date valid_from
    varchar status }
  SUBJECT { uuid id PK
    uuid curriculum_id FK
    varchar code
    int teacher_contact_hours "RRA art. 23"
    int practical_hours "RRA art. 25"
    int autonomous_hours "RRA art. 24"
    int total_hours "generada"
    numeric credits "48 h = 1 crédito (art. 9)"
    varchar organization_unit }
  ACADEMIC_PERIOD { uuid id PK
    varchar code UK
    varchar type "ORDINARY | EXTRAORDINARY"
    date ordinary_enrollment_from
    date ordinary_enrollment_to
    date extraordinary_enrollment_to
    date special_enrollment_to
    date classes_start
    date classes_end
    date grades_close_deadline
    varchar status }
  SECTION { uuid id PK
    uuid period_id FK
    uuid subject_id FK
    varchar code
    int capacity
    int enrolled_count "CHECK <= capacity"
    uuid teacher_id FK }
  SCHEDULE_BLOCK { uuid id PK
    uuid section_id FK
    smallint weekday
    time start_time
    time end_time
    uuid classroom_id "EXCLUDE choque de aula" }
  PARAMETER { uuid id PK
    varchar key
    text value
    varchar data_type
    timestamptz valid_from
    timestamptz valid_to "EXCLUDE solape de vigencia" }
```

## Personas, identidad y seguridad

```mermaid
erDiagram
  PERSON ||--o| USER_ACCOUNT : "tiene cuenta"
  PERSON ||--o{ EMERGENCY_CONTACT : contactos
  PERSON ||--o{ PERSON_DOCUMENT : documentos
  PERSON ||--o{ CONSENT : "consentimientos LOPDP"
  USER_ACCOUNT ||--o{ USER_ROLE : roles
  ROLE ||--o{ USER_ROLE : ""
  ROLE ||--o{ ROLE_PERMISSION : ""
  PERMISSION ||--o{ ROLE_PERMISSION : ""
  USER_ACCOUNT ||--o{ AUTH_SESSION : sesiones
  USER_ACCOUNT ||--o{ LOGIN_ATTEMPT : intentos
  USER_ACCOUNT ||--o{ PASSWORD_RESET_TOKEN : recuperaciones
  PERSON ||--o| STUDENT : "es"
  PERSON ||--o| TEACHER : "es"

  PERSON { uuid id PK
    varchar id_type "CEDULA | PASSPORT"
    varchar id_number "UK; CHECK is_valid_cedula()"
    varchar first_names
    varchar last_names
    date birth_date
    varchar sex
    varchar marital_status
    char nationality "ISO 3166 alfa-3" }
  USER_ACCOUNT { uuid id PK
    uuid person_id FK
    varchar username "cédula o pasaporte"
    varchar password_hash "Argon2id"
    boolean must_change_password
    timestamptz password_expires_at
    int failed_attempts
    timestamptz locked_until
    boolean mfa_enabled
    bytea mfa_secret_enc "AES-GCM"
    varchar institutional_email }
  AUTH_SESSION { uuid id PK
    uuid user_id FK
    varchar refresh_token_hash UK
    timestamptz expires_at
    timestamptz last_seen_at
    timestamptz revoked_at
    uuid replaced_by_id "rotación" }
  LOGIN_ATTEMPT { uuid id PK
    varchar username_attempted
    varchar ip
    boolean success
    varchar reason }
```

## Académico

```mermaid
erDiagram
  ADMISSION_PROCESS ||--o{ APPLICANT : postulantes
  APPLICANT ||--o| STUDENT : "admitido crea"
  STUDENT ||--o{ STUDENT_STATUS_HISTORY : "historial RN-30"
  STUDENT ||--o{ ENROLLMENT : matrículas
  ACADEMIC_PERIOD ||--o{ ENROLLMENT : ""
  ENROLLMENT ||--o{ ENROLLMENT_DETAIL : asignaturas
  SECTION ||--o{ ENROLLMENT_DETAIL : ""
  ENROLLMENT_DETAIL ||--o{ ENROLLMENT_TIME_SLOT : "franjas RN-06"
  ENROLLMENT ||--o| ENROLLMENT_ANNULMENT : anulación
  ENROLLMENT_DETAIL ||--o| WITHDRAWAL : retiro
  AUTHORIZATION ||--o{ ENROLLMENT_DETAIL : "tercera matrícula"
  AUTHORIZATION ||--o{ ENROLLMENT : "especial, carga, deuda"
  STUDENT ||--o{ HOMOLOGATION : homologaciones
  STUDENT ||--o| TITLING : titulación
  TEACHER ||--o{ TEACHER_ASSIGNMENT : distributivo

  STUDENT { uuid id PK
    uuid person_id FK
    varchar code UK
    uuid career_id FK
    uuid curriculum_id "inmutable RN-03"
    varchar cohort
    varchar status
    varchar institutional_email }
  ENROLLMENT { uuid id PK
    uuid student_id FK
    uuid period_id FK
    varchar type "ORDINARY | EXTRAORDINARY | SPECIAL"
    varchar status "CONFIRMED | ANNULLED"
    timestamptz enrolled_at
    uuid registered_by }
  ENROLLMENT_DETAIL { uuid id PK
    uuid enrollment_id FK
    uuid subject_id FK
    uuid section_id FK
    smallint attempt_number "1..3 calculado por la BD"
    uuid third_enrollment_authorization_id
    varchar status "ENROLLED | WITHDRAWN | ANNULLED" }
  ENROLLMENT_TIME_SLOT { uuid student_id
    uuid period_id
    smallint weekday
    int4range minutes "EXCLUDE GiST &&" }
  AUTHORIZATION { uuid id PK
    varchar type
    uuid student_id
    varchar cause_code
    text reason
    uuid authorized_by
    timestamptz used_at }
  HOMOLOGATION { uuid id PK
    varchar method "RRA arts. 82-84"
    uuid target_subject_id
    numeric coverage_percentage ">= 80"
    date source_approved_on "<= 10 años"
    numeric grade }
```

## Evaluación

```mermaid
erDiagram
  EVALUATION_SCHEME ||--o{ EVALUATION_COMPONENT : componentes
  ENROLLMENT_DETAIL ||--o{ GRADE : notas
  EVALUATION_COMPONENT ||--o{ GRADE : ""
  GRADE ||--o{ GRADE_HISTORY : historial
  SECTION ||--o{ CLASS_SESSION : sesiones
  CLASS_SESSION ||--o{ ATTENDANCE : asistencia
  SECTION ||--o{ GRADE_ACT : actas
  GRADE_ACT ||--o{ GRADE_ACT_LINE : líneas
  GRADE_ACT ||--o| GRADE_ACT : "rectifica a"

  EVALUATION_SCHEME { uuid id PK
    uuid period_id FK
    uuid career_id "null = todas"
    numeric scale_min "copiado del parámetro"
    numeric scale_max
    numeric passing_grade
    numeric min_attendance_pct }
  EVALUATION_COMPONENT { uuid id PK
    uuid scheme_id FK
    varchar name
    numeric weight "SUMA = 100 (trigger diferido)"
    smallint sort_order }
  GRADE { uuid id PK
    uuid enrollment_detail_id FK
    uuid component_id FK
    numeric value "dentro de la escala"
    bigint version }
  GRADE_ACT { uuid id PK
    uuid section_id FK
    varchar type "ORIGINAL | RECTIFICATION"
    varchar status "DRAFT | CLOSED | RECTIFIED"
    char content_hash "SHA-256"
    varchar teacher_signature "HMAC"
    uuid rectifies_act_id
    uuid superseded_by_id }
  GRADE_ACT_LINE { uuid id PK
    uuid act_id FK
    uuid enrollment_detail_id FK
    numeric final_grade
    numeric attendance_pct
    numeric passing_grade "snapshot"
    numeric min_attendance_pct "snapshot"
    varchar result "CHECK coherente RN-15" }
```

Vistas: **`v_kardex`** (líneas de actas `CLOSED` + homologaciones aprobadas, con horas y créditos) y **`v_student_average`** (promedio ponderado por horas, RN-16).

## Financiero

```mermaid
erDiagram
  CHARGE_CONCEPT ||--o{ TARIFF : "precio por carrera, nivel y período"
  ENROLLMENT ||--o{ CHARGE : "genera RN-09"
  CHARGE ||--o{ DISCOUNT_APPLIED : descuentos
  ENROLLMENT ||--o| PAYMENT_PLAN : plan
  PAYMENT_PLAN ||--o{ INSTALLMENT : cuotas
  PAYMENT ||--o{ PAYMENT_ALLOCATION : "se aplica a"
  INSTALLMENT ||--o{ PAYMENT_ALLOCATION : ""
  PAYMENT ||--o{ GATEWAY_TRANSACTION : "bitácora pasarela"
  PAYMENT ||--o| INVOICE : factura
  INVOICE ||--o{ CREDIT_NOTE : "notas de crédito"
  STUDENT ||--o{ ACCOUNT_ENTRY : "libro mayor append-only"
  RECONCILIATION ||--o{ RECONCILIATION_ITEM : partidas

  TARIFF { uuid id PK
    uuid concept_id FK
    uuid career_id
    smallint level_number
    uuid period_id
    varchar calc_mode "FLAT | PER_CREDIT"
    numeric amount
    int version "versionado" }
  CHARGE { uuid id PK
    uuid student_id
    uuid period_id
    uuid concept_id
    numeric base_amount
    numeric discount_amount
    numeric net_amount
    varchar status "ACTIVE | REVERSED" }
  INSTALLMENT { uuid id PK
    uuid plan_id FK
    smallint number
    date due_date
    numeric amount_due
    numeric late_fee_accrued
    numeric amount_paid }
  PAYMENT { uuid id PK
    uuid student_id
    numeric amount
    varchar status "PENDING | CONFIRMED | REJECTED | ANNULLED"
    varchar idempotency_key UK
    varchar gateway_reference UK }
  ACCOUNT_ENTRY { uuid id PK
    uuid student_id
    varchar entry_type "CHARGE | DISCOUNT | PAYMENT | LATE_FEE | REVERSAL"
    smallint direction "+1 debe / -1 haber"
    numeric amount }
  INVOICE { uuid id PK
    char establishment
    char emission_point
    bigint sequential
    char access_key "49 dígitos mód. 11"
    varchar sri_status }
```

Vista **`v_account_statement`**: cargos − descuentos − pagos + mora = saldo (RN-29).

## Bienestar

```mermaid
erDiagram
  SCHOLARSHIP_TYPE ||--o{ SCHOLARSHIP_CALL : convocatorias
  SCHOLARSHIP_CALL ||--o{ CALL_REQUIRED_DOCUMENT : "documentos exigidos"
  SCHOLARSHIP_CALL ||--o{ EVALUATION_CRITERION : criterios
  SCHOLARSHIP_CALL ||--o{ SCHOLARSHIP_APPLICATION : postulaciones
  SCHOLARSHIP_APPLICATION ||--o{ APPLICATION_DOCUMENT : adjuntos
  SCHOLARSHIP_APPLICATION ||--o{ APPLICATION_SCORE : puntajes
  COMMITTEE_RESOLUTION ||--o{ SCHOLARSHIP_APPLICATION : resuelve
  COMMITTEE_RESOLUTION ||--o{ COMMITTEE_MEMBER : miembros
  SCHOLARSHIP_APPLICATION ||--o| SCHOLARSHIP_AWARD : "si se aprueba"
  SCHOLARSHIP_AWARD ||--o{ SCHOLARSHIP_FOLLOWUP : "mantenimiento RN-22"
  SCHOLARSHIP_FOLLOWUP ||--o| SCHOLARSHIP_APPEAL : apelación
  STUDENT ||--o{ WELFARE_ATTENTION : "DATO SENSIBLE"
  STUDENT ||--o{ DROPOUT_ALERT : alertas

  SCHOLARSHIP_TYPE { uuid id PK
    varchar code
    varchar mode "PERCENT | AMOUNT"
    numeric value
    numeric min_average
    numeric min_attendance
    boolean renewable }
  SCHOLARSHIP_APPLICATION { uuid id PK
    uuid call_id FK
    uuid student_id FK
    varchar status "DRAFT ... WITHDRAWN"
    numeric total_score
    uuid committee_resolution_id }
  WELFARE_ATTENTION { uuid id PK
    uuid student_id
    varchar category "HEALTH | PSYCHOLOGY | ..."
    bytea summary_enc "AES-256-GCM"
    bytea details_enc
    uuid professional_user_id }
```

## Transversal

| Tabla | Para qué | Restricción clave |
|---|---|---|
| `audit_log` | Quién, cuándo, IP, request-id, tabla, registro, valor anterior y nuevo | Particionada por mes; trigger bloquea UPDATE y DELETE |
| `notification` | Avisos en la app y por correo | `read_at` es lo único editable |
| `student_request` + `student_request_event` | Solicitudes (certificados, retiros, homologaciones) con flujo de aprobación | Eventos append-only |
| `stored_file` | Archivos con tipo, tamaño, hash y nombre saneado | Tipo validado por firma de bytes |
| `background_job` | Cola de reportes y cargas masivas | Consumo con `FOR UPDATE SKIP LOCKED` |
| `process_log` | Bitácora de cierres, cargas masivas y trabajos programados | Append-only |
| `consent` | Consentimientos LOPDP por finalidad | Revocación sin borrar |

## Catálogos oficiales usados

| Catálogo | Valores | Fuente |
|---|---|---|
| Modalidad | PRESENCIAL, SEMIPRESENCIAL, EN_LINEA, A_DISTANCIA, HIBRIDA, DUAL | RRA art. 54 (#1) |
| Tipo de matrícula | ORDINARY, EXTRAORDINARY, SPECIAL | RRA art. 71 (#1) |
| Componentes de aprendizaje | contacto con el docente, práctico-experimental, autónomo | RRA arts. 22–25 (#1) |
| Métodos de homologación | CONTENT_ANALYSIS, KNOWLEDGE_VALIDATION, PROFESSIONAL_PRACTICE, INTERNAL_RECOGNITION | RRA arts. 81–84 (#1) |
| Tipo de comprobante SRI | 01 factura, 04 nota de crédito | Ficha técnica off-line (#8) |
| Provincia de cédula | 01–24, 30 | (#9, #10) |
