package ec.unisfera.identity.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Permiso granular exigido por un endpoint (basta con tener uno de la lista). Qué rol tiene qué
 * permiso vive en la base (role_permission), nunca en el código. Se verifica antes de leer el
 * cuerpo de la petición: un acceso indebido responde 403 aunque el cuerpo sea inválido.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    String[] value();
}
