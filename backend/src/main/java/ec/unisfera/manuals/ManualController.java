package ec.unisfera.manuals;

import ec.unisfera.identity.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manuales públicos: la ayuda debe estar disponible incluso antes de iniciar sesión. */
@Tag(name = "Manuales y ayuda")
@RestController
public class ManualController {

    private final ManualService manuals;

    public ManualController(ManualService manuals) {
        this.manuals = manuals;
    }

    @PublicEndpoint
    @GetMapping("/api/public/manuals")
    public List<ManualService.Manual> list() {
        return manuals.list();
    }

    @PublicEndpoint
    @GetMapping("/api/public/manuals/search")
    public List<ManualService.Hit> search(@RequestParam String q) {
        return manuals.search(q);
    }

    @PublicEndpoint
    @GetMapping("/api/public/manuals/{slug}")
    public ManualService.Manual get(@PathVariable String slug) {
        return manuals.get(slug);
    }

    @PublicEndpoint
    @GetMapping("/api/public/manuals/{slug}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String slug) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"unisfera-" + slug + ".pdf\"").body(manuals.pdf(slug));
    }
}
