package io.github.lightmanufacturing.system.security;

import java.io.Serializable;

public record SessionPrincipal(long id, long authVersion)
    implements Serializable, io.github.lightmanufacturing.shared.security.ActorPrincipal {
  public long actorId() {
    return id;
  }

  private static final long serialVersionUID = 1L;
}
