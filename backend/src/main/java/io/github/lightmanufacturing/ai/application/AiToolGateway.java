package io.github.lightmanufacturing.ai.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.ai.dto.AiDtos.Query;
import io.github.lightmanufacturing.erp.application.SalesAnalyticsService;
import io.github.lightmanufacturing.erp.application.SalesDimensionsService;
import io.github.lightmanufacturing.system.application.SystemService;
import io.github.lightmanufacturing.system.security.SessionPrincipal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class AiToolGateway {
  public record Grant(SessionPrincipal principal,String tenant,long conversation,String run,Instant expires,AtomicInteger calls) {}
  private final ConcurrentHashMap<String,Grant> grants=new ConcurrentHashMap<>();
  private final AiOperationsService operations;
  private final AiSettings settings;private final SystemService system;private final SalesAnalyticsService sales;private final AiConversationService conversations;private final SalesDimensionsService dimensions;
  public AiToolGateway(AiSettings settings,SystemService system,SalesAnalyticsService sales,AiConversationService conversations,SalesDimensionsService dimensions,AiOperationsService operations){this.operations=operations;this.settings=settings;this.system=system;this.sales=sales;this.conversations=conversations;this.dimensions=dimensions;}
  public String issue(SessionPrincipal principal,long conversation,String run){settings.available();grants.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));if(grants.size()>=8)throw new ApiException(429,"AI_CAPACITY","AI服务繁忙，请稍后重试");byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);grants.put(token,new Grant(principal,settings.tenant(),conversation,run,Instant.now().plusSeconds(180),new AtomicInteger()));return token;}
  public void revoke(String token){grants.remove(token);}
  public Grant validate(String token){var g=token==null?null:grants.get(token);if(g==null||g.expires().isBefore(Instant.now())||!g.tenant().equals(settings.tenant())||!conversations.running(g.conversation(),g.run(),g.principal().id()))throw new ApiException(403,"AI_CONTEXT_INVALID","分析授权已过期、会话不属于当前用户或任务已停止");system.current(g.principal());return g;}
  public Object execute(String token,String name,Query query){
    var g=validate(token);if(g.calls().incrementAndGet()>8)throw new ApiException(429,"AI_TOOL_LIMIT","工具调用达到上限");
    var permissions=system.current(g.principal()).user().permissions();
    var required=AiOperationsService.TOOLS.contains(name)?AiOperationsService.permissions(name,query.dimension()):Set.of("erp:sales:read");
    if(!permissions.containsAll(required))throw new ApiException(403,"FORBIDDEN","缺少该工具所需的库存、生产、单据或销售查询权限");
    if(AiOperationsService.TOOLS.contains(name))return operations.execute(name,query);
    if(query.startDate()==null||query.endDate()==null)throw ApiException.invalid("销售查询须提供起止日期");
    if(query.warehouseId()!=null||query.materialId()!=null||query.productionOrderId()!=null)throw ApiException.invalid("销售工具不接受库存或工单参数");
    boolean legacy=Set.of("get_sales_summary","get_sales_trend","execute_safe_query").contains(name);
    if(legacy&&(query.dimension()!=null||query.entityId()!=null||query.query()!=null||query.limit()!=null))throw ApiException.invalid("总体工具不接受维度参数");
    if(!"execute_safe_query".equals(name)&&query.templateId()!=null)throw ApiException.invalid("仅模板工具接受templateId");
    return switch(name){
      case "get_sales_summary"->sales.summary(query.startDate(),query.endDate());
      case "get_sales_trend"->sales.trend(query.startDate(),query.endDate());
      case "execute_safe_query"->{if(!Set.of("sales_summary","sales_trend").contains(Objects.toString(query.templateId(),"")))throw new ApiException(400,"SQL_TEMPLATE_REQUIRED","仅允许预审核销售查询模板，禁止自由SQL");yield "sales_summary".equals(query.templateId())?sales.summary(query.startDate(),query.endDate()):sales.trend(query.startDate(),query.endDate());}
      case "get_customer_sales_ranking","get_product_sales_ranking"->{
        if(query.dimension()!=null||query.entityId()!=null||query.query()!=null)throw ApiException.invalid("排行参数不匹配");
        yield dimensions.ranking(query.startDate(),query.endDate(),name.equals("get_customer_sales_ranking")?"customer":"product",query.limit()==null?10:query.limit());
      }
      case "resolve_sales_entity"->{
        if(query.entityId()!=null||query.limit()!=null)throw ApiException.invalid("候选查询参数不匹配");
        yield dimensions.resolve(query.startDate(),query.endDate(),query.dimension(),query.query());
      }
      case "get_entity_sales"->{
        if(query.query()!=null||query.limit()!=null)throw ApiException.invalid("指定对象参数不匹配");
        yield dimensions.entitySales(query.startDate(),query.endDate(),query.dimension(),query.entityId());
      }
      default->throw ApiException.invalid("工具不在白名单");
    };
  }
}
