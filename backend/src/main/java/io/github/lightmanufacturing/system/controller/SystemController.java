package io.github.lightmanufacturing.system.controller;

import static io.github.lightmanufacturing.system.dto.ApiDtos.*;

import io.github.lightmanufacturing.shared.api.PageResponse;
import io.github.lightmanufacturing.system.application.*;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
  private final SystemService service;

  public SystemController(SystemService service) {
    this.service = service;
  }

  @GetMapping("/users")
  @PreAuthorize("hasAuthority('system:user:read')")
  public PageResponse<UserDto> users(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.users(q, enabled, page, size, sort);
  }

  @GetMapping("/users/{id}")
  @PreAuthorize("hasAuthority('system:user:read')")
  public UserDto user(@PathVariable String id) {
    return service.userDto(id);
  }

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('system:user:write')")
  public UserDto createUser(@Valid @RequestBody UserCreate body) {
    return service.createUser(body);
  }

  @PutMapping("/users/{id}")
  @PreAuthorize("hasAuthority('system:user:write')")
  public UserDto updateUser(@PathVariable String id, @Valid @RequestBody UserUpdate body) {
    return service.updateUser(id, body);
  }

  @PutMapping("/users/{id}/roles")
  @PreAuthorize("hasAuthority('system:user:write')")
  public UserDto userRoles(@PathVariable String id, @Valid @RequestBody UserRoles body) {
    return service.userRoles(id, body);
  }

  @PostMapping("/users/{id}/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('system:user:write')")
  public void resetPassword(@PathVariable String id, @Valid @RequestBody PasswordReset body) {
    service.resetPassword(id, body);
  }

  @GetMapping("/roles")
  @PreAuthorize("hasAuthority('system:role:read')")
  public PageResponse<RoleDto> roles(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.roles(q, page, size, sort);
  }

  @GetMapping("/roles/{id}")
  @PreAuthorize("hasAuthority('system:role:read')")
  public RoleDto role(@PathVariable String id) {
    return service.roleDto(id);
  }

  @PostMapping("/roles")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('system:role:write')")
  public RoleDto createRole(@Valid @RequestBody RoleWrite body) {
    return service.createRole(body);
  }

  @PutMapping("/roles/{id}")
  @PreAuthorize("hasAuthority('system:role:write')")
  public RoleDto updateRole(@PathVariable String id, @Valid @RequestBody RoleWrite body) {
    return service.updateRole(id, body);
  }

  @DeleteMapping("/roles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('system:role:write')")
  public void deleteRole(@PathVariable String id, @RequestParam long version) {
    service.deleteRole(id, version);
  }

  @GetMapping("/permissions")
  @PreAuthorize("hasAuthority('system:role:read')")
  public List<PermissionDto> permissions() {
    return service.permissions();
  }

  @PutMapping("/roles/{id}/permissions")
  @PreAuthorize("hasAuthority('system:role:write')")
  public RoleDto rolePermissions(
      @PathVariable String id, @Valid @RequestBody RolePermissions body) {
    return service.rolePermissions(id, body);
  }

  @GetMapping("/operation-logs")
  @PreAuthorize("hasAuthority('system:log:read')")
  public PageResponse<LogDto> logs(
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) String module,
      @RequestParam(required = false) Boolean success,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.logs(actorId, module, success, from, to, page, size);
  }
}
