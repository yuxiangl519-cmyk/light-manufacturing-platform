package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyTest {
  @Test
  void fixedCatalogHasExactly34Permissions() {
    assertEquals(34, Policy.permissions().size());
    assertTrue(Policy.permissions().contains("system:user:write"));
  }

  @Test
  void passwordRejectsBcryptTruncationAndShortInput() {
    assertThrows(ApiException.class, () -> Policy.password("short"));
    assertThrows(ApiException.class, () -> Policy.password("密".repeat(25)));
    assertDoesNotThrow(() -> Policy.password("Valid-password-2026"));
  }

  @Test
  void actionRequiresItsReadPermission() {
    assertThrows(
        ApiException.class, () -> Policy.validatePermissions(Set.of("erp:purchase:receive")));
    assertDoesNotThrow(
        () -> Policy.validatePermissions(Set.of("erp:purchase:read", "erp:purchase:receive")));
  }

  @Test
  void testGuardRejectsDevelopmentResources() {
    assertThrows(
        IllegalStateException.class,
        () ->
            TestResourceGuard.validate(
                "jdbc:mysql://127.0.0.1:13306/mini_mfg_dev", "light-mfg:test:", 16380));
    assertThrows(
        IllegalStateException.class,
        () ->
            TestResourceGuard.validate(
                "jdbc:mysql://127.0.0.1:13306/mini_mfg_test", "light-mfg:local:", 16380));
    assertDoesNotThrow(
        () ->
            TestResourceGuard.validate(
                "jdbc:mysql://127.0.0.1:13306/mini_mfg_test?serverTimezone=UTC",
                "light-mfg:test:",
                16380));
  }
}
