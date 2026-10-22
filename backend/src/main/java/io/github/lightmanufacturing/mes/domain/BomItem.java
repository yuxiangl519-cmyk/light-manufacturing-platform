package io.github.lightmanufacturing.mes.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "bom_item")
public class BomItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "bom_id", nullable = false)
  private Bom bom;

  private int lineNo;
  private Long materialId;

  @Column(precision = 12, scale = 0)
  private BigDecimal quantityPer;

  public Long getId() {
    return id;
  }

  public Bom getBom() {
    return bom;
  }

  public void setBom(Bom v) {
    bom = v;
  }

  public int getLineNo() {
    return lineNo;
  }

  public void setLineNo(int v) {
    lineNo = v;
  }

  public Long getMaterialId() {
    return materialId;
  }

  public void setMaterialId(Long v) {
    materialId = v;
  }

  public BigDecimal getQuantityPer() {
    return quantityPer;
  }

  public void setQuantityPer(BigDecimal v) {
    quantityPer = v;
  }
}
