# Política de seguridad

## Reportar una vulnerabilidad
No abras un issue público. Escríbeme por GitHub (perfil DiegoFranciscoG) con los pasos para reproducir el problema. Respondo en un máximo de 7 días.

## Prácticas aplicadas en UNISFERA
- **Secretos** solo por variables de entorno; la aplicación no arranca si falta uno crítico (`JWT_SECRET`, `DB_PASSWORD`, `SENSITIVE_DATA_KEY`, `ACT_SIGNING_KEY`, `GATEWAY_WEBHOOK_SECRET`, `SUPERADMIN_INITIAL_PASSWORD`). `.env` no se versiona.
- **Autenticación**: cédula/pasaporte + contraseña Argon2id (parámetros OWASP), contraseña temporal de un solo uso, cambio obligatorio, bloqueo progresivo, segundo factor TOTP obligatorio para roles administrativos y financieros, tokens de acceso de vida corta y refresco rotativo revocable.
- **Autorización** deny-by-default con permisos granulares en base de datos y filtrado por identidad (un estudiante solo ve lo suyo; un docente, sus paralelos).
- **Datos sensibles** (LOPDP): atenciones de bienestar cifradas con AES-256-GCM, acceso exclusivo del rol BIENESTAR y cada lectura auditada.
- **Integridad**: la base de datos prohíbe `DELETE` en datos de negocio, la auditoría es append-only y las actas cerradas son inmutables.
- **Webhooks** de pasarela firmados con HMAC-SHA256 y procesados de forma idempotente.
- **Cadena de suministro**: versiones fijas, gitleaks y Trivy en CI, Dependabot semanal, imágenes Docker con etiqueta exacta y usuario no root.
