package ec.unisfera.shared.domain;

import java.util.List;

/**
 * Violación de una regla de negocio. El código (RN-xx, LEY-x, SEC-xx) viaja hasta la API,
 * aparece en el nombre del test que la cubre y enlaza al manual que la explica.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final List<?> details;

    public BusinessRuleException(String code, String message) {
        this(code, message, List.of());
    }

    public BusinessRuleException(String code, String message, List<?> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public String code() {
        return code;
    }

    /** Todas las violaciones encontradas, para que la interfaz las muestre juntas. */
    public List<?> details() {
        return details;
    }
}
