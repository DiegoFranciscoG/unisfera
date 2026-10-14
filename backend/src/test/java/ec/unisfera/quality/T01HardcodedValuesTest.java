package ec.unisfera.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T-01 · LEY-1 (nada quemado): recorre todo el código de producción buscando literales que
 * parezcan valores de negocio (tarifas, notas mínimas, porcentajes, cupos, plazos). Cada excepción
 * está en la lista blanca con su justificación: constantes de algoritmos legales (cédula, SRI,
 * TOTP), parámetros criptográficos de OWASP o límites técnicos que no son reglas de negocio.
 */
class T01HardcodedValuesTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** Literales numéricos "sospechosos": decimales, o enteros de 2 o más dígitos. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(\\d+\\.\\d+|\\d{2,}(?:_\\d{3})*)(?![\\w.])");
    private static final Pattern NEW_BIGDECIMAL = Pattern.compile("new BigDecimal\\(\"[0-9.]+\"\\)|BigDecimal\\.valueOf\\(\\d+(\\.\\d+)?\\)");
    private static final Set<String> ALWAYS_OK = Set.of("100");
    /** Marca de lista blanca por línea: la justificación queda junto al literal. */
    private static final String TECHNICAL_MARK = "// técnico:";

    /** Archivo → justificación. Estas clases contienen constantes de algoritmos, protocolos o límites técnicos. */
    private static final Map<String, String> ALLOWLIST = Map.ofEntries(
            Map.entry("CedulaValidator.java", "Algoritmo módulo 10 del Registro Civil: provincias 01-24/30, coeficientes y longitud"),
            Map.entry("AccessKeyGenerator.java", "Ficha técnica SRI: longitud 48/49, pesos 2..7 y módulo 11"),
            Map.entry("Totp.java", "RFC 6238: pasos de 30 s, 6 dígitos, máscaras de truncado"),
            Map.entry("Base32.java", "RFC 4648: bits por carácter y máscaras"),
            Map.entry("UuidV7.java", "RFC 9562: máscaras de bits de versión y variante"),
            Map.entry("PasswordHashing.java", "OWASP Password Storage: m=19456 KiB, t=2, p=1"),
            Map.entry("CryptoService.java", "AES-256-GCM: tamaño de clave, IV de 12 bytes y etiqueta de 128 bits"),
            Map.entry("AttemptPolicy.java", "Tope legal de 3 matrículas (RRA art. 74), reforzado por CHECK en la base"),
            Map.entry("LateFeeCalculator.java", "Mes comercial de 30 días para prorratear la mora; la tasa y la gracia son parámetros"),
            Map.entry("PageQuery.java", "Límite técnico de paginación (OWASP API4), no regla de negocio"),
            Map.entry("SecureTokens.java", "Longitudes de tokens aleatorios"),
            Map.entry("FileStorageService.java", "Firmas de bytes de PDF/PNG/JPEG/ZIP"),
            Map.entry("InvoiceService.java", "Rango del código numérico de 8 dígitos de la clave de acceso"),
            Map.entry("GlobalExceptionHandler.java", "Códigos SQLSTATE de PostgreSQL"),
            Map.entry("CorrelationFilter.java", "Longitudes permitidas del request-id"),
            Map.entry("ExportService.java", "Ventana de filas en memoria de POI"),
            Map.entry("ManualService.java", "Márgenes y tamaños tipográficos del PDF"),
            Map.entry("PasswordPolicy.java", "Longitud máxima técnica y tamaño de fragmento de cédula; la mínima es parámetro"),
            Map.entry("LockoutPolicy.java", "Tope técnico de duplicaciones para evitar desbordamiento"),
            Map.entry("SimulatedPaymentGateway.java", "Código de autorización ficticio del simulador"),
            Map.entry("UserDirectory.java", "Tamaño de caché"),
            Map.entry("TechnicalLimits.java", "Tamaños de columna y de listas recientes (límites técnicos)"),
            Map.entry("GradeMath.java", "Escala decimal de 2 posiciones (centésimas)"));

    @Test
    void ley1_noBusinessLiteralsOutsideTheParameterTable() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (ALLOWLIST.containsKey(name) || file.toString().replace('\\', '/').contains("/seed/") || name.startsWith("V12__")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                boolean inTextBlock = false;
                for (int i = 0; i < lines.size(); i++) {
                    String original = lines.get(i);
                    int quotes = countTextBlockDelimiters(original);
                    boolean skip = inTextBlock || quotes > 0;
                    if (quotes % 2 == 1) {
                        inTextBlock = !inTextBlock;
                    }
                    if (skip || original.contains(TECHNICAL_MARK)) {
                        continue;
                    }
                    String line = normalize(original);
                    if (line.isBlank()) {
                        continue;
                    }
                    Matcher m = NUMBER.matcher(line);
                    while (m.find()) {
                        if (!ALWAYS_OK.contains(m.group(1))) {
                            offenders.add(file + ":" + (i + 1) + " → " + original.trim());
                        }
                    }
                    if (NEW_BIGDECIMAL.matcher(line).find()) {
                        offenders.add(file + ":" + (i + 1) + " (BigDecimal literal) → " + original.trim());
                    }
                }
            }
        }
        assertThat(offenders).as("Literales de negocio fuera de la tabla de parámetros (LEY-1)").isEmpty();
    }

    private static int countTextBlockDelimiters(String line) {
        int count = 0;
        int idx = line.indexOf("\"\"\"");
        while (idx >= 0) {
            count++;
            idx = line.indexOf("\"\"\"", idx + 3);
        }
        return count;
    }

    /**
     * Ignora lo que no es una constante de negocio del código: textos (mensajes), comentarios,
     * anotaciones de validación de entrada (límites de formato, también garantizados por las
     * columnas de la base) e índices de columnas de JDBC.
     */
    static String normalize(String line) {
        String noComment = line.replaceAll("//.*$", "").replaceAll("/\\*.*?\\*/", "");
        if (noComment.trim().startsWith("*") || noComment.trim().startsWith("@")) {
            return "";
        }
        return noComment.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("@\\w+\\([^)]*\\)", "")
                .replaceAll("\\.get\\w*\\(\\d+", ".get(");
    }
}
