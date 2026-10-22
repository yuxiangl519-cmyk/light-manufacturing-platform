package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lightmanufacturing.ai.application.AiOperationsService;
import io.github.lightmanufacturing.ai.dto.AiDtos.Query;
import io.github.lightmanufacturing.master.application.MasterService;
import io.github.lightmanufacturing.master.dto.MasterDtos.*;
import io.github.lightmanufacturing.mes.application.*;
import io.github.lightmanufacturing.mes.dto.BomDtos.*;
import io.github.lightmanufacturing.mes.dto.ProductionDtos.*;
import io.github.lightmanufacturing.wms.application.*;
import io.github.lightmanufacturing.wms.domain.StockPosting;
import io.github.lightmanufacturing.wms.dto.StockDtos.Balance;
import io.github.lightmanufacturing.system.application.SystemService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL, rollback fixtures. All stock changes use the inventory application service. */
@SpringBootTest @ActiveProfiles("test") @Transactional
class AiOperationsIntegrationTest {
  @Autowired AiOperationsService ai;
  @Autowired MasterService master;
  @Autowired BomService boms;
  @Autowired ProductionService production;
  @Autowired InventoryService inventory;
  @Autowired SystemService system;
  @Autowired JdbcTemplate jdbc;
  String raw,product,warehouse,finished,bom;long actor;
  @BeforeEach void setup(){
    assertEquals("mini_mfg_test",jdbc.queryForObject("select database()",String.class));
    var principal=system.authenticate("admin",System.getenv("MFG_ADMIN_PASSWORD")).principal();actor=principal.id();
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
    raw=master.saveMaterial(null,new MaterialWrite(null,code(),"CPU","RAW_MATERIAL","个","","5",true)).id();
    product=master.saveMaterial(null,new MaterialWrite(null,code(),"标准办公台式机","FINISHED_GOOD","台","","0",true)).id();
    warehouse=master.saveWarehouse(null,new WarehouseWrite(null,code(),"原料仓","私密地址",true)).id();
    finished=master.saveWarehouse(null,new WarehouseWrite(null,code(),"成品仓","私密地址",true)).id();
    bom=boms.save(null,new BomWrite(null,code(),"组装BOM",product,true,List.of(new ItemWrite(raw,"2")))).id();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  String code(){return "AI4-"+UUID.randomUUID();}
  Query query(String w,String m,String order){return new Query(null,null,null,null,null,null,null,w,m,order);}
  Detail order(){var o=production.create(new Create(product,bom,"4",warehouse,finished,""));production.confirm(Long.parseLong(o.id()),o.version());return production.detail(Long.parseLong(o.id()));}
  void receiveRaw(int count){inventory.post(new StockPosting(StockPosting.Kind.PURCHASE_RECEIPT,Long.parseLong(raw),code(),Long.parseLong(warehouse),actor,List.of(new StockPosting.Line(Long.parseLong(raw),Long.parseLong(raw),BigDecimal.valueOf(count)))));}
  @SuppressWarnings("unchecked") List<Balance> balances(String tool,String w,String m){return (List<Balance>)ai.execute(tool,query(w,m,null)).get("rows");}
  @Test void zeroBalanceThresholdEqualityAndIndependentDatabaseRead(){
    var zero=balances("get_current_stock",warehouse,raw);assertEquals(1,zero.size());assertEquals("0",zero.get(0).quantity());assertEquals("5",zero.get(0).minStock());
    assertTrue(zero.get(0).lowStock());assertEquals(1,balances("get_low_stock_alerts",warehouse,raw).size());
    receiveRaw(5);
    assertTrue(balances("get_low_stock_alerts",warehouse,raw).isEmpty());
    var actual=balances("get_current_stock",warehouse,raw).get(0);
    assertEquals(jdbc.queryForObject("select quantity from stock_balance where warehouse_id=? and material_id=?",BigDecimal.class,warehouse,raw).toPlainString(),actual.quantity());
    assertFalse(ai.execute("get_current_stock",query(warehouse,raw,null)).toString().contains("私密地址"));
  }
  @Test void shortageUsesConfirmedSnapshotNotCurrentBomAndDoesNotReserve(){
    receiveRaw(3);var first=order();var second=order();
    var current=boms.get(bom);boms.save(bom,new BomWrite(current.version(),current.code(),current.name(),product,true,List.of(new ItemWrite(raw,"9"))));
    var data=ai.execute("get_production_shortage",query(null,null,first.id()));
    var item=((List<Requirement>)data.get("items")).get(0);
    var independent=jdbc.queryForMap("select required_quantity,quantity_per from production_material_snapshot where production_order_id=? and material_id=?",first.id(),raw);
    assertEquals(((BigDecimal)independent.get("required_quantity")).toPlainString(),item.requiredQuantity());
    assertEquals("8",item.requiredQuantity());assertEquals("3",item.availableQuantity());assertEquals("5",item.shortageQuantity());assertEquals(1,data.get("bomRevision"));
    var balance=jdbc.queryForObject("select quantity from stock_balance where warehouse_id=? and material_id=?",BigDecimal.class,warehouse,raw);
    assertEquals(((BigDecimal)independent.get("required_quantity")).subtract(balance).max(BigDecimal.ZERO).toPlainString(),item.shortageQuantity());
    assertEquals("9",boms.get(bom).items().get(0).quantityPer());
    var repeated=((List<Requirement>)ai.execute("get_production_shortage",query(null,null,second.id())).get("items")).get(0);
    assertEquals("3",repeated.availableQuantity());assertEquals("5",repeated.shortageQuantity());
    assertEquals("3",balances("get_current_stock",warehouse,raw).get(0).quantity());
  }
  @Test void pendingReceiptDoesNotAddInventoryAndIssuedOrderDoesNotReplenishTwice(){
    receiveRaw(8);var o=order();
    var sufficient=ai.execute("get_production_shortage",query(null,null,o.id()));assertEquals(false,sufficient.get("hasShortage"));assertEquals("0",((List<Requirement>)sufficient.get("items")).get(0).shortageQuantity());
    production.issue(Long.parseLong(o.id()),o.version());
    o=production.detail(Long.parseLong(o.id()));production.report(Long.parseLong(o.id()),o.version());
    var pending=ai.execute("get_pending_production_receipts",query(finished,null,null));
    var rows=(List<Map<String,Object>>)pending.get("rows");assertEquals(1,rows.size());
    var items=(List<Map<String,Object>>)rows.get(0).get("items");assertEquals("4",items.get(0).get("quantity"));
    assertEquals("0",balances("get_current_stock",finished,product).get(0).quantity());
    assertEquals(0,jdbc.queryForObject("select count(*) from stock_ledger where warehouse_id=? and material_id=?",Integer.class,finished,product));
    var issued=ai.execute("get_production_shortage",query(null,null,o.id()));assertEquals(false,issued.get("needsIssue"));assertTrue(((List<?>)issued.get("items")).isEmpty());
    var current=(List<Map<String,Object>>)issued.get("currentStock");assertEquals("8",current.get(0).get("requiredQuantity"));assertEquals("0",current.get(0).get("availableQuantity"));assertFalse(current.get(0).containsKey("shortageQuantity"));
    assertFalse(pending.toString().contains("executedByName"));
  }
  @Test void unconfirmedOrderInvalidIdsHistoryAndParameterMixRejected(){
    var draft=production.create(new Create(product,bom,"1",warehouse,finished,""));
    assertThrows(ApiException.class,()->ai.execute("get_production_shortage",query(null,null,draft.id())));
    assertThrows(ApiException.class,()->ai.execute("get_current_stock",query("0",raw,null)));
    assertThrows(ApiException.class,()->ai.execute("get_current_stock",new Query(java.time.LocalDate.of(2020,1,1),null,null,null,null,null,null,warehouse,raw,null)));
    assertThrows(ApiException.class,()->ai.execute("get_pending_production_receipts",query(null,raw,null)));
    assertThrows(ApiException.class,()->ai.execute("get_current_stock",new Query(null,null,null,null,null,null,21,warehouse,raw,null)));
  }
  @Test void candidateAmbiguityAndBoundSearchExposeMinimalFields(){
    String name=code();master.saveWarehouse(null,new WarehouseWrite(null,code(),name,"不能发送的地址",true));master.saveWarehouse(null,new WarehouseWrite(null,code(),name,"不能发送的地址",false));
    var matches=ai.execute("resolve_operations_entity",new Query(null,null,null,"warehouse",null,name,null,null,null,null));
    assertEquals(2,((List<?>)matches.get("candidates")).size());assertFalse(matches.toString().contains("地址"));
    var injected=ai.execute("resolve_operations_entity",new Query(null,null,null,"material",null,"%' OR 1=1 --",null,null,null,null));
    assertTrue(((List<?>)injected.get("candidates")).isEmpty());
  }
}
