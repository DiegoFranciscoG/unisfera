package ec.unisfera.shared.web;

import java.util.Locale;

/** Cada código de regla enlaza a la sección del manual que la explica en lenguaje claro. */
public final class RuleHelp {

    private RuleHelp() {
    }

    public static String helpUrl(String code) {
        if (code == null) {
            return null;
        }
        String manual = switch (code) {
            case "RN-01", "RN-03", "RN-04", "RN-05", "RN-06", "RN-08", "RN-09", "RN-10" -> "como-me-matriculo";
            case "RN-02" -> "prerrequisitos";
            case "RN-07" -> "si-repruebo";
            case "RN-11", "RN-12", "RN-13", "RN-14", "RN-15" -> "como-leo-mis-notas";
            case "RN-16", "RN-17" -> "kardex-y-promedio";
            case "RN-18", "RN-19", "RN-20", "RN-21" -> "como-postulo-a-una-beca";
            case "RN-22", "RN-23" -> "como-mantengo-mi-beca";
            case "RN-24", "RN-25", "RN-26", "RN-27", "RN-28", "RN-29" -> "como-pago-en-linea";
            case "SEC-01", "SEC-02", "SEC-03", "SEC-04" -> "primer-ingreso";
            default -> "preguntas-frecuentes";
        };
        return "/ayuda/" + manual + "#" + code.toLowerCase(Locale.ROOT);
    }
}
