package ec.unisfera.finance.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Clave de acceso de 49 dígitos del SRI (ficha técnica de comprobantes electrónicos, esquema
 * off-line; investigacion.md #7–#8): fecha (8) + tipo de comprobante (2) + RUC (13) + ambiente (1)
 * + serie (6) + secuencial (9) + código numérico (8) + tipo de emisión (1) + dígito módulo 11 (1).
 * Las constantes son del algoritmo del SRI, no valores de negocio.
 */
public final class AccessKeyGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final int[] WEIGHTS = {2, 3, 4, 5, 6, 7};
    private static final int MODULE = 11;
    private static final String NORMAL_EMISSION = "1";
    private static final int LENGTH_WITHOUT_CHECK = 48;

    private AccessKeyGenerator() {
    }

    public static String generate(LocalDate issueDate, String documentType, String ruc, String environment, String establishment,
                                  String emissionPoint, long sequential, String numericCode) {
        String body = issueDate.format(DATE) + documentType + ruc + environment + establishment + emissionPoint
                + String.format("%09d", sequential) + numericCode + NORMAL_EMISSION;
        if (body.length() != LENGTH_WITHOUT_CHECK || !body.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("La clave de acceso debe tener 48 dígitos antes del verificador");
        }
        return body + checkDigit(body);
    }

    /** Módulo 11 con pesos 2..7 de derecha a izquierda; 11 → 0 y 10 → 1. */
    public static int checkDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            sum += digit * WEIGHTS[i % WEIGHTS.length];
        }
        int result = MODULE - (sum % MODULE);
        if (result == MODULE) {
            return 0;
        }
        return result == MODULE - 1 ? 1 : result;
    }

    public static boolean isValid(String accessKey) {
        return accessKey != null && accessKey.length() == LENGTH_WITHOUT_CHECK + 1 && accessKey.chars().allMatch(Character::isDigit)
                && checkDigit(accessKey.substring(0, LENGTH_WITHOUT_CHECK)) == accessKey.charAt(LENGTH_WITHOUT_CHECK) - '0';
    }
}
