package io.github.lightmanufacturing.erp.domain;

public enum OrderType {
  PURCHASE(
      "purchase_order",
      "purchase_order_item",
      "supplier_id",
      "PURCHASE_ORDER",
      "PO",
      "RAW_MATERIAL",
      "RECEIVED",
      "received_at"),
  SALES(
      "sales_order",
      "sales_order_item",
      "customer_id",
      "SALES_ORDER",
      "SO",
      "FINISHED_GOOD",
      "SHIPPED",
      "shipped_at");
  public final String table,
      itemTable,
      partnerColumn,
      resource,
      prefix,
      materialType,
      doneStatus,
      doneColumn;

  OrderType(
      String table,
      String itemTable,
      String partnerColumn,
      String resource,
      String prefix,
      String materialType,
      String doneStatus,
      String doneColumn) {
    this.table = table;
    this.itemTable = itemTable;
    this.partnerColumn = partnerColumn;
    this.resource = resource;
    this.prefix = prefix;
    this.materialType = materialType;
    this.doneStatus = doneStatus;
    this.doneColumn = doneColumn;
  }
}
