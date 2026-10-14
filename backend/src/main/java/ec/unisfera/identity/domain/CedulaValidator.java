package ec.unisfera.identity.domain;

/**
 * Validación de la cédula ecuatoriana (persona natural). Función pura.
 * <ul>
 *   <li>10 dígitos; provincia 01–24 o 30 (ecuatorianos inscritos en el exterior).</li>
 *   <li>Tercer dígito menor a 6 (persona natural); la serie 30 no lo valida.</li>
 *   <li>Dígito verificador módulo 10 con coeficientes 2-1-2-1-2-1-2-1-2.</li>
 * </ul>
 * Fuentes: investigacion.md #9 y #10. Las constantes son del algoritmo legal, no valores de negocio.
 */
public final class CedulaValidator {

    private static final int LENGTH = 10;
    private static final int MAX_PROVINCE = 24;
    private static final int ABROAD_PROVINCE = 30;
    private static final int NATURAL_PERSON_LIMIT = 6;
    private static final int[] COEFFICIENTS = {2, 1, 2, 1, 2, 1, 2, 1, 2};
    private static final int MODULE = 10;
    private static final int CARRY = 9;

    private CedulaValidator() {
    }

    public static boolean isValid(String cedula) {
        if (cedula == null || cedula.length() != LENGTH || !cedula.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int province = Integer.parseInt(cedula.substring(0, 2));
        if (!(province >= 1 && province <= MAX_PROVINCE) && province != ABROAD_PROVINCE) {
            return false;
        }
        if (province != ABROAD_PROVINCE && digit(cedula, 2) >= NATURAL_PERSON_LIMIT) {
            return false;
        }
        return checkDigit(cedula.substring(0, LENGTH - 1)) == digit(cedula, LENGTH - 1);
    }

    /** Dígito verificador de los 9 primeros dígitos. */
    public static int checkDigit(String firstNine) {
        int sum = 0;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            int product = digit(firstNine, i) * COEFFICIENTS[i];
            sum += product >= MODULE ? product - CARRY : product;
        }
        return (MODULE - sum % MODULE) % MODULE;
    }

    private static int digit(String s, int index) {
        return s.charAt(index) - '0';
    }
}
