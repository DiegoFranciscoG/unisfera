package ec.unisfera.identity.api;

import ec.unisfera.identity.application.AccountService;
import ec.unisfera.identity.application.PersonService;
import ec.unisfera.identity.security.RequiresPermission;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administración de personas y cuentas")
@RestController
@RequestMapping("/api/admin")
public class PersonAdminController {

    public record PersonRequest(@NotBlank @Pattern(regexp = "CEDULA|PASSPORT") String idType,
                                @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9]+$") String idNumber,
                                @NotBlank @Size(max = 120) String firstNames, @NotBlank @Size(max = 120) String lastNames,
                                @NotNull @Past LocalDate birthDate, @NotBlank @Pattern(regexp = "HOMBRE|MUJER") String sex,
                                @Pattern(regexp = "SOLTERO|CASADO|DIVORCIADO|VIUDO|UNION_DE_HECHO") String maritalStatus,
                                @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String nationality, @Size(max = 300) String address,
                                @Pattern(regexp = "^\\+?[0-9 ]{7,20}$") String phoneMobile, @Pattern(regexp = "^\\+?[0-9 ]{7,20}$") String phoneHome,
                                @Email @Size(max = 160) String personalEmail) {

        PersonService.PersonData toData() {
            return new PersonService.PersonData(idType, idNumber, firstNames, lastNames, birthDate, sex, maritalStatus, nationality,
                    address, phoneMobile, phoneHome, personalEmail);
        }
    }

    public record AccountRequest(@NotEmpty List<@Pattern(regexp = "^[A-Z_]+$") String> roles, @Email @Size(max = 160) String institutionalEmail) {
    }

    public record DisableRequest(@NotBlank @Size(min = 10, max = 300) String reason) {
    }

    private final PersonService persons;
    private final AccountService accounts;

    public PersonAdminController(PersonService persons, AccountService accounts) {
        this.persons = persons;
        this.accounts = accounts;
    }

    @RequiresPermission("PERSON_READ")
    @GetMapping("/persons")
    public PageResponse<PersonService.PersonView> search(@RequestParam(required = false) String q,
                                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return persons.search(q, PageQuery.of(page, size));
    }

    @RequiresPermission("PERSON_READ")
    @GetMapping("/persons/{id}")
    public PersonService.PersonView get(@PathVariable UUID id) {
        return persons.get(id);
    }

    @RequiresPermission("PERSON_WRITE")
    @PostMapping("/persons")
    public PersonService.PersonView create(@Valid @RequestBody PersonRequest body) {
        return persons.create(body.toData());
    }

    @RequiresPermission("PERSON_WRITE")
    @PutMapping("/persons/{id}")
    public PersonService.PersonView update(@PathVariable UUID id, @Valid @RequestBody PersonRequest body) {
        return persons.adminUpdate(id, body.toData());
    }

    @RequiresPermission("USER_ADMIN")
    @PostMapping("/persons/{id}/account")
    public AccountService.ProvisionedAccount provision(@PathVariable UUID id, @Valid @RequestBody AccountRequest body) {
        return accounts.provision(id, body.roles(), body.institutionalEmail());
    }

    @RequiresPermission("USER_ADMIN")
    @GetMapping("/users/{userId}")
    public AccountService.AccountView account(@PathVariable UUID userId) {
        return accounts.view(userId);
    }

    @RequiresPermission("USER_ADMIN")
    @PostMapping("/users/{userId}/temporary-password")
    public AccountService.ProvisionedAccount reset(@PathVariable UUID userId) {
        return accounts.resetToTemporary(userId);
    }

    @RequiresPermission("USER_ADMIN")
    @PostMapping("/users/{userId}/roles/{role}")
    public ResponseEntity<Void> grant(@PathVariable UUID userId, @PathVariable String role) {
        accounts.grant(userId, role);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("USER_ADMIN")
    @PostMapping("/users/{userId}/roles/{role}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID userId, @PathVariable String role) {
        accounts.revoke(userId, role);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("USER_ADMIN")
    @PostMapping("/users/{userId}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID userId, @Valid @RequestBody DisableRequest body) {
        accounts.disable(userId, body.reason());
        return ResponseEntity.noContent().build();
    }
}
