package io.github.lightmanufacturing.wms.domain;

import java.math.BigDecimal;
import java.util.List;

/** Internal, immutable contract. The caller locks/validates its source in the same transaction. */
public record StockPosting(
    Kind kind,
    long sourceId,
    String sourceNo,
    long warehouseId,
    long operatorId,
    List<Line> lines) {
  public StockPosting {
    if (lines != null) lines = List.copyOf(lines);
  }

  public record Line(long sourceLineId, long materialId, BigDecimal quantity) {}

  public enum Kind {
    PURCHASE_RECEIPT("IN", "PURCHASE_ORDER", "RAW_MATERIAL"),
    PRODUCTION_ISSUE("OUT", "PRODUCTION_ORDER", "RAW_MATERIAL"),
    PRODUCTION_RECEIPT("IN", "PRODUCTION_ORDER", "FINISHED_GOOD"),
    SALES_SHIPMENT("OUT", "SALES_ORDER", "FINISHED_GOOD");
    private final String direction, sourceType, materialType;

    Kind(String direction, String sourceType, String materialType) {
      this.direction = direction;
      this.sourceType = sourceType;
      this.materialType = materialType;
    }

    public String direction() {
      return direction;
    }

    public String sourceType() {
      return sourceType;
    }

    public String materialType() {
      return materialType;
    }
  }

  public record Result(long id, String documentNo, String status, long version, boolean replayed) {}
}
