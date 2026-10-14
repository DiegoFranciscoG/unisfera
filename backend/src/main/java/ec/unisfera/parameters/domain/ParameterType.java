package ec.unisfera.parameters.domain;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.math.BigDecimal;
import java.time.LocalDate;

public enum ParameterType {
    DECIMAL, INTEGER, BOOLEAN, STRING, LIST, DATE;

    /** Valida que el texto sea un valor legítimo del tipo antes de publicarlo. */
    public void validate(String raw) {
        try {
            switch (this) {
                case DECIMAL -> new BigDecimal(raw);
                case INTEGER -> Long.parseLong(raw);
                case BOOLEAN -> {
                    if (!raw.equals("true") && !raw.equals("false")) {
                        throw new IllegalArgumentException(raw);
                    }
                }
                case DATE -> LocalDate.parse(raw);
                case STRING, LIST -> {
                    if (raw.isBlank()) {
                        throw new IllegalArgumentException("vacío");
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new BusinessRuleException("LEY-1", "El valor '" + raw + "' no es un " + name() + " válido");
        }
    }
}
