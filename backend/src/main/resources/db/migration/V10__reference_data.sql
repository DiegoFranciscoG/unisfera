-- =============================================================================
-- V10 · Datos de referencia: roles, permisos (en BD, nunca en código), tipos de
--       autorización, transiciones de estado, conceptos de cobro y parámetros.
-- Los valores de parámetros son el escenario sintético por defecto (LEY-1):
-- se cambian creando una nueva versión con vigencia, sin recompilar.
-- Los marcados como SUPUESTO en docs/investigacion.md deben confirmarse con la institución.
-- =============================================================================

insert into permission (code, module, description, is_write)
values ('USER_ADMIN', 'identity', 'Crear cuentas, restablecer credenciales y asignar roles', true),
       ('PERSON_READ', 'identity', 'Consultar fichas de personas', false),
       ('PERSON_WRITE', 'identity', 'Registrar y corregir fichas de personas', true),
       ('AUDIT_READ', 'audit', 'Consultar la auditoría', false),
       ('PARAMETER_READ', 'parameters', 'Consultar parámetros y su historial', false),
       ('PARAMETER_WRITE', 'parameters', 'Publicar nuevas versiones de parámetros', true),
       ('CATALOG_READ', 'institution', 'Consultar carreras, mallas, períodos y paralelos', false),
       ('CATALOG_WRITE', 'institution', 'Configurar carreras, mallas, períodos, paralelos y horarios', true),
       ('STUDENT_READ', 'academic', 'Consultar cualquier estudiante', false),
       ('STUDENT_WRITE', 'academic', 'Dar de alta estudiantes y cambiar su estado', true),
       ('ADMISSION_MANAGE', 'academic', 'Gestionar aspirantes y admisión', true),
       ('TEACHER_READ', 'academic', 'Consultar docentes y distributivo', false),
       ('TEACHER_MANAGE', 'academic', 'Gestionar docentes y distributivo', true),
       ('ENROLLMENT_READ', 'academic', 'Consultar matrículas', false),
       ('ENROLLMENT_MANAGE', 'academic', 'Matricular a terceros, carga masiva y anulación', true),
       ('ACADEMIC_EXCEPTION_GRANT', 'academic', 'Autorizar tercera matrícula, matrícula especial, exceso de carga y rectificaciones', true),
       ('WITHDRAWAL_DECIDE', 'academic', 'Resolver retiros', true),
       ('HOMOLOGATION_MANAGE', 'academic', 'Registrar y resolver homologaciones', true),
       ('GRADE_READ', 'evaluation', 'Consultar notas y kardex de cualquier estudiante', false),
       ('ACT_READ', 'evaluation', 'Consultar actas', false),
       ('TEACHING_OWN_SECTIONS', 'evaluation', 'Registrar notas, asistencia y cerrar actas de sus propios paralelos', true),
       ('SELF_SERVICE', 'academic', 'Portal del estudiante: solo sus propios datos', true),
       ('FINANCE_READ', 'finance', 'Consultar cartera, pagos y facturas', false),
       ('TARIFF_MANAGE', 'finance', 'Publicar tarifas', true),
       ('PAYMENT_MANAGE', 'finance', 'Registrar y anular pagos', true),
       ('DEBT_EXCEPTION_GRANT', 'finance', 'Autorizar matrícula con deuda vencida', true),
       ('INVOICE_MANAGE', 'finance', 'Emitir facturas y notas de crédito', true),
       ('RECONCILIATION_MANAGE', 'finance', 'Conciliar extractos', true),
       ('SCHOLARSHIP_READ', 'welfare', 'Consultar becas y postulaciones', false),
       ('SCHOLARSHIP_MANAGE', 'welfare', 'Gestionar tipos de beca, convocatorias y evaluación', true),
       ('COMMITTEE_RESOLVE', 'welfare', 'Registrar resoluciones del comité de becas', true),
       ('WELFARE_SENSITIVE', 'welfare', 'Leer y registrar atenciones de salud y psicología (dato sensible)', true),
       ('DROPOUT_MANAGE', 'welfare', 'Gestionar alertas de deserción', true),
       ('REPORT_READ', 'reporting', 'Consultar reportes institucionales', false),
       ('REPORT_EXPORT', 'reporting', 'Exportar reportes a Excel y PDF', false),
       ('REQUEST_MANAGE', 'academic', 'Resolver solicitudes de estudiantes', true);

insert into role (code, name, description, requires_mfa, read_only)
values ('SUPERADMIN', 'Superadministrador', 'Administración total, sin acceso a datos sensibles de bienestar', true, false),
       ('ACADEMIC_ADMIN', 'Administrador académico', 'Configura la oferta académica y autoriza excepciones', true, false),
       ('REGISTRAR', 'Secretaría', 'Alta de estudiantes, matrículas, retiros y solicitudes', true, false),
       ('FINANCE', 'Financiero', 'Tarifario, cartera, pagos, facturación y conciliación', true, false),
       ('WELFARE', 'Bienestar', 'Becas, ayudas, atenciones y alertas de deserción', true, false),
       ('TEACHER', 'Docente', 'Sus paralelos: notas, asistencia y actas', false, false),
       ('STUDENT', 'Estudiante', 'Portal del estudiante', false, false),
       ('AUDITOR', 'Auditor', 'Solo lectura de todo, excepto datos sensibles de salud', true, true);

insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r
         join permission p on (
    (r.code = 'SUPERADMIN' and p.code not in ('WELFARE_SENSITIVE', 'TEACHING_OWN_SECTIONS', 'SELF_SERVICE'))
        or (r.code = 'ACADEMIC_ADMIN' and p.code in ('USER_ADMIN', 'PERSON_READ', 'PERSON_WRITE', 'PARAMETER_READ', 'PARAMETER_WRITE', 'CATALOG_READ', 'CATALOG_WRITE',
                                                     'STUDENT_READ', 'STUDENT_WRITE', 'ADMISSION_MANAGE', 'TEACHER_READ', 'TEACHER_MANAGE', 'ENROLLMENT_READ',
                                                     'ENROLLMENT_MANAGE', 'ACADEMIC_EXCEPTION_GRANT', 'WITHDRAWAL_DECIDE', 'HOMOLOGATION_MANAGE', 'GRADE_READ',
                                                     'ACT_READ', 'REPORT_READ', 'REPORT_EXPORT', 'REQUEST_MANAGE', 'AUDIT_READ'))
        or (r.code = 'REGISTRAR' and p.code in ('USER_ADMIN', 'PERSON_READ', 'PERSON_WRITE', 'CATALOG_READ', 'STUDENT_READ', 'STUDENT_WRITE', 'ADMISSION_MANAGE',
                                                'TEACHER_READ', 'ENROLLMENT_READ', 'ENROLLMENT_MANAGE', 'WITHDRAWAL_DECIDE', 'HOMOLOGATION_MANAGE',
                                                'GRADE_READ', 'ACT_READ', 'REPORT_READ', 'REPORT_EXPORT', 'REQUEST_MANAGE', 'PARAMETER_READ'))
        or (r.code = 'FINANCE' and p.code in ('PERSON_READ', 'CATALOG_READ', 'STUDENT_READ', 'ENROLLMENT_READ', 'FINANCE_READ', 'TARIFF_MANAGE', 'PAYMENT_MANAGE',
                                              'DEBT_EXCEPTION_GRANT', 'INVOICE_MANAGE', 'RECONCILIATION_MANAGE', 'SCHOLARSHIP_READ', 'REPORT_READ',
                                              'REPORT_EXPORT', 'PARAMETER_READ'))
        or (r.code = 'WELFARE' and p.code in ('PERSON_READ', 'CATALOG_READ', 'STUDENT_READ', 'GRADE_READ', 'SCHOLARSHIP_READ', 'SCHOLARSHIP_MANAGE',
                                              'COMMITTEE_RESOLVE', 'WELFARE_SENSITIVE', 'DROPOUT_MANAGE', 'REPORT_READ', 'REPORT_EXPORT', 'PARAMETER_READ'))
        or (r.code = 'TEACHER' and p.code in ('TEACHING_OWN_SECTIONS'))
        or (r.code = 'STUDENT' and p.code in ('SELF_SERVICE'))
        or (r.code = 'AUDITOR' and p.code in ('PERSON_READ', 'AUDIT_READ', 'PARAMETER_READ', 'CATALOG_READ', 'STUDENT_READ', 'TEACHER_READ', 'ENROLLMENT_READ',
                                              'GRADE_READ', 'ACT_READ', 'FINANCE_READ', 'SCHOLARSHIP_READ', 'REPORT_READ', 'REPORT_EXPORT'))
    );

insert into authorization_type (type, required_permission, description)
values ('THIRD_ENROLLMENT', 'ACADEMIC_EXCEPTION_GRANT', 'Tercera matrícula (LOES art. 84; RRA art. 74)'),
       ('SPECIAL_ENROLLMENT', 'ACADEMIC_EXCEPTION_GRANT', 'Matrícula especial por caso fortuito o fuerza mayor (RRA art. 71 c)'),
       ('LOAD_EXCEPTION', 'ACADEMIC_EXCEPTION_GRANT', 'Exceso del tope de asignaturas por período'),
       ('ACT_RECTIFICATION', 'ACADEMIC_EXCEPTION_GRANT', 'Rectificación de un acta cerrada'),
       ('LATE_WITHDRAWAL', 'ACADEMIC_EXCEPTION_GRANT', 'Retiro voluntario fuera de plazo'),
       ('DEBT_EXCEPTION', 'DEBT_EXCEPTION_GRANT', 'Matrícula con valores vencidos'),
       ('RETROACTIVE_SCHOLARSHIP', 'COMMITTEE_RESOLVE', 'Aplicación retroactiva de una beca por resolución expresa');

insert into student_status_transition (from_status, to_status, requires_process, description)
values ('ADMITTED', 'ENROLLED', false, 'Primera matrícula'),
       ('ADMITTED', 'WITHDRAWN', false, 'Desiste antes de matricularse'),
       ('ENROLLED', 'REGULAR', false, 'Matriculado en al menos el porcentaje mínimo de su malla (RRA art. 76)'),
       ('ENROLLED', 'WITHDRAWN', false, 'Retiro de la carrera'),
       ('ENROLLED', 'SUSPENDED', true, 'Sanción o tercera reprobación (RRA art. 74)'),
       ('REGULAR', 'WITHDRAWN', false, 'Retiro de la carrera'),
       ('REGULAR', 'SUSPENDED', true, 'Sanción o tercera reprobación (RRA art. 74)'),
       ('REGULAR', 'COMPLETED', false, 'Egresado: aprobó todas las asignaturas de su malla'),
       ('COMPLETED', 'GRADUATED', false, 'Titulación aprobada y registrada'),
       ('WITHDRAWN', 'REGULAR', true, 'Reingreso dentro de 10 años (RRA art. 78)'),
       ('SUSPENDED', 'REGULAR', true, 'Rehabilitación por resolución');

insert into charge_concept (code, name, kind, tax_category, discountable)
values ('MATRICULA', 'Matrícula', 'ENROLLMENT', 'EDUCATION', true),
       ('ARANCEL', 'Arancel por crédito', 'TUITION', 'EDUCATION', true),
       ('RECARGO_EXTRA', 'Recargo por matrícula extraordinaria', 'SURCHARGE', 'EDUCATION', false),
       ('MORA', 'Interés por mora', 'LATE_FEE', 'EDUCATION', false),
       ('CERTIFICADO', 'Certificado académico', 'CERTIFICATE', 'EDUCATION', false),
       ('DERECHO_GRADO', 'Derechos de grado', 'SERVICE', 'EDUCATION', true);

insert into person_field_policy (field_name, student_editable, description)
values ('phone_mobile', true, 'Teléfono celular'),
       ('phone_home', true, 'Teléfono fijo'),
       ('address', true, 'Dirección domiciliaria'),
       ('personal_email', true, 'Correo personal'),
       ('emergency_contacts', true, 'Contactos de emergencia'),
       ('first_names', false, 'Nombres (solo Secretaría)'),
       ('last_names', false, 'Apellidos (solo Secretaría)'),
       ('id_number', false, 'Cédula o pasaporte (inmutable)'),
       ('birth_date', false, 'Fecha de nacimiento (solo Secretaría)'),
       ('sex', false, 'Sexo registral (solo Secretaría)'),
       ('marital_status', false, 'Estado civil (solo Secretaría)'),
       ('nationality', false, 'Nacionalidad (solo Secretaría)');

-- Parámetros (vigentes desde 2020-01-01 en hora de Ecuador)
insert into parameter (key, value, data_type, unit, description, valid_from)
values ('grading.scale_min', '0', 'DECIMAL', 'puntos', 'Nota mínima de la escala (RRA art. 67 c; SUPUESTO 1)', '2020-01-01 00:00-05'),
       ('grading.scale_max', '100', 'DECIMAL', 'puntos', 'Nota máxima de la escala (SUPUESTO 1)', '2020-01-01 00:00-05'),
       ('grading.passing_grade', '70', 'DECIMAL', 'puntos', 'Nota mínima de aprobación (SUPUESTO 1)', '2020-01-01 00:00-05'),
       ('grading.max_component_weight', '60', 'DECIMAL', '%', 'Peso máximo de un componente (UDA posgrado: final ≤ 60 %)', '2020-01-01 00:00-05'),
       ('grading.min_components', '3', 'INTEGER', 'componentes', 'Mínimo de componentes por esquema (RRA art. 66)', '2020-01-01 00:00-05'),
       ('attendance.min_percentage', '75', 'DECIMAL', '%', 'Asistencia mínima (UDA: reprueba con más de 25 % de faltas)', '2020-01-01 00:00-05'),
       ('attendance.warning_margin_pct', '10', 'DECIMAL', '%', 'Margen sobre el mínimo para avisar al estudiante antes del cierre', '2020-01-01 00:00-05'),
       ('enrollment.max_subjects_per_period', '7', 'INTEGER', 'asignaturas', 'Tope de asignaturas por período (RN-04)', '2020-01-01 00:00-05'),
       ('enrollment.regular_min_load_pct', '60', 'DECIMAL', '%', 'Carga mínima para ser estudiante regular (RRA art. 76)', '2020-01-01 00:00-05'),
       ('academic.withdrawal_deadline_days', '30', 'INTEGER', 'días', 'Plazo de retiro voluntario desde el inicio de clases (UDA art. 10)', '2020-01-01 00:00-05'),
       ('homologation.min_content_pct', '80', 'DECIMAL', '%', 'Cobertura mínima para homologar por contenidos (RRA art. 82)', '2020-01-01 00:00-05'),
       ('homologation.max_years', '10', 'INTEGER', 'años', 'Antigüedad máxima para homologar (RRA art. 82)', '2020-01-01 00:00-05'),
       ('kardex.average_includes_failed', 'false', 'BOOLEAN', null, 'Si el promedio incluye asignaturas reprobadas', '2020-01-01 00:00-05'),
       ('finance.currency', 'USD', 'STRING', null, 'Moneda oficial del Ecuador', '2020-01-01 00:00-05'),
       ('finance.block_enrollment_on_overdue_debt', 'true', 'BOOLEAN', null, 'La deuda vencida bloquea la matrícula (RN-08)', '2020-01-01 00:00-05'),
       ('finance.grace_days', '5', 'INTEGER', 'días', 'Días de gracia antes de considerar vencida una cuota (SUPUESTO 10)', '2020-01-01 00:00-05'),
       ('finance.late_fee_monthly_rate', '1.1', 'DECIMAL', '% mensual', 'Interés de mora mensual sobre el capital vencido (SUPUESTO 10)', '2020-01-01 00:00-05'),
       ('finance.extraordinary_surcharge_pct', '10', 'DECIMAL', '%', 'Recargo de la matrícula extraordinaria sobre la matrícula (SUPUESTO 10)', '2020-01-01 00:00-05'),
       ('finance.installments_count', '5', 'INTEGER', 'cuotas', 'Número de cuotas del plan de pagos', '2020-01-01 00:00-05'),
       ('finance.installment_interval_days', '30', 'INTEGER', 'días', 'Separación entre cuotas', '2020-01-01 00:00-05'),
       ('finance.first_installment_due_days', '7', 'INTEGER', 'días', 'Días desde la matrícula hasta el vencimiento de la primera cuota', '2020-01-01 00:00-05'),
       ('finance.discount_precedence', 'SCHOLARSHIP,FINANCIAL_AID,SIBLING,AGREEMENT,EARLY_PAYMENT', 'LIST', null, 'Orden de prelación de descuentos (RN-09; SUPUESTO 9)', '2020-01-01 00:00-05'),
       ('finance.payment_allocation_order', 'LATE_FEE,OLDEST_PRINCIPAL', 'LIST', null, 'Orden de aplicación de abonos (RN-25; SUPUESTO 9)', '2020-01-01 00:00-05'),
       ('tax.iva_rate_general', '15', 'DECIMAL', '%', 'IVA tarifa general desde abril de 2024 (SUPUESTO 4)', '2020-01-01 00:00-05'),
       ('tax.iva_rate_education', '0', 'DECIMAL', '%', 'IVA de servicios de educación (SUPUESTO 4)', '2020-01-01 00:00-05'),
       ('sri.environment', '1', 'STRING', null, 'Ambiente SRI: 1 pruebas, 2 producción (ficha técnica off-line)', '2020-01-01 00:00-05'),
       ('security.access_token_minutes', '10', 'INTEGER', 'minutos', 'Vida del token de acceso', '2020-01-01 00:00-05'),
       ('security.refresh_token_hours', '12', 'INTEGER', 'horas', 'Vida máxima del token de refresco', '2020-01-01 00:00-05'),
       ('security.inactivity_minutes', '30', 'INTEGER', 'minutos', 'Expiración de la sesión por inactividad', '2020-01-01 00:00-05'),
       ('security.challenge_token_minutes', '5', 'INTEGER', 'minutos', 'Vida del token de desafío (cambio de contraseña o segundo factor)', '2020-01-01 00:00-05'),
       ('security.max_failed_attempts', '5', 'INTEGER', 'intentos', 'Intentos fallidos antes del bloqueo temporal', '2020-01-01 00:00-05'),
       ('security.lockout_base_seconds', '30', 'INTEGER', 'segundos', 'Bloqueo inicial; se duplica con cada fallo adicional', '2020-01-01 00:00-05'),
       ('security.lockout_max_seconds', '3600', 'INTEGER', 'segundos', 'Bloqueo máximo', '2020-01-01 00:00-05'),
       ('security.password_min_length', '12', 'INTEGER', 'caracteres', 'Longitud mínima de contraseña', '2020-01-01 00:00-05'),
       ('security.password_max_age_days', '180', 'INTEGER', 'días', 'Caducidad de la contraseña', '2020-01-01 00:00-05'),
       ('security.password_history_count', '5', 'INTEGER', 'contraseñas', 'Contraseñas anteriores que no pueden repetirse', '2020-01-01 00:00-05'),
       ('security.temp_password_hours', '72', 'INTEGER', 'horas', 'Validez de la contraseña temporal de un solo uso', '2020-01-01 00:00-05'),
       ('security.reset_token_minutes', '15', 'INTEGER', 'minutos', 'Validez del enlace de recuperación', '2020-01-01 00:00-05'),
       ('security.rate_limit_login_per_minute', '10', 'INTEGER', 'peticiones', 'Intentos de ingreso por IP y minuto', '2020-01-01 00:00-05'),
       ('security.rate_limit_api_per_minute', '600', 'INTEGER', 'peticiones', 'Peticiones por usuario y minuto', '2020-01-01 00:00-05'),
       ('welfare.committee_min_members', '3', 'INTEGER', 'miembros', 'Quórum de la resolución del comité de becas (RN-20)', '2020-01-01 00:00-05'),
       ('welfare.min_scholarship_coverage_pct', '10', 'DECIMAL', '%', 'Becarios mínimos sobre estudiantes regulares (LOES art. 77)', '2020-01-01 00:00-05'),
       ('reports.small_cell_threshold', '5', 'INTEGER', 'personas', 'Celdas con menos personas se suprimen en reportes agregados (RN-23)', '2020-01-01 00:00-05'),
       ('requests.default_due_days', '15', 'INTEGER', 'días', 'Plazo de respuesta de solicitudes', '2020-01-01 00:00-05'),
       ('dropout.max_failed_subjects', '2', 'INTEGER', 'asignaturas', 'Reprobaciones en el período que activan alerta', '2020-01-01 00:00-05'),
       ('dropout.attendance_alert_pct', '80', 'DECIMAL', '%', 'Asistencia bajo la cual se activa alerta temprana', '2020-01-01 00:00-05'),
       ('uploads.max_size_bytes', '5242880', 'INTEGER', 'bytes', 'Tamaño máximo de un archivo adjunto', '2020-01-01 00:00-05'),
       ('uploads.allowed_types', 'application/pdf,image/png,image/jpeg', 'LIST', null, 'Tipos de archivo permitidos (validados por firma de bytes)', '2020-01-01 00:00-05');
