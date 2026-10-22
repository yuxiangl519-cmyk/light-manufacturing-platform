package io.github.lightmanufacturing.system.application;

import io.github.lightmanufacturing.system.domain.*;
import io.github.lightmanufacturing.system.repository.*;
import io.github.lightmanufacturing.system.security.*;

/** Immutable request facts survive clearing of the SecurityContext after the filter chain. */
public record AuditIdentity(Long id, String name) {
  public static final String REQUEST_ATTRIBUTE = AuditIdentity.class.getName();
}
