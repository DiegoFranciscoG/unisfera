package ec.unisfera.shared.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Identificadores UUID versión 7 (RFC 9562): 48 bits de milisegundos + aleatoriedad.
 * Ordenados por tiempo (buena localidad en índices B-tree) y no enumerables.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
    private static final long VERSION_BITS = 0x7000L;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final int VERSION_SHIFT = 16;

    private UuidV7() {
    }

    public static UUID next() {
        return next(Clock.systemUTC());
    }

    public static UUID next(Clock clock) {
        long millis = clock.millis() & TIMESTAMP_MASK;
        long mostSignificant = (millis << VERSION_SHIFT) | VERSION_BITS | (RANDOM.nextInt() & RAND_A_MASK);
        long leastSignificant = (RANDOM.nextLong() & RAND_B_MASK) | VARIANT_BITS;
        return new UUID(mostSignificant, leastSignificant);
    }
}
