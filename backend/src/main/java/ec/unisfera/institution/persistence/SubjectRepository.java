package ec.unisfera.institution.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<SubjectEntity, UUID> {

    List<SubjectEntity> findByCurriculumId(UUID curriculumId);
}
