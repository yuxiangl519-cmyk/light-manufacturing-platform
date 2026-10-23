package io.github.lightmanufacturing.ai.application;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiQuotaTest {
  @Test void shanghaiDayChangesAtSixteenUtc(){
    assertEquals(LocalDate.of(2026,10,22),AiQuotaService.day(Instant.parse("2026-10-22T15:59:59Z")));
    assertEquals(LocalDate.of(2026,10,23),AiQuotaService.day(Instant.parse("2026-10-22T16:00:00Z")));
  }
  @Test void quotasHaveExplicitDefaultsAndPositiveConfiguration(){
    var env=new MockEnvironment();var settings=new AiSettings(env);
    assertEquals(5,settings.perMinute());assertEquals(50,settings.perDay());
    env.setProperty("mfg.ai.quota.per-minute","0");assertThrows(IllegalStateException.class,settings::perMinute);
  }
}
