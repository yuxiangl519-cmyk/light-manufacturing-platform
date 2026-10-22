package io.github.lightmanufacturing.workbench.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.lightmanufacturing.wms.dto.StockDtos;
import java.time.Instant;
import java.util.List;

public final class WorkbenchDtos {
  private WorkbenchDtos() {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Summary(Period period, Cards cards, List<Todo> todos, List<Trend> trend,
      Production production, Inventory inventory, List<RecentOrder> recentOrders,
      List<RecentProduction> recentProduction) {}
  public record Period(Instant from, Instant to) {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Cards(String purchaseCount, String salesCount, String receivedPurchaseAmount,
      String shippedSalesAmount, String confirmedPurchaseAmount, String confirmedSalesAmount) {}
  public record Todo(String type, String label, String count, String route) {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Trend(String date, String purchaseCount, String salesCount) {}
  public record Production(List<StatusCount> byStatus, List<CompletedQuantity> completedQuantities) {}
  public record CompletedQuantity(String unit, String quantity) {}
  public record StatusCount(String status, String count) {}
  public record Inventory(String lowStockCount, List<StockDtos.Balance> items) {}
  public record RecentOrder(String id, String orderNo, String kind, String partnerName,
      String status, String totalAmount, Instant createdAt) {}
  public record RecentProduction(String id, String orderNo, String productName,
      String plannedQuantity, String unit, String status, Instant createdAt) {}
}
