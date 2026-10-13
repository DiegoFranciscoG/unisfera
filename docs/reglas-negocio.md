# Catálogo de reglas de negocio

Cada regla tiene un código `RN-xx` que aparece **en el mensaje de error de la API**, **en el nombre del test que la cubre** y **en el manual del estudiante** (sección con el mismo ancla). Ley 3: cada regla se valida en tres capas; si no coinciden, manda la base.

Leyenda de capas: **BD** = restricción, trigger o exclusión en PostgreSQL · **Dominio** = clase pura sin Spring · **UI** = validación en vivo en Angular.

## Las tres leyes

| Ley | Cómo se cumple | Test |
|---|---|---|
| **LEY-1 Nada quemado** | Tabla `parameter` con vigencia (`valid_from`, `valid_to`) y restricción de exclusión que impide dos valores vigentes a la vez; tarifario versionado; esquema de evaluación que copia los valores vigentes al activarse. | `T01HardcodedValuesTest` recorre `src/main/java` y falla ante literales de negocio fuera de la lista blanca justificada. |
| **LEY-2 Nada se borra** | Trigger `forbid_delete()` (DELETE y TRUNCATE) en todas las tablas de negocio; auditoría append-only con valor anterior y nuevo, actor, IP y request-id; actas, líneas de acta, movimientos contables y pagos confirmados inmutables; correcciones con reverso o rectificación. | `T06ImmutabilityIT` |
| **LEY-3 La lógica no se rompe** | Cada caso de uso en una transacción; las reglas críticas también en la BD; el manejador global traduce el error de la BD al mismo código `RN-xx`. | Todos los `*IT` |

## Matrícula

| Código | Regla | BD | Dominio | UI | Test |
|---|---|---|---|---|---|
| RN-01 | Solo dentro de las ventanas del período según el tipo (ordinaria; extraordinaria con recargo; especial con autorización). | Trigger `enrollment_window_guard` compara `enrolled_at` con las ventanas del período y exige autorización en la especial | `EnrollmentPolicy.checkWindow` | El asistente muestra la ventana vigente y deshabilita el botón fuera de ella | `EnrollmentPolicyTest.rn01_*`, `EnrollmentFlowIT.rn01_*` |
| RN-02 | No se matricula sin prerrequisitos aprobados; los correquisitos van en el mismo período (o ya aprobados). | — (se valida en dominio con datos de la vista `v_kardex`) | `PrerequisiteChecker` | Asignatura bloqueada con candado y explicación | `PrerequisiteCheckerTest.rn02_*` (T-05, combinaciones generadas) |
| RN-03 | El estudiante toma asignaturas de SU malla; cambiar de malla es homologación explícita. | `student.curriculum_id` inmutable (trigger) y trigger que exige que la asignatura pertenezca a la malla del estudiante | `EnrollmentPolicy.checkCurriculum` | Solo se listan asignaturas de su malla | `DatabaseRulesIT.rn03_*` |
| RN-04 | Tope de asignaturas por período parametrizable; excepción autorizada con motivo y responsable. | Trigger cuenta detalles activos contra `enrollment.max_subjects_per_period` vigente | `EnrollmentPolicy.checkLoad` | Contador "x de y asignaturas" | `EnrollmentPolicyTest.rn04_*` |
| RN-05 | Un paralelo nunca excede su cupo, ni con cientos de matrículas en el mismo segundo. | `UPDATE … SET enrolled_count = enrolled_count + 1 WHERE enrolled_count < capacity` dentro del trigger de inserción + `CHECK (enrolled_count <= capacity)` | Pre-chequeo amistoso | Cupos en vivo | `SeatConcurrencyIT.rn05_*` (T-02: 500 hilos, 30 cupos, ×20) |
| RN-06 | Sin choques de horario (solapamiento real de bloques). | Restricción de exclusión GiST sobre `enrollment_time_slot (student, period, weekday, int4range)` | `ScheduleClashDetector` | Horario semanal que marca el choque antes de confirmar | `ScheduleClashDetectorTest.rn06_*`, `DatabaseRulesIT.rn06_*` |
| RN-07 | Tercera matrícula solo con autorización; cuarta imposible. Retiros y anulaciones no cuentan. | Trigger calcula `attempt_number`; `CHECK (attempt_number BETWEEN 1 AND 3)`; `CHECK (attempt_number < 3 OR third_enrollment_authorization_id IS NOT NULL)` | `AttemptPolicy` | Etiqueta "2.ª matrícula" / "3.ª matrícula: requiere autorización" | `AttemptPolicyTest.rn07_*`, `DatabaseRulesIT.rn07_*` |
| RN-08 | Deuda vencida bloquea la matrícula si el parámetro lo indica; excepción del rol financiero registrada. | Trigger consulta cuotas vencidas y `finance.block_enrollment_on_overdue_debt` | `EnrollmentPolicy.checkDebt` | Aviso con enlace al estado de cuenta | `EnrollmentPolicyTest.rn08_*`, `PaymentFlowIT.rn27_*` |
| RN-09 | La matrícula genera los rubros del tarifario vigente y aplica descuentos y becas en el orden de prelación del parámetro. | `CHECK` de montos ≥ 0 y descuento ≤ base | `ChargeCalculator` | Resumen de costos antes de confirmar | `ChargeCalculatorTest.rn09_*` |
| RN-10 | Anular una matrícula no la borra: la marca anulada y revierte los rubros con un reverso. | `CHECK` de motivo y responsable; trigger libera cupos y franjas; trigger diferido verifica saldo cero de los rubros revertidos | `EnrollmentService.annul` | Confirmación con motivo obligatorio | `EnrollmentFlowIT.rn10_*` |

## Calificaciones y asistencia

| Código | Regla | BD | Dominio | UI | Test |
|---|---|---|---|---|---|
| RN-11 | Notas por componente; los pesos suman exactamente 100. | Trigger de restricción **diferido** suma pesos por esquema al confirmar | `EvaluationScheme.validateWeights` | Barra de pesos que debe llegar a 100 | `DatabaseRulesIT.rn11_*` (T-10) |
| RN-12 | Toda nota dentro de la escala vigente; nada negativo. | `CHECK (value >= 0)` + trigger contra `scale_min/scale_max` del esquema | `GradeScale.validate` | Celda en rojo con mensaje | `GradeScaleTest.rn12_*`, `DatabaseRulesIT.rn12_*` |
| RN-13 | El acta se cierra con todas las notas; calcula estados y guarda hash. | Trigger al pasar a `CLOSED` verifica notas completas y hash/firma no nulos | `ActCloser` + `ActHasher` | Resumen previo y confirmación escrita | `ActCloserTest.rn13_*` |
| RN-14 | Acta cerrada no se edita; se emite rectificación autorizada y ambas quedan. | Trigger `guard_grade_act` solo permite `CLOSED → RECTIFIED`; líneas de acta append-only | `ActService.rectify` | Historial de actas con ambas versiones | `T06ImmutabilityIT.rn14_*` |
| RN-15 | La asistencia mínima causa pérdida aun con nota aprobatoria; aviso antes del cierre. | `CHECK` en `grade_act_line`: `APPROVED ⇔ nota ≥ mínima ∧ asistencia ≥ mínima` | `ActCloser.resultFor` | Semáforo de asistencia y notificación | `ActCloserTest.rn15_*` |
| RN-16 | Promedio ponderado por horas, solo desde actas cerradas. | Vista `v_student_average` | `WeightedAverage` | Promedio con explicación del cálculo | `WeightedAverageTest.rn16_*` |
| RN-17 | El kardex solo usa actas cerradas. | Vista `v_kardex` filtra `status = 'CLOSED'` | — | Notas en borrador marcadas como "provisional" | `KardexIT.rn17_*` |

## Bienestar

| Código | Regla | BD | Dominio | UI | Test |
|---|---|---|---|---|---|
| RN-18 | La postulación solo se envía con todos los documentos obligatorios; antes es borrador. | Trigger al pasar a `SUBMITTED` verifica documentos | `ApplicationStateMachine` | Lista de documentos con check | `ScholarshipFlowIT.rn18_*` |
| RN-19 | Enviada, el estudiante no la edita; puede desistir y queda registrado. | Trigger bloquea cambios de contenido fuera de `DRAFT` | `ApplicationStateMachine` | Formulario en solo lectura | `ApplicationStateMachineTest.rn19_*` |
| RN-20 | Evaluación por criterios con puntaje; el resultado exige resolución del comité con acta, fecha y miembros. | `CHECK` aprobado/negado ⇒ `committee_resolution_id` | `ScholarshipScoring` | Tabla de criterios | `ScholarshipFlowIT.rn20_*` |
| RN-21 | La beca genera descuento automático desde el período indicado; nunca retroactiva salvo resolución expresa. | Trigger en `discount_applied` compara el período del rubro con el de inicio de la beca | `ChargeCalculator` | — | `ScholarshipFlowIT.rn21_*` |
| RN-22 | Mantenimiento por período; si no cumple, se suspende con notificación y apelación registrada. | `UNIQUE (award_id, period_id)` en seguimiento | `MaintenanceEvaluator` | Estado de beca y botón de apelar | `MaintenanceEvaluatorTest.rn22_*` |
| RN-23 | Salud y psicología: cifrados, solo BIENESTAR, acceso auditado, fuera de reportes identificables. | Columnas `bytea` cifradas (AES-256-GCM); reportes con supresión de celdas pequeñas | `CryptoService`, `SmallCellSuppression` | Pantalla solo visible con permiso | `T08SensitiveDataIT.rn23_*` |

## Financiero

| Código | Regla | BD | Dominio | UI | Test |
|---|---|---|---|---|---|
| RN-24 | Un pago nunca se procesa dos veces. | `UNIQUE (idempotency_key)`, `UNIQUE (gateway, event_id)`, bloqueo `FOR UPDATE` | `PaymentService.confirm` idempotente | Botón de pago que no se puede pulsar dos veces | `PaymentIdempotencyIT.rn24_*` (T-03) |
| RN-25 | Abonos en el orden de prelación del parámetro (mora → cuota más antigua). | — | `PaymentAllocator` | Detalle de aplicación del pago | `PaymentAllocatorTest.rn25_*` |
| RN-26 | La mora usa la regla vigente al vencimiento, no la de hoy. | Parámetros con vigencia | `LateFeeCalculator` | — | `LateFeeCalculatorTest.rn26_*` |
| RN-27 | Un pago confirmado desbloquea de inmediato lo bloqueado por deuda. | Bloqueo calculado en vivo, sin banderas en caché | `EnrollmentPolicy.checkDebt` | Mensaje de desbloqueo | `PaymentFlowIT.rn27_*` |
| RN-28 | Un pago no se elimina: se anula con nota de crédito y motivo. | Trigger: pago `CONFIRMED` solo pasa a `ANNULLED` con motivo y nota de crédito | `PaymentService.annul` | Diálogo de anulación | `T06ImmutabilityIT.rn28_*` |
| RN-29 | El estado de cuenta cuadra: cargos − descuentos − pagos = saldo. | Movimientos append-only; vista `v_account_statement` | `Ledger` | Estado de cuenta | `LedgerPropertyTest.rn29_*` (T-04, 10.000 movimientos) |

## Estados del estudiante

| Código | Regla | BD | Dominio | UI | Test |
|---|---|---|---|---|---|
| RN-30 | aspirante → admitido → matriculado → regular → (retirado \| suspendido) → egresado → graduado. Cada transición tiene condiciones, deja registro y es irreversible salvo proceso explícito. | Catálogo `student_status_transition` + trigger que rechaza transiciones no listadas o sin proceso | `StudentStateMachine` | Línea de tiempo del estudiante | `StudentStateMachineTest.rn30_*` |

## Seguridad e identidad (reglas SEC)

| Código | Regla | Test |
|---|---|---|
| SEC-01 | La cédula se valida con módulo 10 y nunca forma parte de la contraseña. | `CedulaValidatorTest`, `PasswordPolicyTest` |
| SEC-02 | Mensaje y tiempo de respuesta equivalentes para usuario inexistente y contraseña incorrecta. | `AuthFlowIT.sec02_*` |
| SEC-03 | Bloqueo temporal con retroceso progresivo; cada intento queda registrado con IP. | `LockoutPolicyTest`, `AuthFlowIT.sec03_*` |
| SEC-04 | Segundo factor obligatorio para roles que lo exigen. | `AuthFlowIT.sec04_*` |
| SEC-05 | Un estudiante solo ve lo suyo; un docente, sus paralelos; AUDITOR no modifica nada. | `T07IsolationIT` (recorre todos los endpoints) |
