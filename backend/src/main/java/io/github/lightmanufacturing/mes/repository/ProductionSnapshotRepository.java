package io.github.lightmanufacturing.mes.repository;

import io.github.lightmanufacturing.mes.domain.ProductionMaterialSnapshot;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface ProductionSnapshotRepository extends Repository<ProductionMaterialSnapshot, Long> {
  List<ProductionMaterialSnapshot> findByProductionOrderIdOrderByLineNo(long orderId);
}
