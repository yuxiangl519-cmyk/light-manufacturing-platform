package io.github.lightmanufacturing.master.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class MasterDtos {
  private MasterDtos() {}

  public record MaterialWrite(
      Long version,
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 120) String name,
      @NotBlank String type,
      @NotBlank @Size(max = 10) String unit,
      @NotNull @Size(max = 255) String specification,
      @NotNull String minStock,
      @NotNull Boolean enabled) {}

  public record MaterialDto(
      String id,
      long version,
      String code,
      String name,
      String type,
      String unit,
      String specification,
      String minStock,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {}

  public record PartnerWrite(
      Long version,
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 120) String name,
      @NotNull @Size(max = 80) String contactName,
      @NotNull @Size(max = 40) String phone,
      @NotNull @Size(max = 255) String address,
      @NotNull Boolean enabled) {}

  public record PartnerDto(
      String id,
      long version,
      String code,
      String name,
      String contactName,
      String phone,
      String address,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {}

  public record WarehouseWrite(
      Long version,
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 120) String name,
      @NotNull @Size(max = 255) String address,
      @NotNull Boolean enabled) {}

  public record WarehouseDto(
      String id,
      long version,
      String code,
      String name,
      String address,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {}
}
