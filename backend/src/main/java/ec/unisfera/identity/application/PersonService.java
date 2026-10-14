package ec.unisfera.identity.application;

import ec.unisfera.identity.domain.CedulaValidator;
import ec.unisfera.identity.persistence.PersonEntity;
import ec.unisfera.identity.persistence.PersonRepository;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personas: alta y corrección por Secretaría; el titular solo edita lo que la institución marcó como editable. */
@Service
public class PersonService {

    public record PersonData(String idType, String idNumber, String firstNames, String lastNames, LocalDate birthDate,
                             String sex, String maritalStatus, String nationality, String address, String phoneMobile,
                             String phoneHome, String personalEmail) {
    }

    public record PersonView(UUID id, String idType, String idNumber, String firstNames, String lastNames, LocalDate birthDate,
                             String sex, String maritalStatus, String nationality, String address, String phoneMobile,
                             String phoneHome, String personalEmail, Set<String> editableFields) {
    }

    public record EmergencyContact(UUID id, String fullName, String relationship, String phone) {
    }

    private final PersonRepository persons;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PersonService(PersonRepository persons, JdbcClient jdbc, Clock clock) {
        this.persons = persons;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public PersonView create(PersonData data) {
        String idType = data.idType().toUpperCase(Locale.ROOT);
        String idNumber = data.idNumber().trim().toUpperCase(Locale.ROOT);
        if ("CEDULA".equals(idType) && !CedulaValidator.isValid(idNumber)) {
            throw new BusinessRuleException("SEC-01", "La cédula no es válida (dígito verificador)");
        }
        persons.findByIdTypeAndIdNumber(idType, idNumber).ifPresent(p -> {
            throw new BusinessRuleException("SEC-01", "Ya existe una persona con esa identificación");
        });
        PersonEntity p = new PersonEntity(UuidV7.next(clock), idType, idNumber);
        apply(p, data);
        persons.saveAndFlush(p);
        return view(p);
    }

    @Transactional
    public PersonView adminUpdate(UUID personId, PersonData data) {
        PersonEntity p = persons.findById(personId).orElseThrow(() -> new NotFoundException("La persona"));
        apply(p, data);
        return view(p);
    }

    @Transactional(readOnly = true)
    public PersonView get(UUID personId) {
        return view(persons.findById(personId).orElseThrow(() -> new NotFoundException("La persona")));
    }

    @Transactional(readOnly = true)
    public PageResponse<PersonView> search(String text, PageQuery page) {
        String like = "%" + (text == null ? "" : text.trim().toLowerCase(Locale.ROOT)) + "%";
        List<UUID> ids = jdbc.sql("""
                        select id from person where lower(first_names || ' ' || last_names) like :q or id_number like :q
                        order by last_names, first_names limit :l offset :o""")
                .param("q", like).param("l", page.size()).param("o", page.offset()).query(UUID.class).list();
        long total = jdbc.sql("select count(*) from person where lower(first_names || ' ' || last_names) like :q or id_number like :q")
                .param("q", like).query(Long.class).single();
        return PageResponse.of(persons.findAllById(ids).stream().map(this::view).toList(), page, total);
    }

    /** El titular solo cambia los campos que el administrador marcó como editables (person_field_policy). */
    @Transactional
    public PersonView selfUpdate(UUID personId, Map<String, String> changes) {
        Set<String> editable = editableFields();
        List<String> forbidden = changes.keySet().stream().filter(f -> !editable.contains(f)).toList();
        if (!forbidden.isEmpty()) {
            throw new BusinessRuleException("SEC-05", "No puede modificar: " + String.join(", ", forbidden) + ". Solicite el cambio en Secretaría");
        }
        PersonEntity p = persons.findById(personId).orElseThrow(() -> new NotFoundException("La persona"));
        changes.forEach((field, value) -> {
            String v = value == null || value.isBlank() ? null : value.trim();
            switch (field) {
                case "phone_mobile" -> p.setPhoneMobile(v);
                case "phone_home" -> p.setPhoneHome(v);
                case "address" -> p.setAddress(v);
                case "personal_email" -> p.setPersonalEmail(v);
                default -> throw new BusinessRuleException("SEC-05", "Campo desconocido: " + field);
            }
        });
        persons.flush();
        return view(p);
    }

    public Set<String> editableFields() {
        return Set.copyOf(jdbc.sql("select field_name from person_field_policy where student_editable").query(String.class).list());
    }

    @Transactional(readOnly = true)
    public List<EmergencyContact> contacts(UUID personId) {
        return jdbc.sql("select id, full_name, relationship, phone from emergency_contact where person_id = :p and status = 'ACTIVE' order by created_at")
                .param("p", personId)
                .query((rs, n) -> new EmergencyContact(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("relationship"), rs.getString("phone")))
                .list();
    }

    @Transactional
    public EmergencyContact addContact(UUID personId, String fullName, String relationship, String phone) {
        requireEditable("emergency_contacts");
        UUID id = UuidV7.next(clock);
        jdbc.sql("insert into emergency_contact (id, person_id, full_name, relationship, phone) values (:id, :p, :n, :r, :ph)")
                .param("id", id).param("p", personId).param("n", fullName).param("r", relationship).param("ph", phone).update();
        return new EmergencyContact(id, fullName, relationship, phone);
    }

    /** Baja lógica: el contacto queda inactivo, nunca se borra (LEY-2). */
    @Transactional
    public void deactivateContact(UUID personId, UUID contactId) {
        requireEditable("emergency_contacts");
        int n = jdbc.sql("update emergency_contact set status = 'INACTIVE' where id = :id and person_id = :p and status = 'ACTIVE'")
                .param("id", contactId).param("p", personId).update();
        if (n == 0) {
            throw new NotFoundException("El contacto");
        }
    }

    private void requireEditable(String field) {
        if (!editableFields().contains(field)) {
            throw new BusinessRuleException("SEC-05", "La institución no permite modificar este dato desde el portal");
        }
    }

    private static void apply(PersonEntity p, PersonData d) {
        p.setFirstNames(d.firstNames().trim());
        p.setLastNames(d.lastNames().trim());
        p.setBirthDate(d.birthDate());
        p.setSex(d.sex());
        p.setMaritalStatus(d.maritalStatus());
        p.setNationality(d.nationality());
        p.setAddress(d.address());
        p.setPhoneMobile(d.phoneMobile());
        p.setPhoneHome(d.phoneHome());
        p.setPersonalEmail(d.personalEmail());
    }

    private PersonView view(PersonEntity p) {
        return new PersonView(p.getId(), p.getIdType(), p.getIdNumber(), p.getFirstNames(), p.getLastNames(), p.getBirthDate(),
                p.getSex(), p.getMaritalStatus(), p.getNationality(), p.getAddress(), p.getPhoneMobile(), p.getPhoneHome(),
                p.getPersonalEmail(), editableFields().stream().collect(Collectors.toUnmodifiableSet()));
    }
}
