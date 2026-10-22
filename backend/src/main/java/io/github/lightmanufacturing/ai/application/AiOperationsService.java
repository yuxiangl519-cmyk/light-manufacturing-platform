package io.github.lightmanufacturing.ai.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.ai.dto.AiDtos.Query;
import io.github.lightmanufacturing.master.application.MasterService;
import io.github.lightmanufacturing.mes.application.ProductionService;
import io.github.lightmanufacturing.wms.application.StockQueryService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only orchestration. Inventory and production remain the owners of their rules. */
@Service
@Transactional(readOnly=true, timeout=10)
public class AiOperationsService {
  public static final Set<String> TOOLS=Set.of("get_current_stock","get_low_stock_alerts","get_production_shortage","get_pending_production_receipts","resolve_operations_entity");
  private final StockQueryService stock;
  private final ProductionService production;
  private final MasterService master;
  private final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired
  public AiOperationsService(StockQueryService stock,ProductionService production,MasterService master){this(stock,production,master,Clock.systemUTC());}
  AiOperationsService(StockQueryService stock,ProductionService production,MasterService master,Clock clock){this.stock=stock;this.production=production;this.master=master;this.clock=clock;}

  public static Set<String> permissions(String tool,String dimension){
    return switch(tool){
      case "get_current_stock","get_low_stock_alerts"->Set.of("wms:stock:read");
      case "get_production_shortage"->Set.of("mes:production:read","wms:stock:read");
      case "get_pending_production_receipts"->Set.of("wms:document:read");
      case "resolve_operations_entity"->switch(Objects.toString(dimension,"")){
        case "warehouse","material"->Set.of("wms:stock:read");
        case "receiptWarehouse"->Set.of("wms:document:read");
        case "production"->Set.of("mes:production:read","wms:stock:read");
        default->throw ApiException.invalid("对象类型不支持");
      };
      default->throw ApiException.invalid("工具不在白名单");
    };
  }
  public Map<String,Object> execute(String name,Query q){
    if(!TOOLS.contains(name))throw ApiException.invalid("工具不在白名单");
    if(q.startDate()!=null||q.endDate()!=null||q.templateId()!=null||q.entityId()!=null)throw ApiException.invalid("只支持当前查询，不支持历史库存或销售参数");
    int limit=q.limit()==null?20:q.limit();if(limit<1||limit>20)throw ApiException.invalid("返回数量须为1至20");
    for(String id:Arrays.asList(q.warehouseId(),q.materialId(),q.productionOrderId()))if(id!=null)Policy.id(id);
    if(!name.equals("resolve_operations_entity")&&(q.dimension()!=null||q.query()!=null))throw ApiException.invalid("查询对象参数不匹配");
    Map<String,Object> result=new LinkedHashMap<>();result.put("asOf",clock.instant());result.put("timezone","Asia/Shanghai");
    result.put("source",switch(name){
      case "get_current_stock","get_low_stock_alerts"->"WMS StockQueryService.balances：stock_balance；master material.min_stock";
      case "get_production_shortage"->"MES ProductionService：production_material_snapshot；WMS当前stock_balance（待领料）";
      case "get_pending_production_receipts"->"WMS StockQueryService：stock_document / stock_document_item，PENDING PRODUCTION_RECEIPT";
      default->"MasterService当前资料 / ProductionService工单查询";
    });
    switch(name){
      case "resolve_operations_entity"->{
        if(q.warehouseId()!=null||q.materialId()!=null||q.productionOrderId()!=null||q.limit()!=null)throw ApiException.invalid("候选参数不匹配");
        String text=Policy.text(q.query(),100,"名称或单号");List<Map<String,Object>> candidates;String total;
        switch(Objects.toString(q.dimension(),"")){
          case "warehouse","receiptWarehouse"->{
            var page=master.warehouses(text,null,1,10,"code,asc");total=page.total();
            candidates=page.items().stream().map(w->candidate(w.id(),w.code(),w.name())).toList();
            // Only bounded, explicit warehouse vocabulary. Never invent an ID or
            // select the first result when several real warehouses match.
            if(candidates.isEmpty()){
              List<String> aliases=switch(text){
                case "原料仓","原料仓库"->List.of("原料","零部件");
                case "成品仓","成品仓库"->List.of("成品","整机");
                case "零部件仓","零部件仓库"->List.of("零部件");
                case "整机仓","整机仓库"->List.of("整机");
                default->List.of();
              };
              Map<String,Map<String,Object>> unique=new LinkedHashMap<>();boolean truncated=false;
              for(String alias:aliases){
                var matches=master.warehouses(alias,null,1,10,"code,asc");
                truncated|=Long.parseLong(matches.total())>10;
                for(var w:matches.items())unique.put(w.id(),candidate(w.id(),w.code(),w.name()));
              }
              if(!aliases.isEmpty()){
                candidates=unique.values().stream().limit(10).toList();
                total=Long.toString(truncated?Math.max(11,unique.size()):unique.size());
                result.put("matchMode","warehouseAlias");
              }
            }
          }
          case "material"->{var page=master.materials(text,null,null,1,10,"code,asc");total=page.total();candidates=page.items().stream().map(m->candidate(m.id(),m.code(),m.name())).toList();}
          case "production"->{var page=production.page(text,null,null,null,null,null,1,10,"id,desc");total=page.total();candidates=page.items().stream().map(o->candidate(o.id(),o.orderNo(),o.productName())).toList();}
          default->throw ApiException.invalid("对象类型不支持");
        }
        result.put("candidates",candidates);result.put("truncated",Long.parseLong(total)>10);
      }
      case "get_current_stock","get_low_stock_alerts"->{
        if(q.productionOrderId()!=null)throw ApiException.invalid("库存工具不接受工单参数");
        // Lists follow the existing active warehouse/material zero-stock projection.
        // Explicit IDs also support disabled historical master data, without widening the scope.
        boolean enabled=true;
        if(q.warehouseId()!=null)enabled=master.warehouse(q.warehouseId()).enabled();
        if(q.materialId()!=null)enabled=master.material(q.materialId()).enabled()&&enabled;
        var page=stock.balances(null,q.warehouseId(),q.materialId(),null,name.equals("get_low_stock_alerts"),enabled,1,limit,"materialCode,asc");
        result.put("rows",page.items());result.put("total",page.total());result.put("truncated",Long.parseLong(page.total())>limit);
        result.put("scope",enabled?"启用仓库与启用物料；无余额记录按零":"指定停用资料的现有余额记录；无记录不推断");
      }
      case "get_production_shortage"->{
        if(q.productionOrderId()==null||q.warehouseId()!=null||q.materialId()!=null||q.limit()!=null)throw ApiException.invalid("请提供工单ID，不接受其他范围");
        var order=production.detail(Policy.id(q.productionOrderId()));
        if(order.bomRevision()==null||Set.of("DRAFT","CANCELLED").contains(order.status()))throw new ApiException(409,"INVALID_STATE","只支持已确认且未取消工单的用料快照");
        result.put("orderNo",order.orderNo());result.put("productionOrderId",order.id());result.put("status",order.status());
        result.put("warehouseId",order.issueWarehouseId());result.put("warehouseName",order.issueWarehouseName());result.put("bomRevision",order.bomRevision());
        result.put("materialSnapshot",order.materialSnapshot());
        boolean needsIssue=order.status().equals("READY_TO_ISSUE");result.put("needsIssue",needsIssue);
        if(needsIssue){var requirements=production.requirements(Policy.id(order.id()));result.put("items",requirements.items());result.put("hasShortage",requirements.hasShortage());}
        else{
          var available=stock.availableQuantities(Policy.id(order.issueWarehouseId()),order.materialSnapshot().stream().map(i->Policy.id(i.materialId())).toList());
          result.put("currentStock",order.materialSnapshot().stream().map(i->Map.of("materialId",i.materialId(),"materialName",i.materialName(),"unit",i.unit(),"requiredQuantity",i.requiredQuantity(),"availableQuantity",available.get(Policy.id(i.materialId())).toPlainString())).toList());
          result.put("items",List.of());result.put("notice","工单已领料；快照是原始用料需求，现有数量是当前发料仓余额；缺料参考不适用，不再按现有余额重复计算补料。请勿将已领料需求当作未领料缺料。");
        }
      }
      case "get_pending_production_receipts"->{
        if(q.materialId()!=null||q.productionOrderId()!=null)throw ApiException.invalid("待入库工具只接受仓库ID与数量上限");
        var page=stock.documents(null,"PENDING","PRODUCTION_RECEIPT",q.warehouseId(),"PRODUCTION_ORDER",null,null,null,1,limit,"id,desc","IN");
        List<Map<String,Object>> rows=new ArrayList<>();int lines=0;
        for(var document:page.items()){
          var detail=stock.document(document.id());
          lines+=detail.items().size();if(lines>100)throw new ApiException(422,"AI_RESULT_LIMIT","待入库明细超过100行，请按仓库缩小范围或降低数量上限");
          rows.add(Map.of("id",document.id(),"documentNo",document.documentNo(),"warehouseId",document.warehouseId(),"warehouseName",document.warehouseName(),"sourceId",document.sourceId(),"sourceNo",document.sourceNo(),"status",document.status(),"items",detail.items().stream().map(i->Map.of("materialId",i.materialId(),"materialCode",i.materialCode(),"materialName",i.materialName(),"unit",i.unit(),"quantity",i.quantity())).toList()));
        }
        result.put("rows",rows);result.put("total",page.total());result.put("truncated",Long.parseLong(page.total())>limit);
      }
      default->throw ApiException.invalid("工具不在白名单");
    }
    return result;
  }
  private Map<String,Object> candidate(String id,String code,String name){return Map.of("id",id,"code",code,"name",name);}
}
