package ec.unisfera.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "person")
public class PersonEntity {

    @Id
    private UUID id;
    @Column(name = "id_type", nullable = false, updatable = false)
    private String idType;
    @Column(name = "id_number", nullable = false, updatable = false)
    private String idNumber;
    @Column(name = "first_names", nullable = false)
    private String firstNames;
    @Column(name = "last_names", nullable = false)
    private String lastNames;
    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;
    @Column(nullable = false)
    private String sex;
    @Column(name = "marital_status")
    private String maritalStatus;
    @Column(nullable = false)
    private String nationality;
    private String address;
    @Column(name = "phone_mobile")
    private String phoneMobile;
    @Column(name = "phone_home")
    private String phoneHome;
    @Column(name = "personal_email")
    private String personalEmail;
    @Column(name = "photo_file_id")
    private UUID photoFileId;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Version
    private Long version;

    protected PersonEntity() {
    }

    public PersonEntity(UUID id, String idType, String idNumber) {
        this.id = id;
        this.idType = idType;
        this.idNumber = idNumber;
    }

    public UUID getId() { return id; }
    public String getIdType() { return idType; }
    public String getIdNumber() { return idNumber; }
    public String getFirstNames() { return firstNames; }
    public void setFirstNames(String firstNames) { this.firstNames = firstNames; }
    public String getLastNames() { return lastNames; }
    public void setLastNames(String lastNames) { this.lastNames = lastNames; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }
    public String getMaritalStatus() { return maritalStatus; }
    public void setMaritalStatus(String maritalStatus) { this.maritalStatus = maritalStatus; }
    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhoneMobile() { return phoneMobile; }
    public void setPhoneMobile(String phoneMobile) { this.phoneMobile = phoneMobile; }
    public String getPhoneHome() { return phoneHome; }
    public void setPhoneHome(String phoneHome) { this.phoneHome = phoneHome; }
    public String getPersonalEmail() { return personalEmail; }
    public void setPersonalEmail(String personalEmail) { this.personalEmail = personalEmail; }
    public UUID getPhotoFileId() { return photoFileId; }
    public void setPhotoFileId(UUID photoFileId) { this.photoFileId = photoFileId; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }

    public String fullName() {
        return firstNames + " " + lastNames;
    }
}
