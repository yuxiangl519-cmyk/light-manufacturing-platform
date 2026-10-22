package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightmanufacturing.erp.application.*;
import io.github.lightmanufacturing.ai.application.AiOperationsService;
import io.github.lightmanufacturing.ai.dto.AiDtos.Query;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL -> public Java tools -> Python evidence engine. No LLM or HTTP-stream claim. */
@SpringBootTest @ActiveProfiles("test") @Transactional
class AiEvidenceIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired SalesAnalyticsService sales;
  @Autowired SalesDimensionsService dimensions;
  @Autowired AiOperationsService operations;
  @Autowired ObjectMapper json;
  @Test void realToolEvidenceMatchesIndependentHeaderAndBalanceReads()throws Exception{
    assertEquals("mini_mfg_test",jdbc.queryForObject("select database()",String.class));
    String code="EV5-"+UUID.randomUUID();long actor=jdbc.queryForObject("select min(id) from sys_user",Long.class);
    jdbc.update("insert into warehouse(created_at,updated_at,code,name,address,enabled) values(UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,'证据仓','不发送地址',true)",code);
    long warehouse=jdbc.queryForObject("select id from warehouse where code=?",Long.class,code);
    jdbc.update("insert into material(created_at,updated_at,code,name,type,unit,specification,min_stock,enabled,referenced) values(UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,'CPU','RAW_MATERIAL','个','',5,true,false)",code);
    String material=jdbc.queryForObject("select id from material where code=?",String.class,code);
    var start=LocalDate.of(2405,1,3);var end=start.plusDays(2);
    int[] amounts={6000,2000,1000,1000};
    for(int i=0;i<4;i++){
      String customerCode=code+"-"+i;
      jdbc.update("insert into customer(created_at,updated_at,code,name,contact_name,phone,address,enabled) values(UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,'证据客户','私密联系人','私密电话','私密地址',true)",customerCode);
      long customer=jdbc.queryForObject("select id from customer where code=?",Long.class,customerCode);
      for(int previous=0;previous<2;previous++)for(int day=0;day<2;day++){
        var stamp=Timestamp.from(start.minusDays(previous*2L).plusDays(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        String no="EV5-"+UUID.randomUUID();
        jdbc.update("insert into sales_order(created_at,updated_at,created_by,updated_by,order_no,customer_id,partner_name,warehouse_id,warehouse_name,created_by_name,status,total_amount) values(?,?,?,?,?,?,'证据客户',?,'证据仓','测试','SHIPPED',?)",stamp,stamp,actor,actor,no,customer,warehouse,BigDecimal.valueOf(amounts[i]).multiply(BigDecimal.valueOf(previous+1)).divide(BigDecimal.valueOf(2)));
      }
    }
    // Independent raw header SELECT; aggregation in test code, not production SUM/COUNT SQL.
    var rows=jdbc.queryForList("select id,customer_id,total_amount,created_at from sales_order where status='SHIPPED' and deleted_at is null and created_at>=? and created_at<?",Timestamp.from(start.minusDays(2).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()),Timestamp.from(end.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
    var boundary=Timestamp.from(start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    BigDecimal current=BigDecimal.ZERO,previous=BigDecimal.ZERO;int count=0,oldCount=0;Map<Long,BigDecimal> customers=new HashMap<>();
    for(var row:rows){var amount=(BigDecimal)row.get("total_amount");Object rawTime=row.get("created_at");var created=rawTime instanceof Timestamp t?t.toInstant():((LocalDateTime)rawTime).toInstant(ZoneOffset.UTC);if(!created.isBefore(boundary.toInstant())){current=current.add(amount);count++;customers.merge(((Number)row.get("customer_id")).longValue(),amount,BigDecimal::add);}else{previous=previous.add(amount);oldCount++;}}
    var ranked=customers.values().stream().sorted(Comparator.reverseOrder()).toList();
    var expected=new LinkedHashMap<String,String>();expected.put("currentAmount",current.toPlainString());expected.put("previousAmount",previous.toPlainString());expected.put("currentOrders",Integer.toString(count));expected.put("previousOrders",Integer.toString(oldCount));expected.put("top1Amount",ranked.get(0).toPlainString());expected.put("top3Amount",ranked.stream().limit(3).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());
    var balance=jdbc.queryForList("select quantity from stock_balance where warehouse_id=? and material_id=?",BigDecimal.class,warehouse,material);expected.put("currentStock",balance.isEmpty()?"0":balance.get(0).toPlainString());expected.put("minimumStock",jdbc.queryForObject("select min_stock from material where id=?",BigDecimal.class,material).toPlainString());
    var payload=Map.of("period",Map.of("startDate",start.toString(),"endDate",end.toString()),"summary",sales.summary(start,end),"trend",sales.trend(start,end),"ranking",dimensions.ranking(start,end,"customer",4),"stock",operations.execute("get_low_stock_alerts",new Query(null,null,null,null,null,null,null,Long.toString(warehouse),material,null)),"expected",expected);
    String body=json.writeValueAsString(payload);assertFalse(body.contains("私密"));assertFalse(body.contains("不发送地址"));
    Path root=Path.of("..").toAbsolutePath().normalize();Path python=root.resolve("agent/.venv/Scripts/python.exe");if(!Files.exists(python))python=root.resolve("agent/.venv/bin/python");assertTrue(Files.exists(python),"Locked Python environment required");
    var builder=new ProcessBuilder(python.toString(),"-m","evaluation.verify_evidence").directory(root.toFile()).redirectErrorStream(true);builder.environment().put("PYTHONPATH",root.resolve("agent").toString());
    var process=builder.start();try{try(var input=process.getOutputStream()){input.write(body.getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Python evidence verifier timed out");String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);assertTrue(output.contains("mysql-real-tools-python-evidence-no-model"));}finally{if(process.isAlive())process.destroyForcibly();}
  }
}
