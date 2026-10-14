package ec.unisfera.parameters.domain;

import java.time.Instant;
import java.util.UUID;

public record ParameterVersion(UUID id, String key, String value, ParameterType type, String unit, String description,
                               Instant validFrom, Instant validTo, String changeReason) {

    public boolean isValidAt(Instant at) {
        return !validFrom.isAfter(at) && (validTo == null || validTo.isAfter(at));
    }
}
