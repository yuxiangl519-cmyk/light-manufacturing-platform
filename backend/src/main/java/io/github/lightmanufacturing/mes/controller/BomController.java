package io.github.lightmanufacturing.mes.controller;

import static io.github.lightmanufacturing.mes.dto.BomDtos.*;

import io.github.lightmanufacturing.mes.application.BomService;
import io.github.lightmanufacturing.shared.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/boms")
public class BomController {
  private final BomService service;

  public BomController(BomService s) {
    service = s;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('mes:bom:read')")
  public PageResponse<BomDto> page(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String productMaterialId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.page(q, enabled, productMaterialId, page, size);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('mes:bom:read')")
  public BomDto get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('mes:bom:write')")
  public BomDto create(@Valid @RequestBody BomWrite body) {
    return service.save(null, body);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('mes:bom:write')")
  public BomDto update(@PathVariable String id, @Valid @RequestBody BomWrite body) {
    return service.save(id, body);
  }
}
