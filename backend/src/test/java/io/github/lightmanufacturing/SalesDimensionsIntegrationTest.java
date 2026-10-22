package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lightmanufacturing.erp.application.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class SalesDimensionsIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired SalesDimensionsService dimensions;
  @Autowired SalesAnalyticsService total;
  LocalDate start=LocalDate.of(2402,1,1),end=start.plusDays(2);
  long actor,a,b,p,q,w; String label;
  @BeforeEach void fixture(){
    assertEquals("mini_mfg_test",jdbc.queryForObject("select database()",String.class));
    actor=jdbc.queryForObject("select min(id) from sys_user",Long.class);label="DIM-"+UUID.randomUUID();
    a=customer(label+"客户");b=customer(label+"客户");
    p=product(label+"办公电脑","台");q=product(label+"办公电脑","套");
    jdbc.update("insert into warehouse(created_at,updated_at,code,name,address,enabled) values(UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,'维度仓','',true)",label);
    w=jdbc.queryForObject("select id from warehouse where code=?",Long.class,label);
    long o=order(a,0,"35.00","SHIPPED",false);line(o,1,p,"历史办公电脑","台",2,"10.00");line(o,2,q,"另一商品","套",3,"5.00");
    o=order(a,1,"20.00","SHIPPED",false);line(o,1,p,"最近办公快照","台",2,"10.00");
    o=order(b,0,"60.00","SHIPPED",false);line(o,1,q,"另一商品","套",12,"5.00");
    o=order(a,-1,"10.00","SHIPPED",false);line(o,1,p,"历史办公电脑","台",1,"10.00");
    for(String state:List.of("DRAFT","CONFIRMED","CANCELLED")) {o=order(a,0,"999.00",state,false);line(o,1,p,"历史办公电脑","台",1,"999.00");}
    o=order(a,0,"666.00","SHIPPED",true);line(o,1,p,"历史办公电脑","台",1,"666.00");
    o=order(a,2,"777.00","SHIPPED",false);line(o,1,p,"历史办公电脑","台",1,"777.00");
  }
  @Test void rankingsUseHeaderForCustomerAndLineAmountForProduct(){
    var customers=rows(dimensions.ranking(start,end,"customer",10));
    assertEquals(List.of(Long.toString(b),Long.toString(a)),customers.stream().map(r->r.get("id")).toList());
    assertEquals("60.00",customers.get(0).get("salesAmount"));assertEquals("55.00",customers.get(1).get("salesAmount"));
    assertEquals("2",customers.get(1).get("orderCount"));
    var products=rows(dimensions.ranking(start,end,"product",10));
    assertEquals("75.00",products.get(0).get("salesAmount"));assertEquals("40.00",products.get(1).get("salesAmount"));
    assertEquals(Map.of("套","15"),products.get(0).get("quantitiesByUnit"));
    assertEquals(Map.of("台","4"),products.get(1).get("quantitiesByUnit"));
    assertEquals("最近办公快照",products.get(1).get("name"));
    BigDecimal sum=products.stream().map(r->new BigDecimal((String)r.get("salesAmount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
    assertEquals(new BigDecimal("115.00"),sum);
    assertEquals("115.00",((Map<?,?>)total.summary(start,end).get("current")).get("salesAmount"));
  }
  @Test void scopedSummaryTrendAndIndependentDetailOracle(){
    var result=dimensions.entitySales(start,end,"product",Long.toString(p));
    var summary=(Map<?,?>)result.get("summary");
    var current=(Map<?,?>)summary.get("current");
    // Independent detail read; no production aggregate SQL or SUM/COUNT.
    var detail=jdbc.queryForList("select i.line_amount,i.quantity,o.id from sales_order_item i join sales_order o on o.id=i.sales_order_id where i.material_id=? and o.status='SHIPPED' and o.deleted_at is null and o.created_at>=? and o.created_at<?",p,Timestamp.from(start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()),Timestamp.from(end.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
    assertEquals(detail.stream().map(r->(BigDecimal)r.get("line_amount")).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2).toPlainString(),current.get("salesAmount"));
    assertEquals(Long.toString(detail.stream().map(r->r.get("id")).distinct().count()),current.get("orderCount"));
    assertEquals(Map.of("台","4"),current.get("quantitiesByUnit"));
    assertEquals("10.00",((Map<?,?>)summary.get("previous")).get("salesAmount"));
    var points=(List<?>)((Map<?,?>)result.get("trend")).get("points");
    assertEquals(2,points.size());assertEquals("20.00",((Map<?,?>)points.get(0)).get("salesAmount"));
    var customer=(Map<?,?>)dimensions.entitySales(start,end,"customer",Long.toString(a)).get("summary");
    assertEquals("55.00",((Map<?,?>)customer.get("current")).get("salesAmount"));assertEquals("2",((Map<?,?>)customer.get("current")).get("orderCount"));
  }
  @Test void ambiguityDisabledAndHistoricalSnapshotLookupExposeNoContacts(){
    var matches=dimensions.resolve(start,end,"customer",label+"客户");assertEquals(2,((List<?>)matches.get("candidates")).size());
    jdbc.update("update material set enabled=false,name='改名商品' where id=?",p);
    var history=dimensions.resolve(start,end,"product","历史办公电脑");
    assertEquals(Long.toString(p),((Map<?,?>)((List<?>)history.get("candidates")).get(0)).get("id"));
    assertFalse(history.toString().contains("phone"));assertFalse(matches.toString().contains("不要发给模型"));
    assertEquals("40.00",((Map<?,?>)((Map<?,?>)dimensions.entitySales(start,end,"product",Long.toString(p)).get("summary")).get("current")).get("salesAmount"));
    assertTrue(((List<?>)dimensions.resolve(start,end,"customer","%' OR 1=1 --").get("candidates")).isEmpty());
  }
  @Test void candidateCapAndHistoricalLabelsStayExplicit(){
    for(int i=0;i<10;i++)customer(label+"客户");
    var result=dimensions.resolve(start,end,"customer",label+"客户");
    assertEquals(true,result.get("truncated"));assertEquals(10,((List<?>)result.get("candidates")).size());
    var entity=(Map<?,?>)dimensions.entitySales(start,end,"product",Long.toString(p)).get("entity");
    assertEquals("最近办公快照",entity.get("name"));assertEquals("latestSalesSnapshot",entity.get("nameSource"));
  }
  @Test void limitsAndIdsRejectRatherThanWidenScope(){
    for(int n:List.of(0,21))assertThrows(ApiException.class,()->dimensions.ranking(start,end,"customer",n));
    assertThrows(ApiException.class,()->dimensions.ranking(start,start.plusDays(367),"customer",10));
    assertThrows(ApiException.class,()->dimensions.entitySales(start,end,"product","0"));
    assertThrows(ApiException.class,()->dimensions.entitySales(start,end,"product","9223372036854775807"));
    assertThrows(ApiException.class,()->dimensions.resolve(start,end,"supplier","x"));
    assertEquals(1,rows(dimensions.ranking(start,end,"product",1)).size());
    assertTrue(rows(dimensions.ranking(start.minusYears(1),end.minusYears(1),"customer",10)).isEmpty());
  }
  @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> value){return (List<Map<String,Object>>)value.get("rows");}
  long customer(String name){String code=UUID.randomUUID().toString();jdbc.update("insert into customer(created_at,updated_at,code,name,contact_name,phone,address,enabled) values(UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,?, '不要发给模型','13900000000','私密地址',false)",code,name);return jdbc.queryForObject("select id from customer where code=?",Long.class,code);}
  long product(String name,String unit){String code=UUID.randomUUID().toString();jdbc.update("insert into material(created_at,updated_at,code,name,type,unit,specification,min_stock,enabled,referenced) values(UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,?,'FINISHED_GOOD',?,'',0,false,true)",code,name,unit);return jdbc.queryForObject("select id from material where code=?",Long.class,code);}
  long order(long customer,int day,String amount,String status,boolean deleted){String no=UUID.randomUUID().toString();var stamp=Timestamp.from(start.plusDays(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());jdbc.update("insert into sales_order(created_at,updated_at,created_by,updated_by,order_no,customer_id,partner_name,warehouse_id,warehouse_name,created_by_name,status,total_amount,deleted_at) values(?,?,?,?,?,?,'历史客户快照',?,'仓','测试',?,?,?)",stamp,stamp,actor,actor,no,customer,w,status,new BigDecimal(amount),deleted?stamp:null);return jdbc.queryForObject("select id from sales_order where order_no=?",Long.class,no);}
  void line(long order,int n,long id,String name,String unit,int quantity,String price){jdbc.update("insert into sales_order_item(sales_order_id,line_no,material_id,material_code,material_name,unit,quantity,unit_price,line_amount) values(?,?,?,'快照编码',?,?,?,?,?)",order,n,id,name,unit,quantity,new BigDecimal(price),new BigDecimal(price).multiply(BigDecimal.valueOf(quantity)));}
}
