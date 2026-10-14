package ec.unisfera.shared.domain;

/**
 * Límites técnicos (tamaños de columna y de listas recientes). No son reglas de negocio: reflejan
 * el esquema de la base y la paginación de la API, por eso viven aquí y no en la tabla parameter.
 */
public final class TechnicalLimits {

    public static final int USERNAME = 40;
    public static final int REASON_SNIPPET = 150;
    public static final int DESCRIPTION = 200;
    public static final int AUDIT_DETAIL = 500;
    public static final int RAW_PAYLOAD = 2000;
    public static final int RECENT_PAYMENTS_SELF = 30;
    public static final int RECENT_PAYMENTS_ADMIN = 50;

    private TechnicalLimits() {
    }

    public static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
