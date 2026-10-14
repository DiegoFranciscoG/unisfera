package ec.unisfera.identity.domain;

import java.time.Duration;

/**
 * Retroceso progresivo (SEC-03): sin bloqueo hasta el máximo de intentos; luego el bloqueo
 * empieza en la base y se duplica con cada fallo adicional, hasta un tope.
 */
public final class LockoutPolicy {

    private static final int MAX_DOUBLINGS = 20;

    private LockoutPolicy() {
    }

    public static Duration lockFor(int failedAttempts, int maxAttempts, long baseSeconds, long maxSeconds) {
        if (failedAttempts < maxAttempts) {
            return Duration.ZERO;
        }
        int doublings = Math.min(failedAttempts - maxAttempts, MAX_DOUBLINGS);
        long seconds = Math.min(baseSeconds << doublings, maxSeconds);
        return Duration.ofSeconds(seconds);
    }
}
