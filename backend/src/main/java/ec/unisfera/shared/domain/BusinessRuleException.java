package ec.unisfera.shared.domain;

/**
 * Violación de una regla de negocio. El código (RN-xx, LEY-x, SEC-xx) viaja hasta la API,
 * aparece en el nombre del test que la cubre y enlaza al manual que la explica.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
