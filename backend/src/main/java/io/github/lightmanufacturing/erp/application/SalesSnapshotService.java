package io.github.lightmanufacturing.erp.application;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One bounded InnoDB consistent read; no transaction survives an HTTP request. */
@Service
public class SalesSnapshotService {
  private final SalesAnalyticsService sales;
  private final SalesDimensionsService dimensions;
  public SalesSnapshotService(SalesAnalyticsService sales, SalesDimensionsService dimensions) {this.sales=sales;this.dimensions=dimensions;}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=30)
  public Map<String,Map<String,Object>> totals(LocalDate start,LocalDate end,String basis) {
    String id=UUID.randomUUID().toString(),asOf=Instant.now().toString();
    var result=new LinkedHashMap<String,Map<String,Object>>();
    result.put("summary",sales.summary(start,end,basis));
    result.put("trend",sales.trend(start,end,basis));
    result.put("customer",dimensions.ranking(start,end,"customer",20,basis));
    result.put("product",dimensions.ranking(start,end,"product",20,basis));
    result.values().forEach(v->stamp(v,id,asOf,"sales-total-trend-rankings"));
    return result;
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=30)
  public Map<String,Object> entity(LocalDate start,LocalDate end,String dimension,String entityId,String basis) {
    var result=dimensions.entitySales(start,end,dimension,entityId,basis);
    String id=UUID.randomUUID().toString(),asOf=Instant.now().toString();
    stamp(result,id,asOf,"sales-entity-summary-trend");
    for(String key:List.of("summary","trend")) {
      @SuppressWarnings("unchecked") var part=(Map<String,Object>)result.get(key);
      stamp(part,id,asOf,"sales-entity-summary-trend");
    }
    return result;
  }
  private static void stamp(Map<String,Object> value,String id,String asOf,String scope) {
    value.put("snapshotId",id);value.put("snapshotScope",scope);value.put("asOf",asOf);
  }
}
