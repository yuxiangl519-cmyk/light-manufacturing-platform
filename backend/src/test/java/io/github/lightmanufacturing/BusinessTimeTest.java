package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lightmanufacturing.shared.time.BusinessTime;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BusinessTimeTest {
  @Test
  void requiresExplicitLocalImportAndRestoresAfterFailure() {
    System.clearProperty("mfg.demo.import");
    assertThrows(IllegalStateException.class, () -> BusinessTime.at(Instant.EPOCH));
    System.setProperty("mfg.demo.import", "true");
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> {
            try (var ignored = BusinessTime.at(Instant.parse("2026-09-21T01:00:00Z"))) {
              assertEquals("2026-09-21", BusinessTime.today(ZoneId.of("Asia/Shanghai")).toString());
              assertEquals("TIMESTAMP '2026-09-21 01:00:00.000'", BusinessTime.sqlNow());
              throw new IllegalArgumentException();
            }
          });
      assertEquals("utc_timestamp(3)", BusinessTime.sqlNow());
    } finally {
      System.clearProperty("mfg.demo.import");
    }
  }

  @Test
  void doesNotLeakToOtherThreadsAndRejectsNestedScopes() throws Exception {
    System.setProperty("mfg.demo.import", "true");
    try (var ignored = BusinessTime.at(Instant.parse("2026-08-22T01:00:00Z"))) {
      assertThrows(IllegalStateException.class, () -> BusinessTime.at(Instant.EPOCH));
      var executor = Executors.newSingleThreadExecutor();
      try {
        assertEquals("utc_timestamp(3)", executor.submit(BusinessTime::sqlNow).get());
      } finally {
        executor.shutdownNow();
      }
    } finally {
      System.clearProperty("mfg.demo.import");
    }
    assertTrue(Duration.between(BusinessTime.now(), Instant.now()).abs().getSeconds() < 2);
  }
}
