package io.github.lightmanufacturing.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.lightmanufacturing.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/** Conditions only. Neither stored IDs nor user choices are authorization grants. */
public final class AiContext {
  private AiContext(){}
  private static final Set<String> OPERATIONS=Set.of("get_current_stock","get_low_stock_alerts","get_production_shortage","get_pending_production_receipts");
  private static final Pattern SENSITIVE=Pattern.compile("(?i)(?<!\\d)1[3-9]\\d{9}(?!\\d)|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|sk-[A-Z0-9_-]{12,}");
  public static boolean id(JsonNode n){if(n==null||!n.isTextual()||!n.asText().matches("[1-9][0-9]{0,18}"))return false;try{return Long.parseLong(n.asText())>0;}catch(NumberFormatException e){return false;}}
  private static void choice(ObjectNode target,JsonNode source,String key,String... values){var n=source.get(key);if(n!=null&&n.isTextual()&&Set.of(values).contains(n.asText()))target.put(key,n.asText());}
  private static String query(JsonNode n){if(n==null||!n.isTextual()||n.asText().isBlank()||n.asText().length()>100||SENSITIVE.matcher(n.asText()).find())return null;return n.asText();}
  private static ObjectNode lookup(JsonNode n){
    if(n==null||!n.isObject()||query(n.get("query"))==null)return null;
    String field=n.path("field").asText(),dimension=n.path("dimension").asText();
    boolean valid=switch(field){case "warehouseId"->Set.of("warehouse","receiptWarehouse").contains(dimension);case "materialId"->dimension.equals("material");case "productionOrderId"->dimension.equals("production");default->false;};
    return valid?JsonNodeFactory.instance.objectNode().put("dimension",dimension).put("field",field).put("query",query(n.get("query"))):null;
  }
  public static ObjectNode clean(JsonNode raw){
    ObjectNode result=JsonNodeFactory.instance.objectNode().put("version",1);
    if(raw==null||!raw.isObject()||raw.has("version")&&(!raw.path("version").isInt()||raw.path("version").intValue()!=1))return result;
    for(String key:List.of("entityId","lastCustomerId","lastProductId","warehouseId","materialId","productionOrderId"))if(id(raw.get(key)))result.put(key,raw.get(key).asText());
    choice(result,raw,"domain","sales","operations");choice(result,raw,"metric","sales");choice(result,raw,"dateBasis","created_at","shipped_at");choice(result,raw,"dimension","customer","product");
    var retry=raw.get("retryRequest");if(retry!=null&&retry.isTextual()&&!retry.asText().isBlank()&&retry.asText().length()<=400&&!SENSITIVE.matcher(retry.asText()).find())result.put("retryRequest",retry.asText());
    choice(result,raw,"question","dates","metric","profit","revenue","dimensions","entity","operation","evidence","retry");choice(result,raw,"analysisOperation","total","entity","ranking","concentration");
    if(raw.path("rankingLimit").isInt()&&raw.path("rankingLimit").intValue()>=1&&raw.path("rankingLimit").intValue()<=20)result.put("rankingLimit",raw.path("rankingLimit").intValue());
    if(query(raw.get("entityQuery"))!=null)result.put("entityQuery",query(raw.get("entityQuery")));
    try{var start=LocalDate.parse(raw.path("startDate").asText());var end=LocalDate.parse(raw.path("endDate").asText());long days=ChronoUnit.DAYS.between(start,end);if(days>0&&days<=366&&start.getYear()>=1001){result.put("startDate",start.toString());result.put("endDate",end.toString());}}catch(java.time.DateTimeException ignored){}
    // Legacy free text is reduced to a date expression only, never forwarded verbatim.
    if(!raw.has("version")&&!result.has("startDate")){
      String original=raw.path("originalMessage").asText("");var dates=Pattern.compile("\\d{4}-(?:\\d{1,2})-(?:\\d{1,2})|\\d{4}年\\d{1,2}月\\d{1,2}日").matcher(original);List<String> matches=new ArrayList<>();while(dates.find()&&matches.size()<3)matches.add(dates.group());
      if(matches.size()>0&&matches.size()<3)result.put("dateHint",String.join("至",matches));
      else{var range=Pattern.compile("最近(?:[1-9]\\d{0,2})天|上个月|最近一个月|今天|昨天|本周|上周|本月|上月|\\d{4}年\\d{1,2}月|\\d{4}-\\d{2}(?!-)").matcher(original);if(range.find())result.put("dateHint",range.group());}
    }
    if(!raw.has("version")){
      String original=raw.path("originalMessage").asText("");
      if(!result.has("dimension")){if(original.contains("客户"))result.put("dimension","customer");else if(original.contains("商品")||original.contains("产品")||original.contains("台式机"))result.put("dimension","product");}
      if(!result.has("analysisOperation")){if(original.contains("排行")||original.contains("排名"))result.put("analysisOperation","ranking");else if(original.contains("集中度"))result.put("analysisOperation","concentration");}
      if(!result.has("entityQuery")&&result.has("dimension")){var name=Pattern.compile("[“\"「]([^”\"」]{1,100})[”\"」]").matcher(original);if(name.find()&&!SENSITIVE.matcher(name.group(1)).find())result.put("entityQuery",name.group(1));}
      if(!result.has("metric")&&!Set.of("profit","revenue","metric").contains(result.path("question").asText())&&(original.contains("销售")||original.contains("订单")))result.put("metric","sales");
      if(result.has("entityId")&&result.has("dimension"))result.put(result.path("dimension").asText().equals("customer")?"lastCustomerId":"lastProductId",result.path("entityId").asText());
    }
    var op=raw.path("operation");if(op.isObject()&&OPERATIONS.contains(op.path("name").asText())){
      result.put("domain","operations");result.set("operation",JsonNodeFactory.instance.objectNode().put("name",op.path("name").asText()));
      ObjectNode args=JsonNodeFactory.instance.objectNode();for(String key:List.of("warehouseId","materialId","productionOrderId")){var n=raw.path("operationsArgs").get(key);if(!id(n))n=result.get(key);if(id(n)){args.put(key,n.asText());result.put(key,n.asText());}}
      result.set("operationsArgs",args);var pending=lookup(raw.get("operationsLookup"));if(pending!=null)result.set("operationsLookup",pending);
      ArrayNode remaining=JsonNodeFactory.instance.arrayNode();if(raw.path("operationsRemaining").isArray()){int count=0;for(var item:raw.path("operationsRemaining")){if(count++>=2)break;var value=lookup(item);if(value!=null)remaining.add(value);}}
      if(!raw.has("version")&&remaining.isEmpty()&&pending!=null&&pending.path("field").asText().equals("warehouseId")&&!args.has("materialId")){var name=Pattern.compile("物料[“\"「]([^”\"」]{1,100})[”\"」]").matcher(op.path("request").asText());if(name.find()&&!SENSITIVE.matcher(name.group(1)).find())remaining.add(JsonNodeFactory.instance.objectNode().put("dimension","material").put("field","materialId").put("query",name.group(1)));}
      if(!remaining.isEmpty())result.set("operationsRemaining",remaining);
      for(String key:List.of("startDate","endDate","dateHint","metric","dimension","entityId","entityQuery","analysisOperation","rankingLimit"))result.remove(key);
    }else if(result.path("domain").asText().equals("sales")){
      for(String key:List.of("warehouseId","materialId","productionOrderId"))result.remove(key);
    }
    ObjectNode confirmed=JsonNodeFactory.instance.objectNode();for(String key:List.of("entityId","warehouseId","materialId","productionOrderId")){var n=raw.path("confirmedOptions").get(key);if(id(n)&&n.equals(result.get(key)))confirmed.put(key,n.asText());}if(!confirmed.isEmpty())result.set("confirmedOptions",confirmed);
    ArrayNode missing=JsonNodeFactory.instance.arrayNode();if(raw.path("missingTools").isArray())for(var n:raw.path("missingTools")){if(missing.size()==2)break;if(n.isTextual()&&Set.of("get_sales_summary","get_sales_trend").contains(n.asText()))missing.add(n.asText());}if(!missing.isEmpty())result.set("missingTools",missing);
    if(result.toString().getBytes(StandardCharsets.UTF_8).length>4096)throw ApiException.invalid("AI结构化上下文超过4096字节");
    return result;
  }
}
