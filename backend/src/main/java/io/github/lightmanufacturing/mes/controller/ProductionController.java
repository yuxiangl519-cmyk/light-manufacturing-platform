package io.github.lightmanufacturing.mes.controller;

import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.mes.application.ProductionService;
import io.github.lightmanufacturing.mes.dto.ProductionDtos.*;
import io.github.lightmanufacturing.shared.api.*;
import io.github.lightmanufacturing.shared.api.BusinessDtos.*;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/production-orders")
public class ProductionController {
  private final ProductionService service;

  public ProductionController(ProductionService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('mes:production:read')")
  public PageResponse<Summary> page(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String productMaterialId,
      @RequestParam(required = false) String warehouseId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.page(q, status, productMaterialId, warehouseId, from, to, page, size, sort);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('mes:production:read')")
  public Detail detail(@PathVariable String id) {
    return service.detail(Policy.id(id));
  }

  @GetMapping("/{id}/material-requirements")
  @PreAuthorize("hasAuthority('mes:production:read') and hasAuthority('wms:stock:read')")
  public Requirements requirements(@PathVariable String id) {
    return service.requirements(Policy.id(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('mes:production:write')")
  public Detail create(@Valid @RequestBody Create body) {
    return service.create(body);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('mes:production:write')")
  public Detail update(@PathVariable String id, @Valid @RequestBody Update body) {
    return service.update(Policy.id(id), body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('mes:production:write')")
  public void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(Policy.id(id), version);
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAuthority('mes:production:write')")
  public ActionResponse<Detail> cancel(@PathVariable String id, @Valid @RequestBody Action body) {
    return service.cancel(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/confirm")
  @PreAuthorize("hasAuthority('mes:production:confirm')")
  public ActionResponse<Detail> confirm(@PathVariable String id, @Valid @RequestBody Action body) {
    return service.confirm(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/issue")
  @PreAuthorize("hasAuthority('mes:production:issue')")
  public ActionResponse<Detail> issue(@PathVariable String id, @Valid @RequestBody Action body) {
    return service.issue(Policy.id(id), body.version());
  }

  @PostMapping("/{id}/report")
  @PreAuthorize("hasAuthority('mes:production:report')")
  public ActionResponse<Detail> report(@PathVariable String id, @Valid @RequestBody Action body) {
    return service.report(Policy.id(id), body.version());
  }
}
