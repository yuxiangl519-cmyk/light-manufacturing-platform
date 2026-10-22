package io.github.lightmanufacturing.mes.repository;

import io.github.lightmanufacturing.mes.domain.ProductionOrder;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface ProductionOrderRepository
    extends JpaRepository<ProductionOrder, Long>, JpaSpecificationExecutor<ProductionOrder> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from ProductionOrder o where o.id=:id")
  Optional<ProductionOrder> lockById(long id);
}
