package ec.unisfera.identity.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id con los parámetros mínimos recomendados por OWASP (m = 19 MiB, t = 2, p = 1).
 * Fuente: OWASP Password Storage Cheat Sheet (investigacion.md #31).
 */
public final class PasswordHashing {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;

    private PasswordHashing() {
    }

    public static PasswordEncoder encoder() {
        return new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
