package io.github.lightmanufacturing.erp.dto;

import io.github.lightmanufacturing.wms.dto.StockDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ErpDtos {
  private ErpDtos() {}

  public record LineWrite(
      @NotNull String materialId, @NotNull String quantity, @NotNull String unitPrice) {}

  public record PurchaseWrite(
      @NotNull String supplierId,
      @NotNull String warehouseId,
      @Size(max = 500) String remark,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LineWrite> items) {}

  public record PurchaseUpdate(
      @NotNull @Min(0) Long version,
      @NotNull String supplierId,
      @NotNull String warehouseId,
      @Size(max = 500) String remark,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LineWrite> items) {}

  public record SalesWrite(
      @NotNull String customerId,
      @NotNull String warehouseId,
      @Size(max = 500) String remark,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LineWrite> items) {}

  public record SalesUpdate(
      @NotNull @Min(0) Long version,
      @NotNull String customerId,
      @NotNull String warehouseId,
      @Size(max = 500) String remark,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LineWrite> items) {}

  public record MoneyLine(
      int lineNo,
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String quantity,
      String unitPrice,
      String lineAmount) {}

  public record PurchaseSummary(
      String id,
      long version,
      String orderNo,
      String supplierId,
      String supplierName,
      String warehouseId,
      String warehouseName,
      String status,
      String totalAmount,
      Instant createdAt,
      Instant updatedAt,
      String createdByName) {}

  public record SalesSummary(
      String id,
      long version,
      String orderNo,
      String customerId,
      String customerName,
      String warehouseId,
      String warehouseName,
      String status,
      String totalAmount,
      Instant createdAt,
      Instant updatedAt,
      String createdByName) {}

  public record PurchaseDetail(
      String id,
      long version,
      String orderNo,
      String supplierId,
      String supplierName,
      String warehouseId,
      String warehouseName,
      String status,
      String totalAmount,
      Instant createdAt,
      Instant updatedAt,
      String createdByName,
      String remark,
      Instant confirmedAt,
      Instant receivedAt,
      Instant cancelledAt,
      List<MoneyLine> items,
      List<StockDtos.Document> stockDocuments) {}

  public record SalesDetail(
      String id,
      long version,
      String orderNo,
      String customerId,
      String customerName,
      String warehouseId,
      String warehouseName,
      String status,
      String totalAmount,
      Instant createdAt,
      Instant updatedAt,
      String createdByName,
      String remark,
      Instant confirmedAt,
      Instant shippedAt,
      Instant cancelledAt,
      List<MoneyLine> items,
      List<StockDtos.Document> stockDocuments) {}
}
