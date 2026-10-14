package ec.unisfera.evaluation.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RN-13: hash SHA-256 del contenido canónico del acta (líneas ordenadas, notas por componente,
 * nota final, asistencia y resultado). Cualquier alteración posterior cambia el hash.
 */
public final class ActHasher {

    public record Line(UUID enrollmentDetailId, Map<UUID, BigDecimal> componentGrades, BigDecimal finalGrade, BigDecimal attendancePct, String result) {
    }

    private ActHasher() {
    }

    public static String canonical(UUID sectionId, List<Line> lines) {
        StringBuilder sb = new StringBuilder("section=").append(sectionId).append('\n');
        lines.stream().sorted(Comparator.comparing(Line::enrollmentDetailId)).forEach(l -> {
            sb.append(l.enrollmentDetailId()).append('|');
            sb.append(l.componentGrades().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue().stripTrailingZeros().toPlainString()).collect(Collectors.joining(",")));
            sb.append('|').append(l.finalGrade().stripTrailingZeros().toPlainString())
                    .append('|').append(l.attendancePct().stripTrailingZeros().toPlainString())
                    .append('|').append(l.result()).append('\n');
        });
        return sb.toString();
    }

    public static String hash(UUID sectionId, List<Line> lines) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(sectionId, lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
