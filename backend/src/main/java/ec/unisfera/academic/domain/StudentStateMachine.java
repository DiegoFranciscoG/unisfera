package ec.unisfera.academic.domain;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.util.Map;
import java.util.Set;

/**
 * RN-30: máquina de estados del estudiante alimentada por el catálogo de la base
 * (student_status_transition). El dominio y el trigger aplican la misma tabla.
 */
public final class StudentStateMachine {

    public record Transition(String from, String to, boolean requiresProcess) {
    }

    private final Map<String, Map<String, Transition>> transitions;

    public StudentStateMachine(Set<Transition> catalog) {
        this.transitions = catalog.stream().collect(java.util.stream.Collectors.groupingBy(Transition::from,
                java.util.stream.Collectors.toMap(Transition::to, t -> t)));
    }

    public void check(String from, String to, String process) {
        Transition t = transitions.getOrDefault(from, Map.of()).get(to);
        if (t == null) {
            throw new BusinessRuleException("RN-30", "Transición de estado no permitida (" + from + " → " + to + ")");
        }
        if (t.requiresProcess() && (process == null || process.isBlank())) {
            throw new BusinessRuleException("RN-30", "La transición " + from + " → " + to + " requiere un proceso explícito");
        }
    }

    public boolean canEnroll(String status) {
        return Set.of("ADMITTED", "ENROLLED", "REGULAR").contains(status);
    }
}
