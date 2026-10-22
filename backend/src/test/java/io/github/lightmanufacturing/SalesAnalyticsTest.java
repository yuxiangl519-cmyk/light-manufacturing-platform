package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.lightmanufacturing.erp.application.SalesAnalyticsService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

class SalesAnalyticsTest {
  @Test void rejectsInvalidRangesBeforeDatabaseAccess() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var service = new SalesAnalyticsService(jdbc);
    LocalDate start = LocalDate.of(2026, 1, 1);
    for (LocalDate end : List.of(start, start.minusDays(1), start.plusDays(367))) {
      assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
          () -> service.summary(start, end)).code);
      assertThrows(ApiException.class, () -> service.trend(start, end));
    }
    assertThrows(ApiException.class, () -> service.summary(null, start));
    assertThrows(ApiException.class, () -> service.trend(start, null));
    verifyNoInteractions(jdbc);
  }

  @Test void summaryBindsExclusiveUtcBoundariesAndEqualPreviousPeriod() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    ResultSet row = mock(ResultSet.class);
    when(row.next()).thenReturn(true);
    when(row.getBigDecimal("sales_amount")).thenReturn(new java.math.BigDecimal("123.40"));
    when(row.getString("order_count")).thenReturn("2");
    when(jdbc.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class)))
        .thenAnswer(call -> {
          ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection);
          return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(row);
        });
    var result = new SalesAnalyticsService(jdbc).summary(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 3));
    assertEquals(Map.of("salesAmount", "123.40", "orderCount", "2"), result.get("current"));
    assertEquals(Map.of("startDate", "2024-02-28", "endDate", "2024-03-01"), result.get("previousPeriod"));
    verify(statement).setTimestamp(1, Timestamp.from(Instant.parse("2024-02-29T16:00:00Z")));
    verify(statement).setTimestamp(2, Timestamp.from(Instant.parse("2024-03-02T16:00:00Z")));
    verify(statement).setTimestamp(1, Timestamp.from(Instant.parse("2024-02-27T16:00:00Z")));
    verify(statement).setTimestamp(2, Timestamp.from(Instant.parse("2024-02-29T16:00:00Z")));
    verify(statement, times(2)).setQueryTimeout(5);
    verify(statement, times(2)).setMaxRows(1);
    verify(connection, times(2)).prepareStatement(argThat(sql -> sql.contains("status='SHIPPED'")
        && sql.contains("deleted_at is null") && sql.contains("created_at>=? and created_at<?")));
    verify(jdbc, never()).setQueryTimeout(anyInt());
    assertDoesNotThrow(() -> Instant.parse((String) result.get("asOf")));
  }

  @Test void trendUsesOneBoundedFixedSqlAnd366CalendarDays() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenAnswer(call -> {
      ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection);
      return List.of();
    });
    new SalesAnalyticsService(jdbc).trend(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));
    var calendar = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(statement).setString(eq(1), calendar.capture());
    var days = new com.fasterxml.jackson.databind.ObjectMapper().readTree(calendar.getValue());
    assertEquals(366, days.size());
    assertEquals("2023-12-31 16:00:00", days.get(0).path("start").asText());
    assertEquals("2024-02-29", days.get(59).path("date").asText());
    assertEquals("2024-12-31 16:00:00", days.get(365).path("end").asText());
    verify(statement).setQueryTimeout(5);
    verify(statement).setMaxRows(366);
    verify(connection).prepareStatement(argThat(sql -> sql.contains("json_table(?,") && sql.contains("left join sales_order")
        && sql.contains("limit 366")));
    verify(jdbc, never()).setQueryTimeout(anyInt());
  }
}
