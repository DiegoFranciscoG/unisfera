package ec.unisfera.academic.domain;

import java.util.UUID;

/** Regla incumplida con su código, la asignatura afectada (si aplica) y un mensaje en lenguaje claro. */
public record Violation(String code, UUID subjectId, String message) {
}
