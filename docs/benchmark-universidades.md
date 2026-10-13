# Benchmark: cómo operan cuatro universidades de Cuenca

Fuentes numeradas en [`investigacion.md`](investigacion.md). **S** = SUPUESTO (no verificado en fuente oficial). Consulta: 2026-10-13.

> Contexto clave: la **Universidad de Cuenca es pública** (aplica gratuidad, LOES art. 80); la **U. del Azuay, la U. Católica de Cuenca y la UPS son particulares cofinanciadas**. UNISFERA modela una universidad **privada**, así que la gratuidad no aplica: el estudiante paga matrícula y aranceles y la institución debe sostener becas para al menos el 10 % de sus estudiantes regulares (LOES art. 77, #3).

## Tabla comparativa

| Punto | Universidad de Cuenca | U. Católica de Cuenca | U. Politécnica Salesiana | Universidad del Azuay |
|---|---|---|---|---|
| Tipo | Pública | Particular cofinanciada | Particular cofinanciada | Particular cofinanciada |
| Sistema académico | S — no publicado | S — no publicado | S — no publicado | Prematrícula y matrícula en línea (#11) |
| Escala / nota mínima | Reglamento UC-CU-REG-008-2022 (#19); cifras **S** | **S** | Promedios expresados sobre 100 (#16); mínimo **S** | Grado: **S** (en pandemia 30/50, #15); posgrado 70/100 (#14) |
| Aportes y pesos | Recuperación una sola vez por período (#19) | **S** | **S** | Posgrado: ≥ 2 actividades + final ≤ 60 % (#14) |
| Asistencia exigida | **S** | **S** | **S** | Reprueba con > 25 % de faltas → 75 % (#11) |
| Kardex / récord | "Récord académico" **S** | **S** | **S** | **S** |
| Matrícula | Calendario por resolución, reformable (#21); admisión con examen (15.664 postulantes en 2026, #22) | **S** | **S** | Ordinaria ≤ 15 días; extraordinaria ≤ 15 días después; especial por Consejo Universitario ≤ 15 días más (#11) |
| Segunda / tercera matrícula | Marco RRA art. 74 (#1) | Marco RRA art. 74 | Marco RRA art. 74 | Tercera por Consejo de Facultad con causales: promedio ≥ "buena", única materia pendiente, calamidad, enfermedad, maternidad (#11) |
| Retiros | Marco RRA art. 73 | Marco RRA art. 73 | Marco RRA art. 73 | Voluntario ≤ 30 días desde inicio de clases; fuerza mayor en cualquier momento, con reembolso proporcional (#11) |
| Homologaciones | Marco RRA art. 82 (80 %, 10 años) | Marco RRA art. 82 | Marco RRA art. 82 | Reglamento de homologación 2024 existe; no homologa a quien tuvo dos matrículas en la misma materia en otra IES (#11) |
| Becas | Vulnerabilidad socioeconómica, mérito deportivo (#20) | Seguimiento del rendimiento de becarios (#23) | Comité de Becas; Equidad Social por quintil; mejor bachiller 20–29 %; mantenimiento ≥ 90/100 (80–89 → 15 %); Distinción académica 30 % con ≥ 95/100 (#16); crédito CERS con 75/100 (#18) | Todos con beca socioeconómica parcial; becas hasta 100 % (socioeconómica, discapacidad, deportiva, académica); ficha socioeconómica previa (#12, #13) |
| Cómo se paga | No paga (gratuidad), salvo pérdida de gratuidad | **S** | **S** | Matrícula USD 198 + arancel semestral USD 2.439,84–2.815,20 por carrera, con descuento de beca (#12); medios **S** |

## Decisiones de diseño adoptadas

| Punto | Decisión en UNISFERA | Por qué |
|---|---|---|
| Escala, nota mínima y asistencia | Tres **parámetros con vigencia** (`grading.scale_max`, `grading.passing_grade`, `attendance.min_percentage`). Seed: 0–100, 70 y 75 %. El esquema de evaluación de cada período **copia** los valores vigentes al activarse. | El RRA (art. 67 c) deja la escala a cada IES y las cuatro difieren; la UDA cambió su escala en pandemia. Copiar al esquema evita que un cambio de parámetro altere actas ya cerradas. |
| Aportes y pesos | `evaluation_scheme` por carrera y período con N componentes; la **base exige** que sumen 100 (trigger diferido) y un peso máximo por componente parametrizable. | Ninguna universidad publica un esquema único; el RRA pide ≥ 2 evaluaciones formativas y 2 sumativas. |
| Matrícula | Período con ventanas ordinaria / extraordinaria / especial; extraordinaria con recargo del tarifario; especial solo con autorización registrada. | Modelo UDA (#11) alineado con RRA art. 71. |
| Segunda y tercera matrícula | Número de matrícula calculado **por la base** (trigger) contando intentos previos que no fueron retirados ni anulados; la tercera exige `authorization` con causal del catálogo; `CHECK attempt_number BETWEEN 1 AND 3` hace imposible la cuarta. | RRA arts. 73–74 y causales UDA. |
| Retiros | Voluntario dentro de `academic.withdrawal_deadline_days` (seed 30) desde el inicio de clases; fuerza mayor en cualquier momento con aprobación; libera el cupo y no cuenta como intento. | UDA (#11) + RRA art. 73. |
| Homologaciones | Cuatro métodos (análisis de contenidos, validación de conocimientos, ejercicio profesional, reconocimiento interno); cobertura mínima `homologation.min_content_pct` (80) y antigüedad máxima `homologation.max_years` (10). Cuentan como aprobadas para prerrequisitos y kardex. | RRA arts. 81–84. |
| Kardex | Vista `v_kardex` que **solo** lee actas cerradas vigentes + homologaciones aprobadas; promedio ponderado por horas. | RN-16 y RN-17: una nota en borrador no existe para el historial. |
| Becas | Catálogo `scholarship_type` (porcentaje o monto, rubros cubiertos, mínimos de promedio y asistencia, mantenimiento escalonado); convocatorias con documentos obligatorios; evaluación por criterios con puntaje; resolución de comité con miembros. | Comité y escalones de la UPS (#16); ficha socioeconómica de la UDA (#13); 10 % LOES (#3). |
| Pagos | Matrícula + arancel por crédito (tarifario versionado), plan de cuotas, pago en línea por adaptador de pasarela con simulador completo, facturación electrónica con clave de acceso del SRI. | Estructura de aranceles UDA (#12); SRI ficha off-line (#7, #8). |
| Pasarela real | Primera candidata: **Kushki** (sandbox sin contrato y webhooks, #25). Payphone como segunda (redirección + confirmación, #24). Datafast y Deuna requieren contrato o intermediario. | Permite probar con una pasarela real sin firmar contrato; el dominio no cambia (puerto `PaymentGateway`). |

## Comparativa de pasarelas

| Pasarela | Pruebas sin contrato | Notificación del resultado | Datos que pide | Costos |
|---|---|---|---|---|
| Payphone | No confirmado (requiere Payphone Business + usuario desarrollador) | Redirección a `responseUrl` con `id` y `clientTransactionId`; el backend confirma por API | Monto en centavos, `clientTransactionId` único, referencia, datos de facturación | No públicos (S) |
| Kushki | Sí: sandbox multi-país sin costo | Webhooks por evento + consulta | Token de tarjeta (el número nunca toca nuestro servidor), monto, moneda, metadatos | Por contrato (S) |
| Datafast (Dataweb) | No: credenciales y ambiente se solicitan a Datafast | Retorno del widget + consulta | Según contrato | No públicos (S) |
| Deuna (Banco Pichincha) | Vía intermediario (Payválida) con sandbox | Consulta / notificación del intermediario | Comercio, orden, checksum SHA-512 | No públicos (S) |

**Decisión**: puerto `PaymentGateway` (crear intención, interpretar notificación firmada, consultar estado) y adaptador `SimulatedPaymentGateway` con cuatro escenarios: **aprobado**, **rechazado**, **demora** (queda pendiente y se confirma por consulta) y **doble notificación** (envía el mismo evento dos veces). Las notificaciones llegan firmadas con HMAC-SHA256 y se procesan de forma idempotente.
