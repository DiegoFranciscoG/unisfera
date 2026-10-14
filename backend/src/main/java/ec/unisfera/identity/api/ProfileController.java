package ec.unisfera.identity.api;

import ec.unisfera.identity.application.PersonService;
import ec.unisfera.identity.security.AuthenticatedOnly;
import ec.unisfera.identity.security.SecurityUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mi ficha: el identificador sale del token, nunca de la URL. */
@Tag(name = "Mi ficha")
@RestController
@RequestMapping("/api/me/profile")
@AuthenticatedOnly
public class ProfileController {

    public record ContactRequest(@NotBlank @Size(max = 160) String fullName, @NotBlank @Size(max = 40) String relationship,
                                 @NotBlank @Pattern(regexp = "^\\+?[0-9 ]{7,20}$") String phone) {
    }

    private final PersonService persons;

    public ProfileController(PersonService persons) {
        this.persons = persons;
    }

    @GetMapping
    public PersonService.PersonView profile() {
        return persons.get(SecurityUtils.requireUser().personId());
    }

    /** Solo los campos marcados como editables por la institución; el resto responde 422 (SEC-05). */
    @PatchMapping
    public PersonService.PersonView update(@RequestBody Map<String, @Size(max = 300) String> changes) {
        return persons.selfUpdate(SecurityUtils.requireUser().personId(), changes);
    }

    @GetMapping("/emergency-contacts")
    public List<PersonService.EmergencyContact> contacts() {
        return persons.contacts(SecurityUtils.requireUser().personId());
    }

    @PostMapping("/emergency-contacts")
    public PersonService.EmergencyContact add(@Valid @RequestBody ContactRequest body) {
        return persons.addContact(SecurityUtils.requireUser().personId(), body.fullName(), body.relationship(), body.phone());
    }

    @PostMapping("/emergency-contacts/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        persons.deactivateContact(SecurityUtils.requireUser().personId(), id);
        return ResponseEntity.noContent().build();
    }
}
