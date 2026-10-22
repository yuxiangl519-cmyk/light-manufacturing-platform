package io.github.lightmanufacturing.ai.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.ai.dto.AiDtos.Query;
import io.github.lightmanufacturing.master.application.MasterService;
import io.github.lightmanufacturing.mes.application.ProductionService;
import io.github.lightmanufacturing.shared.api.PageResponse;
import io.github.lightmanufacturing.wms.application.StockQueryService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AiOperationsTest {
  @Test void warehouseAliasUsesBackendCandidatesAndKeepsAmbiguity(){
    var master=mock(MasterService.class);
    var empty=new PageResponse<io.github.lightmanufacturing.master.dto.MasterDtos.WarehouseDto>(List.of(),1,10,"0");
    var first=new io.github.lightmanufacturing.master.dto.MasterDtos.WarehouseDto("41",0,"A","演示·电脑零部件仓","private",true,Instant.EPOCH,Instant.EPOCH);
    var second=new io.github.lightmanufacturing.master.dto.MasterDtos.WarehouseDto("42",0,"B","另一零部件仓","private",true,Instant.EPOCH,Instant.EPOCH);
    when(master.warehouses(anyString(),isNull(),eq(1),eq(10),eq("code,asc"))).thenReturn(empty);
    when(master.warehouses("零部件",null,1,10,"code,asc")).thenReturn(new PageResponse<>(List.of(first,second),1,10,"2"));
    var service=new AiOperationsService(mock(StockQueryService.class),mock(ProductionService.class),master);
    var result=service.execute("resolve_operations_entity",new Query(null,null,null,"warehouse",null,"原料仓",null,null,null,null));
    assertEquals(2,((List<?>)result.get("candidates")).size());
    assertFalse(result.toString().contains("private"));assertEquals(false,result.get("truncated"));
  }
  @Test void queryTimestampIsInjectableAndNotTheExecutionDay(){
    var stock=mock(StockQueryService.class);var instant=Instant.parse("2024-02-29T16:00:00Z");
    when(stock.balances(null,null,null,null,true,true,1,20,"materialCode,asc")).thenReturn(new PageResponse<>(List.of(),1,20,"0"));
    var service=new AiOperationsService(stock,mock(ProductionService.class),mock(MasterService.class),Clock.fixed(instant,ZoneOffset.UTC));
    var result=service.execute("get_low_stock_alerts",new Query(null,null,null,null,null,null,null,null,null,null));
    assertEquals(instant,result.get("asOf"));assertEquals("Asia/Shanghai",result.get("timezone"));
    assertEquals("0",result.get("total"));assertEquals(false,result.get("truncated"));
  }
  @Test void historicalOrOversizedQueriesFailBeforeAnyBusinessRead(){
    var stock=mock(StockQueryService.class);var production=mock(ProductionService.class);var master=mock(MasterService.class);
    var service=new AiOperationsService(stock,production,master);
    assertThrows(ApiException.class,()->service.execute("get_current_stock",new Query(LocalDate.of(2024,2,29),null,null,null,null,null,null,null,null,null)));
    assertThrows(ApiException.class,()->service.execute("get_pending_production_receipts",new Query(null,null,null,null,null,null,21,null,null,null)));
    verifyNoInteractions(stock,production,master);
  }
}
