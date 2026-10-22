package io.github.lightmanufacturing.workbench.controller;

import io.github.lightmanufacturing.workbench.application.WorkbenchQueryService;
import io.github.lightmanufacturing.workbench.dto.WorkbenchDtos.Summary;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workbench")
public class WorkbenchController {
  private final WorkbenchQueryService service;
  public WorkbenchController(WorkbenchQueryService service) { this.service = service; }
  @GetMapping
  @PreAuthorize("hasAuthority('workbench:read')")
  public Summary summary(@RequestParam(required=false) Instant from,
      @RequestParam(required=false) Instant to, Authentication authentication) {
    return service.summary(from, to, authentication.getAuthorities().stream()
        .map(a -> a.getAuthority()).collect(Collectors.toSet()));
  }
}
