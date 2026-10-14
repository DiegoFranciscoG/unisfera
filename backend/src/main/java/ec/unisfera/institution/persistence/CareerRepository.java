package ec.unisfera.institution.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CareerRepository extends JpaRepository<CareerEntity, UUID> {

    Page<CareerEntity> findByStatus(String status, Pageable pageable);

    Optional<CareerEntity> findByCode(String code);
}
