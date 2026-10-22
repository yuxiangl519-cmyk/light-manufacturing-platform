package io.github.lightmanufacturing.mes.repository;

import io.github.lightmanufacturing.mes.domain.ProductionReport;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ProductionReportRepository extends Repository<ProductionReport, Long> {
  Optional<ProductionReport> findByProductionOrderId(long orderId);
}
