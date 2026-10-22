package io.github.lightmanufacturing.wms.controller;

import static io.github.lightmanufacturing.wms.dto.StockDtos.*;

import io.github.lightmanufacturing.shared.api.PageResponse;
import io.github.lightmanufacturing.wms.application.StockQueryService;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StockController {
  private final StockQueryService service;

  public StockController(StockQueryService service) {
    this.service = service;
  }

  @GetMapping("/stock-documents")
  @PreAuthorize("hasAuthority('wms:document:read')")
  public PageResponse<Document> documents(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String sourceId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.documents(
        q, status, kind, warehouseId, sourceType, sourceId, from, to, page, size, sort, direction);
  }

  @GetMapping("/stock-documents/{id}")
  @PreAuthorize("hasAuthority('wms:document:read')")
  public Detail document(@PathVariable String id) {
    return service.document(id);
  }

  @GetMapping("/stock-balances")
  @PreAuthorize("hasAuthority('wms:stock:read')")
  public PageResponse<Balance> balances(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) String materialId,
      @RequestParam(required = false) String type,
      @RequestParam(defaultValue = "false") boolean lowStockOnly,
      @RequestParam(defaultValue = "true") boolean enabled,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "materialCode,asc") String sort) {
    return service.balances(
        q, warehouseId, materialId, type, lowStockOnly, enabled, page, size, sort);
  }

  @GetMapping("/stock-ledgers")
  @PreAuthorize("hasAuthority('wms:ledger:read')")
  public PageResponse<Ledger> ledgers(
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) String materialId,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String sourceId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "id,desc") String sort) {
    return service.ledgers(
        warehouseId, materialId, sourceType, sourceId, from, to, page, size, sort);
  }
}
