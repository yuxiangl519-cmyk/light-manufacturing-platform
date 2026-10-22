package io.github.lightmanufacturing.wms.dto;

import java.time.Instant;
import java.util.List;

public final class StockDtos {
  private StockDtos() {}

  public record Document(
      String id,
      long version,
      String documentNo,
      String kind,
      String direction,
      String status,
      String warehouseId,
      String warehouseName,
      String sourceType,
      String sourceId,
      String sourceNo,
      Instant createdAt,
      Instant executedAt) {}

  public record Item(
      String id,
      int lineNo,
      String sourceLineId,
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String quantity) {}

  public record Detail(
      String id,
      long version,
      String documentNo,
      String kind,
      String direction,
      String status,
      String warehouseId,
      String warehouseName,
      String sourceType,
      String sourceId,
      String sourceNo,
      Instant createdAt,
      Instant executedAt,
      String executedByName,
      List<Item> items) {}

  public record Balance(
      String warehouseId,
      String warehouseName,
      String materialId,
      String materialCode,
      String materialName,
      String type,
      String unit,
      String quantity,
      String minStock,
      boolean lowStock) {}

  public record Ledger(
      String id,
      String warehouseId,
      String warehouseName,
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String stockDocumentId,
      String stockDocumentItemId,
      String documentNo,
      String kind,
      String sourceType,
      String sourceId,
      String sourceNo,
      String sourceLineId,
      String changeQuantity,
      String beforeQuantity,
      String afterQuantity,
      String operatorName,
      Instant occurredAt) {}
}
