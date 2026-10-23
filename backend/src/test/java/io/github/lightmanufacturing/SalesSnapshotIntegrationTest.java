package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.lightmanufacturing.erp.application.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real MySQL writer commits between two reads; timestamps alone could not make this pass. */
@SpringBootTest @ActiveProfiles("test")
class SalesSnapshotIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired SalesSnapshotService snapshots;
  @SpyBean SalesAnalyticsService sales;
  final LocalDate start=LocalDate.of(2455,1,1),end=start.plusDays(1);

  @Test void summaryTrendAndRankingKeepOneReadViewAcrossConcurrentCommit() throws Exception {
    assertEquals("mini_mfg_test",jdbc.queryForObject("select database()",String.class));
    String code="SNAP-"+UUID.randomUUID();
    long actor=jdbc.queryForObject("select min(id) from sys_user",Long.class);
    jdbc.update("insert into customer(created_at,updated_at,code,name,contact_name,phone,address,enabled) values(utc_timestamp(),utc_timestamp(),?,'快照测试','','','',true)",code);
    long customer=jdbc.queryForObject("select id from customer where code=?",Long.class,code);
    jdbc.update("insert into warehouse(created_at,updated_at,code,name,address,enabled) values(utc_timestamp(),utc_timestamp(),?,'快照测试仓','',true)",code);
    long warehouse=jdbc.queryForObject("select id from warehouse where code=?",Long.class,code);
    var time=Timestamp.from(start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    jdbc.update("insert into sales_order(created_at,updated_at,created_by,updated_by,order_no,customer_id,partner_name,warehouse_id,warehouse_name,created_by_name,status,total_amount,shipped_at) values(?,?,?,?,?,?,'快照测试',?,'快照测试仓','快照测试','SHIPPED',10.00,?)",time,time,actor,actor,code,customer,warehouse,time);
    long order=jdbc.queryForObject("select id from sales_order where order_no=?",Long.class,code);
    var writer=Executors.newSingleThreadExecutor();var once=new AtomicBoolean();
    try {
      doAnswer(invocation->{
        assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        Object result=invocation.callRealMethod();
        if(once.compareAndSet(false,true))writer.submit(()->jdbc.update("update sales_order set total_amount=99.00 where id=?",order)).get(10,TimeUnit.SECONDS);
        return result;
      }).when(sales).summary(eq(start),eq(end),eq("shipped_at"));
      var bundle=snapshots.totals(start,end,"shipped_at");
      @SuppressWarnings("unchecked") var current=(Map<String,String>)bundle.get("summary").get("current");
      @SuppressWarnings("unchecked") var points=(List<Map<String,String>>)bundle.get("trend").get("points");
      @SuppressWarnings("unchecked") var ranking=(List<Map<String,Object>>)bundle.get("customer").get("rows");
      assertEquals("10.00",current.get("salesAmount"));
      assertEquals("10.00",points.get(0).get("salesAmount"));
      assertEquals("10.00",ranking.get(0).get("salesAmount"));
      assertEquals(1,bundle.values().stream().map(v->v.get("snapshotId")).distinct().count());
      assertEquals("99.00",jdbc.queryForObject("select total_amount from sales_order where id=?",java.math.BigDecimal.class,order).toPlainString());
      @SuppressWarnings("unchecked") var next=(Map<String,String>)snapshots.totals(start,end,"shipped_at").get("summary").get("current");
      assertEquals("99.00",next.get("salesAmount"));
    } finally {writer.shutdownNow();reset(sales);jdbc.update("delete from sales_order where id=?",order);jdbc.update("delete from customer where id=?",customer);jdbc.update("delete from warehouse where id=?",warehouse);}
  }
}
