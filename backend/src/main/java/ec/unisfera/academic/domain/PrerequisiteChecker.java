package ec.unisfera.academic.domain;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** RN-02: prerrequisitos aprobados y correquisitos en el mismo período (o ya aprobados). */
public final class PrerequisiteChecker {

    private PrerequisiteChecker() {
    }

    public static Set<UUID> missingPrerequisites(Collection<UUID> prerequisites, Set<UUID> approved) {
        return prerequisites.stream().filter(p -> !approved.contains(p)).collect(Collectors.toUnmodifiableSet());
    }

    public static Set<UUID> missingCorequisites(Collection<UUID> corequisites, Set<UUID> approved, Set<UUID> selected) {
        return corequisites.stream().filter(c -> !approved.contains(c) && !selected.contains(c)).collect(Collectors.toUnmodifiableSet());
    }
}
