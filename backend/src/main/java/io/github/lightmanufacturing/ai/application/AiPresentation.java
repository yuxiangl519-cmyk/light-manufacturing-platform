package io.github.lightmanufacturing.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.Map;

/** Public presentation boundary. Never forwards provider reasoning or arbitrary metadata. */
public final class AiPresentation {
 private AiPresentation(){}
 private static final Map<String,String> STAGES=Map.ofEntries(Map.entry("Decision","模型决策"),Map.entry("Tools","执行受控工具"),Map.entry("Gateway","连接分析服务"),Map.entry("Planner","确认范围与查询计划"),Map.entry("Context","更新查询条件"),Map.entry("Executor","读取受控业务数据"),Map.entry("TextToSQL","读取预设统计"),Map.entry("Analyzer","计算并校验指标"),Map.entry("Reflection","核对证据完整性"),Map.entry("Reporter","组织事实与建议"),Map.entry("Clarification","等待补充信息"),Map.entry("DimensionResolver","确认客户或商品"),Map.entry("DimensionExecutor","查询对象销售"),Map.entry("Operations","查询库存与工单"));
 public static String stage(String node){return STAGES.getOrDefault(node,"处理查询");}
 public static String text(String raw){
  if(raw==null)return "";
  return raw.replaceAll("(?is)<(?:think|analysis)>.*?</(?:think|analysis)>","[内部内容已隐藏]")
   .replaceAll("(?is)```(?:sql|thinking|analysis).*?```","[内部内容已隐藏]")
   .replaceAll("(?im)^.*\\b(?:SELECT\\s+.+?\\s+FROM|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|WITH\\s+\\w+\\s+AS)\\b[^\\r\\n]*","[查询实现已隐藏]")
   .replaceAll("(?i)sales_order\\.status=SHIPPED;(?:created_at|shipped_at);deleted_at IS NULL(?:;sales_order_item\\.line_amount;material_id;unit)?","已发货销售订单受控统计")
   .replace("WMS StockQueryService.balances：stock_balance；master material.min_stock","库存查询服务：当前余额与最低库存阈值")
   .replace("MES ProductionService：production_material_snapshot；WMS当前stock_balance（待领料）","生产服务确认快照与库存服务当前余额（待领料）")
   .replace("WMS StockQueryService：stock_document / stock_document_item，PENDING PRODUCTION_RECEIPT","库存单据服务：待执行成品入库单")
   .replaceAll("(?i)\\b(?:sales_order_item|sales_order|stock_balance|stock_document_item|stock_document|production_material_snapshot|production_order_item|production_order|material|warehouse)\\b(?:[.][a-z_]+)?","业务服务")
   .replace("line_amount","明细行金额").replace("created_at","创建时间").replace("shipped_at","实际发货时间").replace("deleted_at","删除标记").replace("material_id","物料ID").replace("min_stock","最低库存阈值")
   .replaceAll("(?i)Bearer\\s+[^\\s；;，,]+","[认证信息已隐藏]")
   .replaceAll("(?i)(?:gatewayToken|api[_-]?key|authorization|password|secret)\\s*[:=]\\s*[^\\s；;，,]+","[认证信息已隐藏]")
   .replaceAll("(?i)sk-[a-z0-9_-]{12,}","[密钥已隐藏]")
   .replaceAll("(?i)(?:电话|手机|phone|mobile)\\s*[:：=]?\\s*1[3-9]\\d{9}","[电话已隐藏]")
   .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}","[邮箱已隐藏]");
 }
 public static ObjectNode event(String event,JsonNode raw){
  ObjectNode value=JsonNodeFactory.instance.objectNode();
  if(event.equals("stage")){String node=raw.path("node").asText();value.put("node",STAGES.containsKey(node)?node:"Processing");value.put("message",stage(node));}
  else for(String key:new String[]{"content","message","code","status"})if(raw.path(key).isTextual())value.put(key,text(raw.path(key).asText()));
  if(raw.has("context"))value.set("context",AiContext.clean(raw.get("context")));
  if(raw.path("execution").isObject()){
   var stats=JsonNodeFactory.instance.objectNode();
   for(String key:new String[]{"toolCalls","modelCalls","elapsedMs","usageReports"}){var n=raw.path("execution").path(key);if(n.isIntegralNumber()&&n.canConvertToLong()&&n.asLong()>=0&&n.asLong()<=1000000000)stats.put(key,n.asLong());}
   var usage=JsonNodeFactory.instance.objectNode();for(String key:new String[]{"prompt_tokens","completion_tokens","total_tokens"}){var n=raw.path("execution").path("usage").path(key);if(n.isIntegralNumber()&&n.canConvertToLong()&&n.asLong()>=0&&n.asLong()<=1000000000)usage.put(key,n.asLong());}
   if(usage.isEmpty())stats.putNull("usage");else stats.set("usage",usage);value.set("execution",stats);
  }
  return value;
 }
}
