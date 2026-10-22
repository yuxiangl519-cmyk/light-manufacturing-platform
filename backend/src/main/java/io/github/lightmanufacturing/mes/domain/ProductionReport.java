package io.github.lightmanufacturing.mes.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "production_report")
public class ProductionReport {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long productionOrderId;

  @Column(precision = 12, scale = 0)
  private BigDecimal reportedQuantity;

  private Long reportedBy;
  private String reportedByName;
  private Instant reportedAt;

  protected ProductionReport() {}

  public ProductionReport(long order, BigDecimal quantity, long actor, String name, Instant at) {
    productionOrderId = order;
    reportedQuantity = quantity;
    reportedBy = actor;
    reportedByName = name;
    reportedAt = at;
  }

  public Long getId() {
    return id;
  }

  public BigDecimal getReportedQuantity() {
    return reportedQuantity;
  }

  public String getReportedByName() {
    return reportedByName;
  }

  public Instant getReportedAt() {
    return reportedAt;
  }
}
