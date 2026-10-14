package ec.unisfera.manuals;

import ec.unisfera.shared.domain.NotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Manuales en lenguaje claro (docs/manuales, empaquetados en el JAR). Se sirven como Markdown
 * para la ayuda contextual y el buscador, y se generan como PDF descargable.
 */
@Service
public class ManualService {

    public record Manual(String slug, String title, String audience, String markdown) {
    }

    public record Hit(String slug, String title, String heading, String snippet) {
    }

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{3,60}$");
    private final Map<String, Manual> cache = new ConcurrentHashMap<>();

    public List<Manual> list() {
        load();
        return cache.values().stream().sorted(Comparator.comparing(Manual::slug))
                .map(m -> new Manual(m.slug(), m.title(), m.audience(), null)).toList();
    }

    public Manual get(String slug) {
        load();
        if (!SLUG.matcher(slug).matches() || !cache.containsKey(slug)) {
            throw new NotFoundException("El manual");
        }
        return cache.get(slug);
    }

    /** Buscador de ayuda: coincidencias por sección, con el encabezado más cercano. */
    public List<Hit> search(String query) {
        load();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (q.length() < 3) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Manual m : cache.values()) {
            String heading = m.title();
            for (String line : m.markdown().split("\n")) {
                if (line.startsWith("#")) {
                    heading = line.replaceAll("^#+\\s*", "");
                }
                if (line.toLowerCase(Locale.ROOT).contains(q)) {
                    hits.add(new Hit(m.slug(), m.title(), heading, line.replaceAll("[#*`>]", "").trim()));
                    break;
                }
            }
        }
        return hits.stream().limit(20).toList();
    }

    public byte[] pdf(String slug) {
        Manual m = get(slug);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        org.openpdf.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10.5f);
        for (String raw : m.markdown().split("\n")) {
            String line = raw.replace("**", "").replace("`", "");
            if (line.startsWith("![")) {
                continue;
            }
            Paragraph p;
            if (line.startsWith("# ")) {
                p = new Paragraph(line.substring(2), h1);
                p.setSpacingAfter(10);
            } else if (line.startsWith("## ") || line.startsWith("### ")) {
                p = new Paragraph(line.replaceAll("^#+\\s*", "").replaceAll("\\s*\\{#.*}$", ""), h2);
                p.setSpacingBefore(10);
                p.setSpacingAfter(4);
            } else if (line.startsWith("- ") || line.matches("^\\d+\\. .*")) {
                p = new Paragraph("   " + (line.startsWith("- ") ? "• " + line.substring(2) : line), body);
            } else if (line.startsWith("> ")) {
                p = new Paragraph(line.substring(2), FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10.5f));
            } else {
                p = new Paragraph(line.replaceAll("\\[(.*?)]\\(.*?\\)", "$1"), body);
            }
            doc.add(p);
        }
        doc.close();
        return out.toByteArray();
    }

    private void load() {
        if (!cache.isEmpty()) {
            return;
        }
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:manuals/*.md")) {
                String name = r.getFilename();
                if (name == null || name.equalsIgnoreCase("README.md")) {
                    continue;
                }
                String md = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String slug = name.replace(".md", "");
                String title = md.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2)).orElse(slug);
                String audience = slug.startsWith("docente") ? "TEACHER" : slug.startsWith("administrador") ? "ADMIN" : "STUDENT";
                cache.put(slug, new Manual(slug, title, audience, md));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
