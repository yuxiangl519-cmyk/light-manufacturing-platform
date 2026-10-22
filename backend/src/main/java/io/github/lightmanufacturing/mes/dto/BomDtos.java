package io.github.lightmanufacturing.mes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class BomDtos {
  private BomDtos() {}

  public record ItemWrite(@NotBlank String materialId, @NotBlank String quantityPer) {}

  public record BomWrite(
      Long version,
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 120) String name,
      @NotBlank String productMaterialId,
      @NotNull Boolean enabled,
      @NotNull @Size(min = 1, max = 100) List<@Valid ItemWrite> items) {}

  public record ItemDto(
      String id,
      int lineNo,
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String quantityPer) {}

  public record BomDto(
      String id,
      long version,
      String code,
      String name,
      String productMaterialId,
      String productMaterialCode,
      String productMaterialName,
      int revision,
      boolean enabled,
      List<ItemDto> items,
      Instant createdAt,
      Instant updatedAt) {}
}
