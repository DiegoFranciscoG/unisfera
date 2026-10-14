package ec.unisfera.institution.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurriculumRepository extends JpaRepository<CurriculumEntity, UUID> {

    List<CurriculumEntity> findByCareerIdOrderByValidFromDesc(UUID careerId);
}
