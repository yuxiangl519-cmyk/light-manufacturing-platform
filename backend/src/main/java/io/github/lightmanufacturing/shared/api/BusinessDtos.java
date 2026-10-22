package io.github.lightmanufacturing.shared.api;

import jakarta.validation.constraints.*;
import java.util.List;

public final class BusinessDtos {
  private BusinessDtos() {}

  public record Action(@NotNull @PositiveOrZero @Max(9007199254740991L) Long version) {}

  /** relatedDocuments is typed by each caller, keeping shared independent of business modules. */
  public record ActionResponse<T>(
      String operationId, boolean replayed, T document, List<?> relatedDocuments) {
    public ActionResponse {
      relatedDocuments = List.copyOf(relatedDocuments);
    }
  }
}
