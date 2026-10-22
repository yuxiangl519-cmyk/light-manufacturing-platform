package io.github.lightmanufacturing.shared.security;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.shared.persistence.AuditedEntity;

public final class BusinessActor {
  private BusinessActor() {}

  public static long requireId() {
    Long id = AuditedEntity.actor();
    if (id == null) throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
    return id;
  }
}
