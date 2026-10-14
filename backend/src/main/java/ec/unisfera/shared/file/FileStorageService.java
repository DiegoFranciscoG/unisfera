package ec.unisfera.shared.file;

import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archivos adjuntos con tipo validado por la firma de bytes (no por la extensión ni por lo que
 * declara el navegador), tamaño máximo parametrizado y nombre saneado. Se guardan con un nombre
 * aleatorio fuera de la raíz web.
 */
@Service
public class FileStorageService {

    public record StoredFile(UUID id, String originalName, String contentType, long size, String sha256, String purpose, UUID ownerPersonId) {
    }

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] XLSX = {'P', 'K', 3, 4};

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final AppProperties props;
    private final Clock clock;

    public FileStorageService(JdbcClient jdbc, ParameterService parameters, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public StoredFile store(byte[] content, String originalName, String purpose, UUID ownerPersonId) {
        if (content.length == 0 || content.length > parameters.integer(ParameterKeys.UPLOAD_MAX_BYTES)) {
            throw new BusinessRuleException("FILE", "El archivo está vacío o supera el tamaño permitido");
        }
        String type = detect(content);
        List<String> allowed = parameters.list(ParameterKeys.UPLOAD_TYPES);
        if (type == null || !allowed.contains(type)) {
            throw new BusinessRuleException("FILE", "Tipo de archivo no permitido; use: " + String.join(", ", allowed));
        }
        UUID id = UuidV7.next(clock);
        String storedName = id + extension(type);
        try {
            Path dir = Path.of(props.storagePath()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName).normalize();
            if (!target.startsWith(dir)) {
                throw new BusinessRuleException("FILE", "Ruta inválida");
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String sha = sha256(content);
        String safeName = sanitize(originalName);
        jdbc.sql("""
                        insert into stored_file (id, original_name, stored_name, content_type, size_bytes, sha256, purpose, owner_person_id)
                        values (:id, :o, :s, :t, :size, :sha, :p, :owner)""")
                .param("id", id).param("o", safeName).param("s", storedName).param("t", type).param("size", (long) content.length)
                .param("sha", sha).param("p", purpose).param("owner", ownerPersonId).update();
        return new StoredFile(id, safeName, type, content.length, sha, purpose, ownerPersonId);
    }

    public StoredFile metadata(UUID id) {
        return jdbc.sql("select id, original_name, content_type, size_bytes, sha256, purpose, owner_person_id from stored_file where id = :id")
                .param("id", id)
                .query((rs, n) -> new StoredFile(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class)))
                .optional().orElseThrow(() -> new NotFoundException("El archivo"));
    }

    public byte[] content(UUID id) {
        String stored = jdbc.sql("select stored_name from stored_file where id = :id").param("id", id).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("El archivo"));
        try {
            return Files.readAllBytes(Path.of(props.storagePath()).toAbsolutePath().normalize().resolve(stored));
        } catch (IOException e) {
            throw new NotFoundException("El contenido del archivo");
        }
    }

    static String detect(byte[] c) {
        if (startsWith(c, PDF)) {
            return "application/pdf";
        }
        if (startsWith(c, PNG)) {
            return "image/png";
        }
        if (startsWith(c, JPEG)) {
            return "image/jpeg";
        }
        if (startsWith(c, XLSX)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return null;
    }

    static String sanitize(String name) {
        String base = name == null ? "archivo" : Path.of(name.replace('\\', '/')).getFileName().toString();
        String clean = base.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("\\.{2,}", ".");
        return clean.isBlank() ? "archivo" : clean.substring(0, Math.min(clean.length(), 120));
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String type) {
        return switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".bin";
        };
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
