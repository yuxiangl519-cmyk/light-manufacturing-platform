package io.github.lightmanufacturing.master.controller;

import static io.github.lightmanufacturing.master.dto.MasterDtos.*;

import io.github.lightmanufacturing.master.application.MasterService;
import io.github.lightmanufacturing.shared.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MasterController {
  private final MasterService service;

  public MasterController(MasterService s) {
    service = s;
  }

  @GetMapping("/materials")
  @PreAuthorize("hasAuthority('master:material:read')")
  public PageResponse<MaterialDto> materials(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String type,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.materials(q, enabled, type, page, size, sort);
  }

  @GetMapping("/materials/{id}")
  @PreAuthorize("hasAuthority('master:material:read')")
  public MaterialDto material(@PathVariable String id) {
    return service.material(id);
  }

  @PostMapping("/materials")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('master:material:write')")
  public MaterialDto createMaterial(@Valid @RequestBody MaterialWrite body) {
    return service.saveMaterial(null, body);
  }

  @PutMapping("/materials/{id}")
  @PreAuthorize("hasAuthority('master:material:write')")
  public MaterialDto updateMaterial(
      @PathVariable String id, @Valid @RequestBody MaterialWrite body) {
    return service.saveMaterial(id, body);
  }

  @GetMapping("/customers")
  @PreAuthorize("hasAuthority('master:customer:read')")
  public PageResponse<PartnerDto> customers(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.customers(q, enabled, page, size, sort);
  }

  @GetMapping("/customers/{id}")
  @PreAuthorize("hasAuthority('master:customer:read')")
  public PartnerDto customer(@PathVariable String id) {
    return service.customer(id);
  }

  @PostMapping("/customers")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('master:customer:write')")
  public PartnerDto createCustomer(@Valid @RequestBody PartnerWrite body) {
    return service.saveCustomer(null, body);
  }

  @PutMapping("/customers/{id}")
  @PreAuthorize("hasAuthority('master:customer:write')")
  public PartnerDto updateCustomer(@PathVariable String id, @Valid @RequestBody PartnerWrite body) {
    return service.saveCustomer(id, body);
  }

  @GetMapping("/suppliers")
  @PreAuthorize("hasAuthority('master:supplier:read')")
  public PageResponse<PartnerDto> suppliers(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.suppliers(q, enabled, page, size, sort);
  }

  @GetMapping("/suppliers/{id}")
  @PreAuthorize("hasAuthority('master:supplier:read')")
  public PartnerDto supplier(@PathVariable String id) {
    return service.supplier(id);
  }

  @PostMapping("/suppliers")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('master:supplier:write')")
  public PartnerDto createSupplier(@Valid @RequestBody PartnerWrite body) {
    return service.saveSupplier(null, body);
  }

  @PutMapping("/suppliers/{id}")
  @PreAuthorize("hasAuthority('master:supplier:write')")
  public PartnerDto updateSupplier(@PathVariable String id, @Valid @RequestBody PartnerWrite body) {
    return service.saveSupplier(id, body);
  }

  @GetMapping("/warehouses")
  @PreAuthorize("hasAuthority('master:warehouse:read')")
  public PageResponse<WarehouseDto> warehouses(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.warehouses(q, enabled, page, size, sort);
  }

  @GetMapping("/warehouses/{id}")
  @PreAuthorize("hasAuthority('master:warehouse:read')")
  public WarehouseDto warehouse(@PathVariable String id) {
    return service.warehouse(id);
  }

  @PostMapping("/warehouses")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('master:warehouse:write')")
  public WarehouseDto createWarehouse(@Valid @RequestBody WarehouseWrite body) {
    return service.saveWarehouse(null, body);
  }

  @PutMapping("/warehouses/{id}")
  @PreAuthorize("hasAuthority('master:warehouse:write')")
  public WarehouseDto updateWarehouse(
      @PathVariable String id, @Valid @RequestBody WarehouseWrite body) {
    return service.saveWarehouse(id, body);
  }
}
