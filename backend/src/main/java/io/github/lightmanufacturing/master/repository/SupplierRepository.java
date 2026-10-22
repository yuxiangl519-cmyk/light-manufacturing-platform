package io.github.lightmanufacturing.master.repository;

import io.github.lightmanufacturing.master.domain.Supplier;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface SupplierRepository
    extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {
  boolean existsByCode(String code);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select x from Supplier x where x.id=:id")
  Optional<Supplier> readLockById(long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select x from Supplier x where x.id=:id")
  Optional<Supplier> lockById(long id);
}
