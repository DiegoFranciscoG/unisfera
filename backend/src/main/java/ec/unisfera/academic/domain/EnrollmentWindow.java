package ec.unisfera.academic.domain;

import java.time.LocalDate;
import java.util.Optional;

/** RN-01: qué tipo de matrícula corresponde a una fecha (RRA art. 71). */
public record EnrollmentWindow(LocalDate ordinaryFrom, LocalDate ordinaryTo, LocalDate extraordinaryTo, LocalDate specialTo) {

    public enum Type { ORDINARY, EXTRAORDINARY, SPECIAL }

    /** Tipo por fecha; la especial además exige una autorización registrada. */
    public Optional<Type> typeOn(LocalDate day, boolean hasSpecialAuthorization) {
        if (!day.isBefore(ordinaryFrom) && !day.isAfter(ordinaryTo)) {
            return Optional.of(Type.ORDINARY);
        }
        if (day.isAfter(ordinaryTo) && !day.isAfter(extraordinaryTo)) {
            return Optional.of(Type.EXTRAORDINARY);
        }
        if (day.isAfter(extraordinaryTo) && !day.isAfter(specialTo) && hasSpecialAuthorization) {
            return Optional.of(Type.SPECIAL);
        }
        return Optional.empty();
    }
}
