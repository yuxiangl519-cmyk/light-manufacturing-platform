package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lightmanufacturing.erp.application.SalesAnalyticsService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL 8 projection semantics; fixture writes are rolled back after each test. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SalesAnalyticsIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired SalesAnalyticsService sales;
  long actor, customer, warehouse;

  @BeforeEach void fixture() {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    actor = jdbc.queryForObject("select min(id) from sys_user", Long.class);
    String code = "SA-" + UUID.randomUUID();
    jdbc.update("insert into customer(created_at,updated_at,code,name,contact_name,phone,address,enabled) values(utc_timestamp(),utc_timestamp(),?,'分析测试客户','','','',true)", code);
    customer = jdbc.queryForObject("select id from customer where code=?", Long.class, code);
    jdbc.update("insert into warehouse(created_at,updated_at,code,name,address,enabled) values(utc_timestamp(),utc_timestamp(),?,'分析测试仓','',true)", code);
    warehouse = jdbc.queryForObject("select id from warehouse where code=?", Long.class, code);
  }

  @Test void shippedOnlyUtcBoundariesPreviousPeriodAndZeroDays() {
    LocalDate start = LocalDate.of(2400, 3, 1), end = start.plusDays(3);
    Instant boundary = start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    order(boundary.minusMillis(1), "SHIPPED", "7.77", false);
    order(boundary, "SHIPPED", "10.10", false);
    order(boundary.plusSeconds(86400 * 2 - 1), "SHIPPED", "20.20", false);
    order(boundary.plusSeconds(86400 * 3), "SHIPPED", "90.00", false);
    order(boundary, "CONFIRMED", "100.00", false);
    order(boundary, "DRAFT", "100.00", false);
    order(boundary, "CANCELLED", "100.00", false);
    order(boundary, "SHIPPED", "100.00", true);
    var summary = sales.summary(start, end);
    assertEquals(Map.of("salesAmount", "30.30", "orderCount", "2"), summary.get("current"));
    assertEquals(Map.of("salesAmount", "7.77", "orderCount", "1"), summary.get("previous"));
    assertEquals("CNY", summary.get("currency"));
    @SuppressWarnings("unchecked")
    var points = (List<Map<String, String>>) sales.trend(start, end).get("points");
    assertEquals(List.of(
        Map.of("date", "2400-03-01", "salesAmount", "10.10", "orderCount", "1"),
        Map.of("date", "2400-03-02", "salesAmount", "20.20", "orderCount", "1"),
        Map.of("date", "2400-03-03", "salesAmount", "0.00", "orderCount", "0")), points);
  }

  @Test void empty366DayRangeReturnsAllNaturalDaysAndStringZeroes() {
    LocalDate start = LocalDate.of(2500, 1, 1), end = start.plusDays(366);
    assertEquals(Map.of("salesAmount", "0.00", "orderCount", "0"), sales.summary(start, end).get("current"));
    @SuppressWarnings("unchecked")
    var points = (List<Map<String, String>>) sales.trend(start, end).get("points");
    assertEquals(366, points.size());
    for (int i = 0; i < points.size(); i++) {
      assertEquals(Map.of("date", start.plusDays(i).toString(), "salesAmount", "0.00", "orderCount", "0"), points.get(i));
    }
  }

  @Test void independentDetailOracleChecksSummaryEveryDayAndMetadata() {
    LocalDate start = LocalDate.of(2401, 3, 1), end = start.plusDays(3);
    Instant boundary = start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    order(boundary.minusSeconds(1), "SHIPPED", "33.33", false);
    order(boundary, "SHIPPED", "10.01", false);
    order(boundary.plusSeconds(100), "SHIPPED", "20.02", false);
    order(boundary.plusSeconds(86400), "SHIPPED", "0.01", false);
    order(boundary, "CONFIRMED", "999.99", false);
    order(boundary, "SHIPPED", "999.99", true);
    // Independent oracle: fetch individual rows, filter and group in Java.
    // It deliberately does not reuse projection SQL, SUM, COUNT or JSON_TABLE.
    var rows = jdbc.queryForList("select created_at,status,total_amount,deleted_at from sales_order where customer_id=?", customer);
    Map<LocalDate, BigDecimal> amounts = new HashMap<>();
    Map<LocalDate, Long> counts = new HashMap<>();
    for (var row : rows) {
      if (!"SHIPPED".equals(row.get("status")) || row.get("deleted_at") != null) continue;
      LocalDate day = ((LocalDateTime) row.get("created_at")).toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
      amounts.merge(day, (BigDecimal) row.get("total_amount"), BigDecimal::add);
      counts.merge(day, 1L, Long::sum);
    }
    var summary = sales.summary(start, end);
    assertEquals(Map.of("startDate", start.toString(), "endDate", end.toString()), summary.get("period"));
    assertEquals(Map.of("startDate", start.minusDays(3).toString(), "endDate", start.toString()), summary.get("previousPeriod"));
    assertEquals("sales_order.status=SHIPPED;created_at;deleted_at IS NULL", summary.get("source"));
    assertEquals("CNY", summary.get("currency"));
    assertNotNull(Instant.parse((String) summary.get("asOf")));
    @SuppressWarnings("unchecked")
    var points = (List<Map<String,String>>) sales.trend(start, end).get("points");
    BigDecimal total = BigDecimal.ZERO; long count = 0;
    for (int i=0;i<3;i++) {
      LocalDate day=start.plusDays(i);
      BigDecimal amount=amounts.getOrDefault(day, BigDecimal.ZERO).setScale(2);
      long orders=counts.getOrDefault(day,0L);
      assertEquals(Map.of("date",day.toString(),"salesAmount",amount.toPlainString(),"orderCount",Long.toString(orders)),points.get(i));
      total=total.add(amount); count+=orders;
    }
    assertEquals(Map.of("salesAmount",total.toPlainString(),"orderCount",Long.toString(count)),summary.get("current"));
    assertEquals(Map.of("salesAmount",amounts.get(start.minusDays(1)).setScale(2).toPlainString(),"orderCount","1"),summary.get("previous"));
  }

  @Test void historicalShanghaiDstUsesLocalMidnightRatherThanFixedUtcEight() {
    LocalDate start=LocalDate.of(1990,6,1), end=start.plusDays(2);
    Instant boundary=start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    assertEquals(Instant.parse("1990-05-31T15:00:00Z"),boundary);
    order(boundary.minusMillis(1),"SHIPPED","7.77",false);
    order(boundary,"SHIPPED","10.01",false);
    order(boundary.plusSeconds(86400),"SHIPPED","20.02",false);
    order(end.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),"SHIPPED","99.99",false);
    var summary=sales.summary(start,end);
    assertEquals(Map.of("salesAmount","30.03","orderCount","2"),summary.get("current"));
    assertEquals(Map.of("salesAmount","7.77","orderCount","1"),summary.get("previous"));
    @SuppressWarnings("unchecked")
    var points=(List<Map<String,String>>)sales.trend(start,end).get("points");
    assertEquals(List.of(Map.of("date","1990-06-01","salesAmount","10.01","orderCount","1"),
        Map.of("date","1990-06-02","salesAmount","20.02","orderCount","1")),points);
  }

  private void order(Instant created, String status, String amount, boolean deleted) {
    jdbc.update("""
        insert into sales_order(created_at,updated_at,created_by,updated_by,order_no,
          customer_id,partner_name,warehouse_id,warehouse_name,created_by_name,status,total_amount,deleted_at)
        values(?,?,?,?,?,?,'分析测试客户',?,'分析测试仓','分析测试',?,?,?)
        """, Timestamp.from(created), Timestamp.from(created), actor, actor, "SA-" + UUID.randomUUID(),
        customer, warehouse, status, new BigDecimal(amount), deleted ? Timestamp.from(created) : null);
  }
}
