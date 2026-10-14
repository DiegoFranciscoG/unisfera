package ec.unisfera.parameters.domain;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Todas las versiones de un parámetro; resuelve el valor vigente en cualquier instante (RN-26). */
public record ParameterTimeline(String key, List<ParameterVersion> versions) {

    public ParameterTimeline {
        versions = versions.stream().sorted(Comparator.comparing(ParameterVersion::validFrom)).toList();
    }

    public ParameterVersion at(Instant instant) {
        return versions.stream()
                .filter(v -> v.isValidAt(instant))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("LEY-1", "El parámetro " + key + " no tiene valor vigente en " + instant));
    }
}
