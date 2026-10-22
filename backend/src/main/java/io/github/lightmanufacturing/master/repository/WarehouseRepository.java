package io.github.lightmanufacturing.master.repository;

import io.github.lightmanufacturing.master.domain.Warehouse;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface WarehouseRepository
    extends JpaRepository<Warehouse, Long>, JpaSpecificationExecutor<Warehouse> {
  boolean existsByCode(String code);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select x from Warehouse x where x.id=:id")
  Optional<Warehouse> readLockById(long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select x from Warehouse x where x.id=:id")
  Optional<Warehouse> lockById(long id);
}
