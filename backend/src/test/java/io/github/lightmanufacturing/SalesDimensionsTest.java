package io.github.lightmanufacturing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.github.lightmanufacturing.erp.application.SalesDimensionsService;
import io.github.lightmanufacturing.master.application.SalesEntityLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.*;
import java.time.*;
import java.util.*;

class SalesDimensionsTest {
  @Test void rankingUsesBoundParametersAndPerStatementLimits() throws Exception {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(jdbc.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenAnswer(call->{((PreparedStatementCreator)call.getArgument(0)).createPreparedStatement(connection);return List.of();});
    var service=new SalesDimensionsService(jdbc,mock(SalesEntityLookupService.class));
    service.ranking(LocalDate.of(2026,10,1),LocalDate.of(2026,10,3),"product",7);
    verify(statement).setQueryTimeout(5);verify(statement).setMaxRows(201);
    verify(statement).setObject(1,Timestamp.from(Instant.parse("2026-09-30T16:00:00Z")));
    verify(statement).setObject(2,Timestamp.from(Instant.parse("2026-10-02T16:00:00Z")));
    verify(statement).setObject(3,7);
    verify(connection).prepareStatement(argThat(sql->sql.contains("count(distinct order_id)")&&sql.contains("status='SHIPPED'")&&sql.contains("limit ?")));
  }
  @Test void invalidBoundsNeverReadDatabase(){
    JdbcTemplate jdbc=mock(JdbcTemplate.class);var master=mock(SalesEntityLookupService.class);var service=new SalesDimensionsService(jdbc,master);LocalDate start=LocalDate.of(2026,1,1);
    assertThrows(ApiException.class,()->service.ranking(start,start.plusDays(367),"customer",10));
    assertThrows(ApiException.class,()->service.ranking(start,start.plusDays(1),"customer",21));
    assertThrows(ApiException.class,()->service.entitySales(start,start.plusDays(1),"product","0"));
    assertThrows(ApiException.class,()->service.resolve(start,start.plusDays(1),"supplier","x"));
    verifyNoInteractions(jdbc,master);
  }
  @Test void lookupEscapesWildcardsAndOmitsAllContactColumns() throws Exception {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(jdbc.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenAnswer(call->{((PreparedStatementCreator)call.getArgument(0)).createPreparedStatement(connection);return List.of();});
    new SalesEntityLookupService(jdbc).search("customer","a%_!");
    verify(statement).setObject(1,"%a!%!_!!%");verify(statement).setObject(2,"%a!%!_!!%");
    verify(statement).setQueryTimeout(5);verify(statement).setMaxRows(11);
    verify(connection).prepareStatement(argThat(sql->!sql.contains("phone")&&!sql.contains("contact")&&!sql.contains("address")&&!sql.contains("a%_!")));
  }
}
