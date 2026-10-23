package io.github.lightmanufacturing.erp.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.master.application.SalesEntityLookupService;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Same eligible order policy as total sales. Amounts are immutable order snapshots. */
@Service @Transactional(readOnly=true)
public class SalesDimensionsService {
  private final JdbcTemplate jdbc; private final SalesEntityLookupService master;
  public SalesDimensionsService(JdbcTemplate jdbc,SalesEntityLookupService master){this.jdbc=jdbc;this.master=master;}
  private static boolean product(String dimension){if(!Set.of("customer","product").contains(Objects.toString(dimension,"")))throw ApiException.invalid("仅支持客户或商品维度");return "product".equals(dimension);}
  private static String base(String dimension,String basis){
    return "with eligible as (select * from sales_order where "+SalesAnalyticsService.ELIGIBLE.replace("created_at",SalesAnalyticsService.basis(basis))+"), base as ("+
      (product(dimension)?"select o.id order_id,o.created_at,o."+SalesAnalyticsService.basis(basis)+" basis_at,i.material_id entity_id,i.material_name entity_name,i.material_code code,i.unit,i.line_amount amount,i.quantity from eligible o join sales_order_item i on i.sales_order_id=o.id":
      "select o.id order_id,o.created_at,o."+SalesAnalyticsService.basis(basis)+" basis_at,o.customer_id entity_id,o.partner_name entity_name,'' code,'' unit,o.total_amount amount,0 quantity from eligible o")+") ";
  }
  private static Timestamp instant(LocalDate date){return Timestamp.from(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());}
  private List<Map<String,Object>> query(String sql,List<?> args,int cap){
    return jdbc.query(c->{var s=c.prepareStatement(sql);s.setQueryTimeout(5);s.setMaxRows(cap);int i=1;for(Object a:args)s.setObject(i++,a);return s;},(r,n)->{
      Map<String,Object> row=new LinkedHashMap<>();var meta=r.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),r.getObject(i));return row;});
  }
  private List<Object> bounds(LocalDate start,LocalDate end){SalesAnalyticsService.validate(start,end);return new ArrayList<>(List.of(instant(start),instant(end)));}
  public Map<String,Object> resolve(LocalDate start,LocalDate end,String dimension,String query){return resolve(start,end,dimension,query,"created_at");}
  public Map<String,Object> resolve(LocalDate start,LocalDate end,String dimension,String query,String basis){
    basis=SalesAnalyticsService.basis(basis);
    product(dimension);var args=bounds(start,end);String pattern=SalesEntityLookupService.literal(query);args.add(pattern);args.add(pattern);
    var candidates=new TreeMap<Long,Map<String,Object>>();
    for(var row:master.search(dimension,query))candidates.put(Policy.id((String)row.get("id")),row);
    var aliases=query(base(dimension,basis)+"select distinct entity_id from base where entity_name like ? escape '!' or code like ? escape '!' order by entity_id limit 11",args,11);
    for(var row:aliases){String id=row.get("entity_id").toString();candidates.put(Policy.id(id),master.find(dimension,id));}
    var result=new LinkedHashMap<String,Object>();result.put("dimension",dimension);result.put("candidates",candidates.values().stream().limit(10).toList());result.put("truncated",candidates.size()>10||aliases.size()==11);return result;
  }
  public Map<String,Object> ranking(LocalDate start,LocalDate end,String dimension,int limit){return ranking(start,end,dimension,limit,"created_at");}
  public Map<String,Object> ranking(LocalDate start,LocalDate end,String dimension,int limit,String basis){
    basis=SalesAnalyticsService.basis(basis);
    product(dimension);if(limit<1||limit>20)throw ApiException.invalid("排行数量须为1至20");var args=bounds(start,end);args.add(limit);
    // Per-unit counts can be summed per product because uq_sales_item_material forbids
    // two rows for a product on one order. Different products' counts must never be added.
    String sql=base(dimension,basis)+"""
      , units as (select entity_id,unit,sum(amount) amount,count(distinct order_id) orders,sum(quantity) quantity from base group by entity_id,unit),
      ranked as (select entity_id,sum(amount) amount,sum(orders) orders from units group by entity_id order by amount desc,entity_id limit ?),
      names as (select entity_id,entity_name,code,row_number() over(partition by entity_id order by created_at desc,order_id desc) rn from base)
      select ranked.entity_id,names.entity_name,names.code,ranked.amount sales_amount,ranked.orders order_count,units.unit,units.quantity
      from ranked join names on names.entity_id=ranked.entity_id and names.rn=1 join units on units.entity_id=ranked.entity_id
      order by ranked.amount desc,ranked.entity_id,units.unit limit 201
      """;
    var raw=query(sql,args,201);if(raw.size()>200)throw ApiException.invalid("历史单位种类超过返回上限");
    Map<String,Map<String,Object>> rows=new LinkedHashMap<>();
    for(var r:raw){String id=r.get("entity_id").toString();var row=rows.computeIfAbsent(id,k->{var v=new LinkedHashMap<String,Object>();v.put("id",id);v.put("name",r.get("entity_name"));v.put("code",r.get("code"));v.put("salesAmount",decimal(r.get("sales_amount"),2));v.put("orderCount",decimal(r.get("order_count"),0));v.put("quantitiesByUnit",new TreeMap<String,String>());return v;});if(product(dimension))quantity(row,r.get("unit").toString(),r.get("quantity"));}
    var result=metadata(start,end,dimension,basis);result.put("limit",limit);result.put("rows",List.copyOf(rows.values()));return result;
  }
  public Map<String,Object> entitySales(LocalDate start,LocalDate end,String dimension,String id){return entitySales(start,end,dimension,id,"created_at");}
  public Map<String,Object> entitySales(LocalDate start,LocalDate end,String dimension,String id,String basis){
    basis=SalesAnalyticsService.basis(basis);
    product(dimension);var days=SalesAnalyticsService.validate(start,end);Policy.id(id);var entity=new LinkedHashMap<>(master.find(dimension,id));
    var labelArgs=bounds(start,end);labelArgs.add(Policy.id(id));
    var labels=query(base(dimension,basis)+"select entity_name,code from base where entity_id=? order by created_at desc,order_id desc limit 1",labelArgs,1);
    entity.put("nameSource",labels.isEmpty()?"masterNoCurrentSales":"latestSalesSnapshot");
    if(!labels.isEmpty()){entity.put("name",labels.get(0).get("entity_name"));if(product(dimension))entity.put("code",labels.get(0).get("code"));}
    var current=daily(start,end,dimension,id,basis);var previous=daily(start.minusDays(days),start,dimension,id,basis);
    var summary=metadata(start,end,dimension,basis);summary.put("period",SalesAnalyticsService.period(start,end));summary.put("previousPeriod",SalesAnalyticsService.period(start.minusDays(days),start));summary.put("current",totals(current));summary.put("previous",totals(previous));
    var trend=metadata(start,end,dimension,basis);trend.put("points",current);
    var result=metadata(start,end,dimension,basis);result.put("entity",entity);result.put("summary",summary);result.put("trend",trend);return result;
  }
  private List<Map<String,Object>> daily(LocalDate start,LocalDate end,String dimension,String id,String basis){
    var args=new ArrayList<Object>(List.of(instant(start),instant(end)));args.add(Policy.id(id));
    // Convert local day boundaries in Java, just like total trend. No DB TZ tables.
    StringJoiner calendar=new StringJoiner(",","[","]");
    for(LocalDate d=start;d.isBefore(end);d=d.plusDays(1))calendar.add("{\"date\":\""+d+"\",\"start\":\""+SalesAnalyticsService.utc(d)+"\",\"end\":\""+SalesAnalyticsService.utc(d.plusDays(1))+"\"}");
    args.add(calendar.toString());
    String sql=base(dimension,basis)+"""
      select calendar.day,b.unit,coalesce(sum(b.amount),0) sales_amount,count(distinct b.order_id) order_count,coalesce(sum(b.quantity),0) quantity
      from json_table(?, '$[*]' columns(day date path '$.date', starts datetime path '$.start', ends datetime path '$.end')) calendar
      left join base b on b.entity_id=? and b.basis_at>=calendar.starts and b.basis_at<calendar.ends
      group by calendar.day,b.unit order by calendar.day,b.unit limit 3661
      """;
    // SQL parameters: eligible bounds, JSON calendar, entity ID.
    Collections.swap(args,2,3);
    var raw=query(sql,args,3661);if(raw.size()>3660)throw ApiException.invalid("趋势单位种类超过上限");
    Map<String,Map<String,Object>> points=new LinkedHashMap<>();
    for(var r:raw){String day=r.get("day").toString();var row=points.computeIfAbsent(day,k->{var v=new LinkedHashMap<String,Object>();v.put("date",day);v.put("salesAmount","0.00");v.put("orderCount","0");v.put("quantitiesByUnit",new TreeMap<String,String>());return v;});add(row,"salesAmount",r.get("sales_amount"),2);add(row,"orderCount",r.get("order_count"),0);if(product(dimension)&&r.get("unit")!=null)quantity(row,r.get("unit").toString(),r.get("quantity"));}
    return List.copyOf(points.values());
  }
  private static Map<String,Object> totals(List<Map<String,Object>> points){Map<String,Object> row=new LinkedHashMap<>();row.put("salesAmount","0.00");row.put("orderCount","0");row.put("quantitiesByUnit",new TreeMap<String,String>());for(var p:points){add(row,"salesAmount",p.get("salesAmount"),2);add(row,"orderCount",p.get("orderCount"),0);@SuppressWarnings("unchecked")var units=(Map<String,String>)p.get("quantitiesByUnit");units.forEach((u,q)->quantity(row,u,q));}return row;}
  private static void add(Map<String,Object> row,String key,Object value,int scale){row.put(key,new BigDecimal(row.get(key).toString()).add(new BigDecimal(value.toString())).setScale(scale).toPlainString());}
  @SuppressWarnings("unchecked") private static void quantity(Map<String,Object> row,String unit,Object value){var units=(Map<String,String>)row.get("quantitiesByUnit");units.merge(unit,decimal(value,0),(a,b)->new BigDecimal(a).add(new BigDecimal(b)).toPlainString());if(units.size()>10)throw ApiException.invalid("历史单位种类超过10种，不能合并数量");}
  private static String decimal(Object value,int scale){return new BigDecimal(value.toString()).setScale(scale).toPlainString();}
  private static Map<String,Object> metadata(LocalDate start,LocalDate end,String dimension,String basis){var result=SalesAnalyticsService.metadata(start,end,basis);result.put("dimension",dimension);if(product(dimension)){result.put("definition","按销售单创建时间筛选当前已发货销售明细的人民币含税金额（line_amount），不是收入或利润；数量按快照单位分组");result.put("source","sales_order.status=SHIPPED;created_at;deleted_at IS NULL;sales_order_item.line_amount;material_id;unit");}if(product(dimension)&&"shipped_at".equals(basis)){result.put("definition","按实际发货时间筛选当前已发货销售明细的人民币含税发货金额，不是收入或利润；数量按快照单位分组");result.put("source",result.get("source").toString().replace("created_at","shipped_at"));}return result;}
}
