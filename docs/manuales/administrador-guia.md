# Administración: guía general

## Roles y permisos

| Rol | Para qué sirve |
|---|---|
| Superadministrador | Configuración total, sin acceso a datos sensibles de bienestar |
| Administración académica | Oferta, mallas, esquemas y excepciones académicas |
| Secretaría | Estudiantes, matrículas, retiros y solicitudes |
| Financiero | Tarifario, pagos, facturación y conciliación |
| Bienestar | Becas, alertas de deserción y atenciones cifradas |
| Auditoría | Solo lectura de todo, excepto datos de salud |

Los roles administrativos exigen **segundo factor**. Cada permiso vive en la base de datos y se verifica en cada petición; la interfaz solo oculta lo que no puedes usar.

## Tablero

Indicadores en vivo del período: estudiantes activos, matrícula, ocupación, aprobación, cartera vencida, recaudación, becarios, alertas y solicitudes pendientes. Cada indicador lleva a su módulo.

## Estudiantes

Busca por nombre, código o cédula. La identificación se muestra enmascarada. La ficha 360° reúne avance de malla, kardex, estado de cuenta e historial de estados. Los cambios de estado siguen el catálogo de transiciones y quedan registrados.

## Oferta académica

Períodos con su calendario (ventanas de matrícula, clases y cierre de actas), paralelos con su ocupación y mallas por carrera con niveles y prerrequisitos. Una malla solo se modifica en borrador.

## Finanzas

- **Pagos presenciales**: registra efectivo, transferencia o tarjeta POS; se emite la factura electrónica (RIDE) con su clave de acceso de 49 dígitos.
- **Anulación**: requiere motivo y genera nota de crédito y reverso; nunca se borra un pago.
- **Mora**: se calcula cada noche; también puedes ejecutarla manualmente.
- **Conciliación**: sube el extracto CSV del banco o de la pasarela y revisa las diferencias.

## Reportes

Nueve reportes institucionales con vista previa y exportación a Excel o PDF en segundo plano. Las cifras menores al umbral vigente se suprimen para no identificar personas.

## Auditoría

Toda alta, cambio, intento denegado, lectura sensible y exportación queda en una bitácora de solo inserción con actor, IP y número de solicitud. Usa el número de solicitud que ve el usuario en un error para encontrar el evento exacto.
