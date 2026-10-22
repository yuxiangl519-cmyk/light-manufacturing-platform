package io.github.lightmanufacturing.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightmanufacturing.erp.application.SalesAnalyticsService;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"mfg.bootstrap-enabled=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MFG_DEMO_ANALYTICS", matches = "true")
class DemoAnalyticsReadOnly {
  @Autowired SalesAnalyticsService analytics;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void verifyActualAiToolDataWithoutModelOrBusinessWrites() throws Exception {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    var start = LocalDate.parse("2026-09-21");
    var end = LocalDate.parse("2026-10-21");
    var summary = analytics.summary(start, end);
    var trend = analytics.trend(start, end);
    var current = (Map<?, ?>) summary.get("current");
    var previous = (Map<?, ?>) summary.get("previous");
    var points = (List<Map<String, Object>>) trend.get("points");
    assertEquals(30, points.size());
    BigDecimal sum =
        points.stream()
            .map(p -> new BigDecimal(p.get("salesAmount").toString()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, sum.compareTo(new BigDecimal(current.get("salesAmount").toString())));
    var zone = ZoneId.of("Asia/Shanghai");
    BigDecimal expected =
        jdbc.queryForObject(
            "select coalesce(sum(total_amount),0) from sales_order where status='SHIPPED' and"
                + " deleted_at is null and created_at>=? and created_at<?",
            BigDecimal.class,
            Timestamp.from(start.atStartOfDay(zone).toInstant()),
            Timestamp.from(end.atStartOfDay(zone).toInstant()));
    assertEquals(0, expected.compareTo(sum));
    assertTrue(
        new BigDecimal(previous.get("salesAmount").toString()).compareTo(new BigDecimal("80876"))
            >= 0);
    Path out = Path.of("../.local/demo-data/ai-tool-data.json");
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            out.toFile(),
            Map.of(
                "database",
                "mini_mfg_test",
                "summary",
                summary,
                "trend",
                trend,
                "modelInvoked",
                false));
  }
}
