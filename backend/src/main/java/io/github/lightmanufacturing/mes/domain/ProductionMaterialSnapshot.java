package io.github.lightmanufacturing.mes.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "production_material_snapshot")
public class ProductionMaterialSnapshot {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long productionOrderId;
  private int lineNo;
  private Long materialId;
  private String materialCode;
  private String materialName;
  private String unit;

  @Column(precision = 12, scale = 0)
  private BigDecimal quantityPer;

  @Column(precision = 12, scale = 0)
  private BigDecimal requiredQuantity;

  protected ProductionMaterialSnapshot() {}

  public ProductionMaterialSnapshot(
      long order,
      int line,
      long material,
      String code,
      String name,
      String unit,
      BigDecimal per,
      BigDecimal required) {
    productionOrderId = order;
    lineNo = line;
    materialId = material;
    materialCode = code;
    materialName = name;
    this.unit = unit;
    quantityPer = per;
    requiredQuantity = required;
  }

  public Long getId() {
    return id;
  }

  public Long getProductionOrderId() {
    return productionOrderId;
  }

  public int getLineNo() {
    return lineNo;
  }

  public Long getMaterialId() {
    return materialId;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public String getMaterialName() {
    return materialName;
  }

  public String getUnit() {
    return unit;
  }

  public BigDecimal getQuantityPer() {
    return quantityPer;
  }

  public BigDecimal getRequiredQuantity() {
    return requiredQuantity;
  }
}
