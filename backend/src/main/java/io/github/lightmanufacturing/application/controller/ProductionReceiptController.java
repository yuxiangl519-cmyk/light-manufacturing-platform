package io.github.lightmanufacturing.application.controller;

import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.mes.application.ProductionService;
import io.github.lightmanufacturing.mes.dto.ProductionDtos.ReceiptResult;
import io.github.lightmanufacturing.shared.api.BusinessDtos.Action;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Coordinates the business source through MES; WMS never calls back into MES. */
@RestController
@RequestMapping("/api/v1/stock-documents")
public class ProductionReceiptController {
  private final ProductionService production;

  public ProductionReceiptController(ProductionService production) {
    this.production = production;
  }

  @PostMapping("/{id}/execute")
  @PreAuthorize("hasAuthority('wms:receipt:execute')")
  public ReceiptResult execute(@PathVariable String id, @Valid @RequestBody Action body) {
    return production.executeReceipt(Policy.id(id), body.version());
  }
}
