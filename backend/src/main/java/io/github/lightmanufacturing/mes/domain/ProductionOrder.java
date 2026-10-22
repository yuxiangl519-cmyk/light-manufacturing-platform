package io.github.lightmanufacturing.mes.domain;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "production_order")
public class ProductionOrder extends AuditedEntity {
  private String orderNo;

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String v) {
    orderNo = v;
  }

  private Long productMaterialId;

  public Long getProductMaterialId() {
    return productMaterialId;
  }

  public void setProductMaterialId(Long v) {
    productMaterialId = v;
  }

  private String productCode;

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String v) {
    productCode = v;
  }

  private String productName;

  public String getProductName() {
    return productName;
  }

  public void setProductName(String v) {
    productName = v;
  }

  private String unit;

  public String getUnit() {
    return unit;
  }

  public void setUnit(String v) {
    unit = v;
  }

  @Column(precision = 12, scale = 0)
  private java.math.BigDecimal plannedQuantity;

  public java.math.BigDecimal getPlannedQuantity() {
    return plannedQuantity;
  }

  public void setPlannedQuantity(java.math.BigDecimal v) {
    plannedQuantity = v;
  }

  private Long bomId;

  public Long getBomId() {
    return bomId;
  }

  public void setBomId(Long v) {
    bomId = v;
  }

  private String bomCode;

  public String getBomCode() {
    return bomCode;
  }

  public void setBomCode(String v) {
    bomCode = v;
  }

  private Integer bomRevision;

  public Integer getBomRevision() {
    return bomRevision;
  }

  public void setBomRevision(Integer v) {
    bomRevision = v;
  }

  private Long issueWarehouseId;

  public Long getIssueWarehouseId() {
    return issueWarehouseId;
  }

  public void setIssueWarehouseId(Long v) {
    issueWarehouseId = v;
  }

  private String issueWarehouseName;

  public String getIssueWarehouseName() {
    return issueWarehouseName;
  }

  public void setIssueWarehouseName(String v) {
    issueWarehouseName = v;
  }

  private Long receiptWarehouseId;

  public Long getReceiptWarehouseId() {
    return receiptWarehouseId;
  }

  public void setReceiptWarehouseId(Long v) {
    receiptWarehouseId = v;
  }

  private String receiptWarehouseName;

  public String getReceiptWarehouseName() {
    return receiptWarehouseName;
  }

  public void setReceiptWarehouseName(String v) {
    receiptWarehouseName = v;
  }

  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  private String remark;

  public String getRemark() {
    return remark;
  }

  public void setRemark(String v) {
    remark = v;
  }

  private java.time.Instant confirmedAt;

  public java.time.Instant getConfirmedAt() {
    return confirmedAt;
  }

  public void setConfirmedAt(java.time.Instant v) {
    confirmedAt = v;
  }

  private java.time.Instant issuedAt;

  public java.time.Instant getIssuedAt() {
    return issuedAt;
  }

  public void setIssuedAt(java.time.Instant v) {
    issuedAt = v;
  }

  private java.time.Instant reportedAt;

  public java.time.Instant getReportedAt() {
    return reportedAt;
  }

  public void setReportedAt(java.time.Instant v) {
    reportedAt = v;
  }

  private java.time.Instant completedAt;

  public java.time.Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(java.time.Instant v) {
    completedAt = v;
  }

  private java.time.Instant cancelledAt;

  public java.time.Instant getCancelledAt() {
    return cancelledAt;
  }

  public void setCancelledAt(java.time.Instant v) {
    cancelledAt = v;
  }

  private java.time.Instant deletedAt;

  public java.time.Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(java.time.Instant v) {
    deletedAt = v;
  }
}
