package io.github.lightmanufacturing.mes.dto;

import io.github.lightmanufacturing.wms.dto.StockDtos;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ProductionDtos {
  private ProductionDtos() {}

  public record Create(
      @NotBlank String productMaterialId,
      @NotBlank String bomId,
      @NotBlank String plannedQuantity,
      @NotBlank String issueWarehouseId,
      @NotBlank String receiptWarehouseId,
      @Size(max = 500) String remark) {}

  public record Update(
      @NotNull @PositiveOrZero @Max(9007199254740991L) Long version,
      @NotBlank String productMaterialId,
      @NotBlank String bomId,
      @NotBlank String plannedQuantity,
      @NotBlank String issueWarehouseId,
      @NotBlank String receiptWarehouseId,
      @Size(max = 500) String remark) {}

  public record Summary(
      String id,
      long version,
      String orderNo,
      String productMaterialId,
      String productCode,
      String productName,
      String unit,
      String plannedQuantity,
      String issueWarehouseId,
      String issueWarehouseName,
      String receiptWarehouseId,
      String receiptWarehouseName,
      String status,
      Instant createdAt,
      Instant updatedAt,
      String createdByName) {}

  public record Snapshot(
      String id,
      int lineNo,
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String quantityPer,
      String requiredQuantity) {}

  public record Report(
      String id, String reportedQuantity, Instant reportedAt, String reportedByName) {}

  public record Detail(
      String id,
      long version,
      String orderNo,
      String productMaterialId,
      String productCode,
      String productName,
      String unit,
      String plannedQuantity,
      String issueWarehouseId,
      String issueWarehouseName,
      String receiptWarehouseId,
      String receiptWarehouseName,
      String status,
      Instant createdAt,
      Instant updatedAt,
      String createdByName,
      String bomId,
      String bomCode,
      Integer bomRevision,
      String remark,
      Instant confirmedAt,
      Instant issuedAt,
      Instant reportedAt,
      Instant completedAt,
      Instant cancelledAt,
      List<Snapshot> materialSnapshot,
      Report report,
      List<StockDtos.Document> stockDocuments) {}

  public record Requirement(
      int lineNo,
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String requiredQuantity,
      String availableQuantity,
      String shortageQuantity,
      boolean shortage) {}

  public record Requirements(
      String productionOrderId, String status, boolean hasShortage, List<Requirement> items) {}

  public record RelatedOrder(String id, long version, String orderNo, String status) {}

  public record ReceiptResult(
      String operationId,
      boolean replayed,
      StockDtos.Detail document,
      List<StockDtos.Document> relatedDocuments,
      RelatedOrder relatedProductionOrder) {}
}
