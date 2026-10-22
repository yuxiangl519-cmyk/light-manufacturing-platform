package io.github.lightmanufacturing.master.repository;

import io.github.lightmanufacturing.master.domain.Customer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface CustomerRepository
    extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
  boolean existsByCode(String code);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select x from Customer x where x.id=:id")
  Optional<Customer> readLockById(long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select x from Customer x where x.id=:id")
  Optional<Customer> lockById(long id);
}
