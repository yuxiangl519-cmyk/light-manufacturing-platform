package io.github.lightmanufacturing.erp.controller;

import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.erp.application.PurchaseOrderService;
import io.github.lightmanufacturing.erp.dto.ErpDtos.*;
import io.github.lightmanufacturing.shared.api.*;
import io.github.lightmanufacturing.shared.api.BusinessDtos.*;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {
  private final PurchaseOrderService service;

  public PurchaseOrderController(PurchaseOrderService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('erp:purchase:read')")
  public PageResponse<PurchaseSummary> page(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String supplierId,
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.page(q, status, supplierId, warehouseId, from, to, page, size, sort);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('erp:purchase:read')")
  public PurchaseDetail detail(@PathVariable String id) {
    return service.detail(Policy.id(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('erp:purchase:write')")
  public PurchaseDetail create(@Valid @RequestBody PurchaseWrite body) {
    return service.create(body);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('erp:purchase:write')")
  public PurchaseDetail update(@PathVariable String id, @Valid @RequestBody PurchaseUpdate body) {
    return service.update(Policy.id(id), body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('erp:purchase:write')")
  public void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(Policy.id(id), version);
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAuthority('erp:purchase:write')")
  public ActionResponse<PurchaseDetail> cancel(
      @PathVariable String id, @Valid @RequestBody Action body) {
    return service.cancel(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/confirm")
  @PreAuthorize("hasAuthority('erp:purchase:confirm')")
  public ActionResponse<PurchaseDetail> confirm(
      @PathVariable String id, @Valid @RequestBody Action body) {
    return service.confirm(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/receive")
  @PreAuthorize("hasAuthority('erp:purchase:receive')")
  public ActionResponse<PurchaseDetail> receive(
      @PathVariable String id, @Valid @RequestBody Action body) {
    return service.receive(Policy.id(id), body.version());
  }
}
