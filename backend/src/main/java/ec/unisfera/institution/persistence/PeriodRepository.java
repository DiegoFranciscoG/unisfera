package ec.unisfera.institution.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<AcademicPeriodEntity, UUID> {

    Page<AcademicPeriodEntity> findAllByOrderByClassesStartDesc(Pageable pageable);

    Optional<AcademicPeriodEntity> findByCode(String code);

    Optional<AcademicPeriodEntity> findFirstByStatusInOrderByClassesStartDesc(List<String> statuses);
}
