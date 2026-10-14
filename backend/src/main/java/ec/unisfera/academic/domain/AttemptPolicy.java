package ec.unisfera.academic.domain;

/**
 * RN-07: número de matrícula en una asignatura. El tope de tres es legal (RRA art. 74, LOES
 * art. 84), no institucional; la base lo refuerza con CHECK (attempt_number BETWEEN 1 AND 3).
 * Los retiros y las anulaciones no cuentan (RRA art. 73).
 */
public final class AttemptPolicy {

    public static final int LEGAL_MAX_ATTEMPTS = 3;

    public enum Outcome { ALLOWED, NEEDS_AUTHORIZATION, EXHAUSTED }

    private AttemptPolicy() {
    }

    public static int nextAttempt(int countedPreviousAttempts) {
        return countedPreviousAttempts + 1;
    }

    public static Outcome evaluate(int countedPreviousAttempts, boolean hasThirdAttemptAuthorization) {
        int next = nextAttempt(countedPreviousAttempts);
        if (next > LEGAL_MAX_ATTEMPTS) {
            return Outcome.EXHAUSTED;
        }
        if (next == LEGAL_MAX_ATTEMPTS && !hasThirdAttemptAuthorization) {
            return Outcome.NEEDS_AUTHORIZATION;
        }
        return Outcome.ALLOWED;
    }
}
