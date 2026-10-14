package ec.unisfera.institution.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<SectionEntity, UUID> {
}
