package io.github.lightmanufacturing.workbench.application;

import static io.github.lightmanufacturing.workbench.dto.WorkbenchDtos.*;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.wms.application.StockQueryService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dedicated read projections: no aggregate repository access and no inventory mutation. */
@Service
@Transactional(readOnly=true)
public class WorkbenchQueryService {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final StockQueryService stock;
  public WorkbenchQueryService(JdbcTemplate jdbc, StockQueryService stock) {
    this.jdbc=jdbc; this.stock=stock;
  }
  public Summary summary(Instant from, Instant to, Set<String> permissions) {
    LocalDate today = LocalDate.now(SHANGHAI);
    Instant start = from == null ? today.withDayOfMonth(1).atStartOfDay(SHANGHAI).toInstant() : from;
    Instant end = to == null ? today.withDayOfMonth(1).plusMonths(1).atStartOfDay(SHANGHAI).toInstant() : to;
    if (!start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(366))>0)
      throw ApiException.invalid("时间区间须为正且不超过366天");
    boolean purchase=permissions.contains("erp:purchase:read");
    boolean sales=permissions.contains("erp:sales:read");
    boolean mes=permissions.contains("mes:production:read");
    Cards cards = new Cards(purchase ? countCreated("purchase_order", start,end) : null,
        sales ? countCreated("sales_order",start,end) : null,
        purchase ? amount("purchase_order","RECEIVED","created_at",start,end) : null,
        sales ? amount("sales_order","SHIPPED","created_at",start,end) : null,
        purchase ? confirmedAmount("purchase_order","RECEIVED",start,end) : null,
        sales ? confirmedAmount("sales_order","SHIPPED",start,end) : null);
    List<Todo> todos=new ArrayList<>();
    if(purchase && permissions.contains("erp:purchase:receive"))
      todos.add(new Todo("PURCHASE_RECEIVE","待收货",countStatus("purchase_order","CONFIRMED"),"/erp/purchases?status=CONFIRMED"));
    if (mes && permissions.contains("mes:production:issue"))
      todos.add(todo("READY_TO_ISSUE","待领料","production_order","/mes/production-orders?status=READY_TO_ISSUE"));
    if (mes && permissions.contains("mes:production:report"))
      todos.add(todo("IN_PRODUCTION","待报工","production_order","/mes/production-orders?status=IN_PRODUCTION"));
    if (mes && permissions.contains("wms:document:read") && permissions.contains("wms:receipt:execute"))
      todos.add(todo("PENDING_INBOUND","待成品入库","production_order","/wms/inbound?status=PENDING"));
    if (sales && permissions.contains("erp:sales:ship"))
      todos.add(todo("CONFIRMED","待发货","sales_order","/erp/sales?status=CONFIRMED"));
    List<Trend> trend=new ArrayList<>();
    for (int i=6;i>=0;i--) {
      LocalDate day=today.minusDays(i);
      Instant a=day.atStartOfDay(SHANGHAI).toInstant(), b=day.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
      trend.add(new Trend(day.toString(),purchase?countCreated("purchase_order",a,b):null,
          sales?countCreated("sales_order",a,b):null));
    }
    Production production=null;
    List<RecentProduction> recentProduction=List.of();
    if (mes) {
      List<StatusCount> states=new ArrayList<>();
      for (String s:List.of("DRAFT","READY_TO_ISSUE","IN_PRODUCTION","PENDING_INBOUND","COMPLETED","CANCELLED"))
        states.add(new StatusCount(s,countStatus("production_order",s)));
      var completed=jdbc.query("select unit,sum(planned_quantity) quantity from production_order where deleted_at is null and status='COMPLETED' and completed_at>=? and completed_at<? group by unit order by unit",(r,n)->new CompletedQuantity(r.getString("unit"),r.getBigDecimal("quantity").toPlainString()),Timestamp.from(start),Timestamp.from(end));
      production=new Production(List.copyOf(states),completed);
      recentProduction=jdbc.query("select * from production_order where deleted_at is null order by created_at desc,id desc limit 4",
          (r,n)->new RecentProduction(r.getString("id"),r.getString("order_no"),r.getString("product_name"),
              r.getBigDecimal("planned_quantity").toPlainString(),r.getString("unit"),r.getString("status"),r.getTimestamp("created_at").toInstant()));
    }
    Inventory inventory=null;
    if (permissions.contains("wms:stock:read")) {
      var balances=stock.balances(null,null,null,null,true,true,1,10,"materialCode,asc");
      inventory=new Inventory(balances.total(),balances.items());
    }
    List<RecentOrder> recent=new ArrayList<>();
    if (purchase) recent.addAll(recent("purchase_order","PURCHASE_ORDER"));
    if (sales) recent.addAll(recent("sales_order","SALES_ORDER"));
    recent.sort(Comparator.comparing(RecentOrder::createdAt).reversed()
        .thenComparing((RecentOrder r)->Long.parseLong(r.id()),Comparator.reverseOrder())
        .thenComparing(RecentOrder::kind));
    return new Summary(new Period(start,end),cards,List.copyOf(todos),List.copyOf(trend),
        production,inventory,recent.stream().limit(5).toList(),recentProduction);
  }
  private String countCreated(String table,Instant from,Instant to) {
    return jdbc.queryForObject("select count(*) from "+table+" where deleted_at is null and status<>'CANCELLED' and created_at>=? and created_at<?",
        Long.class,Timestamp.from(from),Timestamp.from(to)).toString();
  }
  private String confirmedAmount(String table,String completedStatus,Instant from,Instant to){
    return jdbc.queryForObject("select coalesce(sum(total_amount),0) from "+table+" where deleted_at is null and status in('CONFIRMED',?) and created_at>=? and created_at<?",java.math.BigDecimal.class,completedStatus,Timestamp.from(from),Timestamp.from(to)).setScale(2).toPlainString();
  }
  private String amount(String table,String status,String column,Instant from,Instant to) {
    return jdbc.queryForObject("select coalesce(sum(total_amount),0) from "+table+" where deleted_at is null and status=? and "+column+">=? and "+column+"<?",
        java.math.BigDecimal.class,status,Timestamp.from(from),Timestamp.from(to)).setScale(2).toPlainString();
  }
  private String countStatus(String table,String status) {
    return jdbc.queryForObject("select count(*) from "+table+" where deleted_at is null and status=?",Long.class,status).toString();
  }
  private Todo todo(String status,String label,String table,String route) {
    return new Todo(status,label,countStatus(table,status),route);
  }
  private List<RecentOrder> recent(String table,String kind) {
    return jdbc.query("select * from "+table+" where deleted_at is null order by created_at desc,id desc limit 5",
        (r,n)->new RecentOrder(r.getString("id"),r.getString("order_no"),kind,r.getString("partner_name"),
            r.getString("status"),r.getBigDecimal("total_amount").toPlainString(),r.getTimestamp("created_at").toInstant()));
  }
}

