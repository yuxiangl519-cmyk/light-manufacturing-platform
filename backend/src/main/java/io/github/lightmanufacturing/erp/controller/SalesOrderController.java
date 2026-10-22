package io.github.lightmanufacturing.erp.controller;

import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.erp.application.SalesOrderService;
import io.github.lightmanufacturing.erp.dto.ErpDtos.*;
import io.github.lightmanufacturing.shared.api.*;
import io.github.lightmanufacturing.shared.api.BusinessDtos.*;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sales-orders")
public class SalesOrderController {
  private final SalesOrderService service;

  public SalesOrderController(SalesOrderService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('erp:sales:read')")
  public PageResponse<SalesSummary> page(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.page(q, status, customerId, warehouseId, from, to, page, size, sort);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('erp:sales:read')")
  public SalesDetail detail(@PathVariable String id) {
    return service.detail(Policy.id(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('erp:sales:write')")
  public SalesDetail create(@Valid @RequestBody SalesWrite body) {
    return service.create(body);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('erp:sales:write')")
  public SalesDetail update(@PathVariable String id, @Valid @RequestBody SalesUpdate body) {
    return service.update(Policy.id(id), body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('erp:sales:write')")
  public void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(Policy.id(id), version);
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAuthority('erp:sales:write')")
  public ActionResponse<SalesDetail> cancel(
      @PathVariable String id, @Valid @RequestBody Action body) {
    return service.cancel(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/confirm")
  @PreAuthorize("hasAuthority('erp:sales:confirm')")
  public ActionResponse<SalesDetail> confirm(
      @PathVariable String id, @Valid @RequestBody Action body) {
    return service.confirm(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/ship")
  @PreAuthorize("hasAuthority('erp:sales:ship')")
  public ActionResponse<SalesDetail> ship(
      @PathVariable String id, @Valid @RequestBody Action body) {
    return service.ship(Policy.id(id), body.version());
  }
}
