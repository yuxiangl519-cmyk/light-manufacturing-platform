package io.github.lightmanufacturing.erp.application;

import io.github.lightmanufacturing.ApiException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, read-only ERP projections exposed to authorized application services. */
@Service
@Transactional(readOnly = true)
public class SalesAnalyticsService {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final String DEFINITION = "按创建时间筛选当前已发货销售单的人民币含税订单金额，不是收入或利润";
  private static final String SOURCE = "sales_order.status=SHIPPED;created_at;deleted_at IS NULL";
  static final String ELIGIBLE = "status='SHIPPED' and deleted_at is null and created_at>=? and created_at<?";
  private static final String SUMMARY_SQL = "select coalesce(sum(total_amount),0) sales_amount,count(*) order_count from sales_order where " + ELIGIBLE;
  private static final String TREND_SQL = """
      select calendar.day,coalesce(sum(orders.total_amount),0) sales_amount,
      count(orders.id) order_count
      from json_table(?, '$[*]' columns(
        day date path '$.date', starts datetime path '$.start', ends datetime path '$.end'
      )) calendar
      left join sales_order orders on orders.status='SHIPPED' and orders.deleted_at is null
        and orders.created_at>=calendar.starts and orders.created_at<calendar.ends
      group by calendar.day order by calendar.day limit 366
      """;
  private final JdbcTemplate jdbc;

  public SalesAnalyticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Map<String, Object> summary(LocalDate startDate, LocalDate endDate) {
    return summary(startDate, endDate, "created_at");
  }
  public Map<String,Object> summary(LocalDate startDate, LocalDate endDate, String basis) {
    basis = basis(basis);
    long days = validate(startDate, endDate);
    LocalDate previousStart;
    try { previousStart = startDate.minusDays(days); }
    catch (DateTimeException ex) { throw ApiException.invalid("日期区间超出支持范围"); }
    var result = metadata(startDate, endDate, basis);
    result.put("previousPeriod", period(previousStart, startDate));
    result.put("current", aggregate(startDate, endDate, basis));
    result.put("previous", aggregate(previousStart, startDate, basis));
    return result;
  }

  public Map<String, Object> trend(LocalDate startDate, LocalDate endDate) {
    return trend(startDate, endDate, "created_at");
  }
  public Map<String,Object> trend(LocalDate startDate, LocalDate endDate, String basis) {
    final String column = basis(basis);
    validate(startDate, endDate);
    // Each boundary is calculated in Java: no server/session timezone or MySQL timezone tables.
    var calendar = new StringJoiner(",", "[", "]");
    for (LocalDate day = startDate; day.isBefore(endDate); day = day.plusDays(1)) {
      calendar.add("{\"date\":\"" + day + "\",\"start\":\"" + utc(day)
          + "\",\"end\":\"" + utc(day.plusDays(1)) + "\"}");
    }
    var points = jdbc.query(connection -> {
      PreparedStatement statement = connection.prepareStatement(TREND_SQL.replace("orders.created_at", "orders." + column));
      statement.setQueryTimeout(5);
      statement.setMaxRows(366);
      statement.setString(1, calendar.toString());
      return statement;
    }, (row, index) -> Map.of("date", row.getDate("day").toLocalDate().toString(),
        "salesAmount", row.getBigDecimal("sales_amount").setScale(2).toPlainString(),
        "orderCount", row.getString("order_count")));
    var result = metadata(startDate, endDate, basis);
    result.put("points", points);
    return result;
  }

  private Map<String, String> aggregate(LocalDate startDate, LocalDate endDate, String basis) {
    return jdbc.query(connection -> {
      PreparedStatement statement = connection.prepareStatement(SUMMARY_SQL.replace("created_at", basis(basis)));
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      statement.setTimestamp(1, Timestamp.from(startDate.atStartOfDay(SHANGHAI).toInstant()));
      statement.setTimestamp(2, Timestamp.from(endDate.atStartOfDay(SHANGHAI).toInstant()));
      return statement;
    }, row -> {
      row.next();
      return Map.of("salesAmount", row.getBigDecimal("sales_amount").setScale(2).toPlainString(),
          "orderCount", row.getString("order_count"));
    });
  }

  public static long validate(LocalDate start, LocalDate end) {
    if (start == null || end == null || !start.isBefore(end))
      throw ApiException.invalid("开始日期须早于结束日期，结束日期不包含在区间内");
    long days = ChronoUnit.DAYS.between(start, end);
    // MySQL DATETIME supports years 1000–9999, including the comparison period.
    if (days > 366 || start.getYear() < 1001 || end.getYear() > 9999)
      throw ApiException.invalid("日期区间须不超过366天且在支持范围内");
    return days;
  }

  static String utc(LocalDate day) {
    return LocalDateTime.ofInstant(day.atStartOfDay(SHANGHAI).toInstant(), ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
  }

  static Map<String, String> period(LocalDate start, LocalDate end) {
    return Map.of("startDate", start.toString(), "endDate", end.toString());
  }

  static Map<String, Object> metadata(LocalDate start, LocalDate end) {
    return metadata(start, end, "created_at");
  }
  public static String basis(String basis) {
    if (basis == null) return "created_at";
    if (!Set.of("created_at", "shipped_at").contains(basis)) throw ApiException.invalid("日期口径仅支持created_at或shipped_at");
    return basis;
  }
  static Map<String,Object> metadata(LocalDate start, LocalDate end, String basis) {
    basis = basis(basis);
    var result = new LinkedHashMap<String, Object>();
    result.put("dateBasis", basis);
    result.put("period", period(start, end));
    result.put("currency", "CNY");
    result.put("definition", "shipped_at".equals(basis) ? "按实际发货时间筛选当前已发货销售单的人民币含税发货金额，不是收入或利润" : DEFINITION);
    result.put("source", SOURCE.replace("created_at", basis));
    result.put("asOf", Instant.now().toString());
    return result;
  }
}
