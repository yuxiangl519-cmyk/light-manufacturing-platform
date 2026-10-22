package io.github.lightmanufacturing.mes.repository;

import io.github.lightmanufacturing.mes.domain.Bom;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface BomRepository extends JpaRepository<Bom, Long>, JpaSpecificationExecutor<Bom> {
  boolean existsByCode(String code);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Bom b where b.id=:id")
  Optional<Bom> lockById(long id);
}
