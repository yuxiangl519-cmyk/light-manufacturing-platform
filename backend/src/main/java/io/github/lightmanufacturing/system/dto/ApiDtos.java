package io.github.lightmanufacturing.system.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApiDtos {
  private ApiDtos() {}

  public record Login(
      @NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 256) String password) {}

  public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}

  public record UserCreate(
      @NotBlank @Size(max = 50) String username,
      @NotBlank @Size(max = 80) String displayName,
      @NotBlank String initialPassword,
      @NotNull Boolean enabled,
      @NotNull @Size(max = 100) List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds) {}

  public record UserUpdate(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Size(max = 80) String displayName,
      @NotNull Boolean enabled) {}

  public record UserRoles(
      @NotNull @PositiveOrZero Long version,
      @NotNull @Size(max = 100) List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds) {}

  public record PasswordReset(
      @NotNull @PositiveOrZero Long version, @NotBlank String newPassword) {}

  public record RoleWrite(
      Long version,
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 80) String name,
      @NotNull @Size(max = 255) String description) {}

  public record RolePermissions(
      @NotNull @PositiveOrZero Long version,
      @NotNull @Size(max = 34) Set<String> permissionCodes) {}

  public record CurrentUser(
      String id,
      String username,
      String displayName,
      List<String> roles,
      List<String> permissions) {}

  public record UserDto(
      String id,
      long version,
      String username,
      String displayName,
      boolean enabled,
      List<String> roleIds,
      Instant createdAt,
      Instant updatedAt) {}

  public record RoleDto(
      String id,
      long version,
      String code,
      String name,
      String description,
      boolean builtin,
      List<String> permissionCodes) {}

  public record PermissionDto(String code, String name, String module) {}

  public record LogDto(
      String id,
      String actorId,
      String actorName,
      String module,
      String action,
      String resourceType,
      String resourceId,
      String resourceNo,
      boolean success,
      String errorCode,
      String summary,
      String traceId,
      String clientIp,
      Instant occurredAt) {}
}
