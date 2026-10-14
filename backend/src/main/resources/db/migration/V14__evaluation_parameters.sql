-- =============================================================================
-- V14 · Parámetros de evaluación adicionales (LEY-1).
-- =============================================================================

insert into parameter (key, value, data_type, unit, description, valid_from)
values ('attendance.counted_statuses', 'PRESENT,LATE,JUSTIFIED', 'LIST', null,
        'Estados de asistencia que cuentan como asistida para el porcentaje (RN-15)', '2020-01-01 00:00-05'),
       ('evaluation.close_confirmation_phrase', 'CERRAR ACTA', 'STRING', null,
        'Frase que el docente escribe para confirmar el cierre del acta', '2020-01-01 00:00-05');
