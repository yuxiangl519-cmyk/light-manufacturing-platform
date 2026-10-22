package io.github.lightmanufacturing.master.repository;

import io.github.lightmanufacturing.master.domain.Material;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface MaterialRepository
    extends JpaRepository<Material, Long>, JpaSpecificationExecutor<Material> {
  boolean existsByCode(String code);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select x from Material x where x.id=:id")
  Optional<Material> readLockById(long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select x from Material x where x.id=:id")
  Optional<Material> lockById(long id);
}
