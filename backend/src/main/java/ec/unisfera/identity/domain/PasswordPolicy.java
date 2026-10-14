package ec.unisfera.identity.domain;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Política de contraseñas (NIST SP 800-63B: longitud y lista de bloqueo, sin reglas de
 * composición arbitrarias). SEC-01: la cédula nunca es la contraseña ni parte de ella.
 */
public final class PasswordPolicy {

    private static final int ID_FRAGMENT = 6;
    private static final int MAX_LENGTH = 128;
    private static final List<String> BLOCKED = List.of("password", "contrasena", "unisfera", "universidad", "qwerty", "123456", "abcdef");

    private PasswordPolicy() {
    }

    public static void validate(String password, String idNumber, int minLength) {
        if (password == null || password.length() < minLength) {
            throw new BusinessRuleException("SEC-01", "La contraseña debe tener al menos " + minLength + " caracteres");
        }
        if (password.length() > MAX_LENGTH) {
            throw new BusinessRuleException("SEC-01", "La contraseña es demasiado larga");
        }
        if (password.chars().distinct().count() <= 2) {
            throw new BusinessRuleException("SEC-01", "La contraseña es demasiado repetitiva");
        }
        String normalized = normalize(password);
        if (idNumber != null) {
            String id = normalize(idNumber);
            for (int i = 0; i + ID_FRAGMENT <= id.length(); i++) {
                if (normalized.contains(id.substring(i, i + ID_FRAGMENT))) {
                    throw new BusinessRuleException("SEC-01", "La contraseña no puede contener su cédula ni parte de ella");
                }
            }
        }
        for (String blocked : BLOCKED) {
            if (normalized.contains(blocked)) {
                throw new BusinessRuleException("SEC-01", "La contraseña contiene una palabra demasiado común");
            }
        }
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
