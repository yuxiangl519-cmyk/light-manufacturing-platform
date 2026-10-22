package io.github.lightmanufacturing.wms.application;

import static io.github.lightmanufacturing.wms.dto.StockDtos.*;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.shared.api.PageResponse;
import io.github.lightmanufacturing.wms.domain.StockPosting.Kind;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StockQueryService {
  private final JdbcTemplate jdbc;

  public StockQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Cross-module read projection. No caller needs access to WMS repositories or tables. */
  public List<Document> forSource(String sourceType, long sourceId) {
    if (sourceId <= 0
        || !Set.of("PURCHASE_ORDER", "PRODUCTION_ORDER", "SALES_ORDER").contains(sourceType))
      throw ApiException.invalid("库存来源无效");
    return jdbc.query(
        "select d.* from stock_document d where d.source_type=? and d.source_id=? order by d.id",
        this::mapDocument,
        sourceType,
        sourceId);
  }

  /** Informational only: the posting service always checks again under balance row locks. */
  public Map<Long, java.math.BigDecimal> availableQuantities(
      long warehouseId, Collection<Long> materialIds) {
    if (warehouseId <= 0 || materialIds == null || materialIds.size() > 100)
      throw ApiException.invalid("库存查询维度无效");
    Map<Long, java.math.BigDecimal> result = new LinkedHashMap<>();
    for (long id : new TreeSet<>(materialIds)) {
      if (id <= 0) throw ApiException.invalid("库存物料无效");
      var rows =
          jdbc.queryForList(
              "select quantity from stock_balance where warehouse_id=? and material_id=?",
              java.math.BigDecimal.class,
              warehouseId,
              id);
      result.put(id, rows.isEmpty() ? java.math.BigDecimal.ZERO : rows.get(0));
    }
    return Collections.unmodifiableMap(result);
  }

  public PageResponse<Document> documents(
      String q,
      String status,
      String kind,
      String warehouseId,
      String sourceType,
      String sourceId,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort) {
    return documents(q, status, kind, warehouseId, sourceType, sourceId, from, to, page, size, sort, null);
  }

  public PageResponse<Document> documents(
      String q, String status, String kind, String warehouseId, String sourceType,
      String sourceId, Instant from, Instant to, int page, int size, String sort, String direction) {
    paging(page, size);
    String order = order(sort);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    search(where, args, q, "d.document_no", "d.source_no");
    enumeration(where, args, "d.status", status, Set.of("PENDING", "EXECUTED"));
    enumeration(where, args, "d.direction", direction, Set.of("IN", "OUT"));
    enumeration(
        where,
        args,
        "d.kind",
        kind,
        EnumSet.allOf(Kind.class).stream()
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toSet()));
    source(where, args, "d.", sourceType, sourceId);
    id(where, args, "d.warehouse_id", warehouseId);
    period(where, args, "d.created_at", from, to);
    return query(
        "select d.* from stock_document d",
        " from stock_document d",
        where.toString(),
        args,
        order,
        page,
        size,
        this::mapDocument);
  }

  public Detail document(String id) {
    var docs =
        jdbc.query("select * from stock_document d where d.id=?", this::mapDocument, Policy.id(id));
    if (docs.isEmpty()) throw ApiException.missing();
    var d = docs.get(0);
    var items =
        jdbc.query(
            "select * from stock_document_item where stock_document_id=? order by line_no",
            (r, n) ->
                new Item(
                    r.getString("id"),
                    r.getInt("line_no"),
                    r.getString("source_line_id"),
                    r.getString("material_id"),
                    r.getString("material_code"),
                    r.getString("material_name"),
                    r.getString("unit"),
                    r.getBigDecimal("quantity").toPlainString()),
            Policy.id(id));
    String actor =
        jdbc.queryForObject(
            "select executed_by_name from stock_document where id=?", String.class, Policy.id(id));
    return new Detail(
        d.id(),
        d.version(),
        d.documentNo(),
        d.kind(),
        d.direction(),
        d.status(),
        d.warehouseId(),
        d.warehouseName(),
        d.sourceType(),
        d.sourceId(),
        d.sourceNo(),
        d.createdAt(),
        d.executedAt(),
        actor,
        List.copyOf(items));
  }

  public PageResponse<Balance> balances(
      String q,
      String warehouseId,
      String materialId,
      String type,
      boolean lowStockOnly,
      boolean enabled,
      int page,
      int size,
      String sort) {
    paging(page, size);
    String order =
        switch (sort) {
          case "materialCode,asc" -> "m.code asc,w.id asc,m.id asc";
          case "warehouseId,asc" -> "w.id asc,m.code asc,m.id asc";
          default -> throw ApiException.invalid("库存余额排序不支持");
        };
    String tables =
        enabled
            ? " from warehouse w cross join material m left join stock_balance b on"
                + " b.warehouse_id=w.id and b.material_id=m.id"
            : " from stock_balance b join warehouse w on w.id=b.warehouse_id join material m on"
                + " m.id=b.material_id";
    StringBuilder where =
        new StringBuilder(
            enabled
                ? " where w.enabled=true and m.enabled=true"
                : " where (w.enabled=false or m.enabled=false)");
    List<Object> args = new ArrayList<>();
    search(where, args, q, "m.code", "m.name");
    id(where, args, "w.id", warehouseId);
    id(where, args, "m.id", materialId);
    enumeration(where, args, "m.type", type, Set.of("RAW_MATERIAL", "FINISHED_GOOD"));
    if (lowStockOnly) where.append(" and coalesce(b.quantity,0)<m.min_stock");
    String select =
        "select w.id warehouse_id,w.name warehouse_name,m.id material_id,m.code"
            + " material_code,m.name material_name,m.type,m.unit,coalesce(b.quantity,0)"
            + " quantity,m.min_stock";
    return query(
        select + tables,
        tables,
        where.toString(),
        args,
        order,
        page,
        size,
        (r, n) ->
            new Balance(
                r.getString("warehouse_id"),
                r.getString("warehouse_name"),
                r.getString("material_id"),
                r.getString("material_code"),
                r.getString("material_name"),
                r.getString("type"),
                r.getString("unit"),
                r.getBigDecimal("quantity").toPlainString(),
                r.getBigDecimal("min_stock").toPlainString(),
                r.getBigDecimal("quantity").compareTo(r.getBigDecimal("min_stock")) < 0));
  }

  public PageResponse<Ledger> ledgers(
      String warehouseId,
      String materialId,
      String sourceType,
      String sourceId,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort) {
    paging(page, size);
    String order = order(sort).replace("d.", "l.").replace("created_at", "occurred_at");
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    id(where, args, "l.warehouse_id", warehouseId);
    id(where, args, "l.material_id", materialId);
    source(where, args, "l.", sourceType, sourceId);
    period(where, args, "l.occurred_at", from, to);
    String tables =
        " from stock_ledger l join stock_document d on d.id=l.stock_document_id join"
            + " stock_document_item i on i.id=l.stock_document_item_id";
    return query(
        "select l.*,d.document_no,d.warehouse_name,i.material_code,i.material_name,i.unit" + tables,
        tables,
        where.toString(),
        args,
        order,
        page,
        size,
        (r, n) ->
            new Ledger(
                r.getString("id"),
                r.getString("warehouse_id"),
                r.getString("warehouse_name"),
                r.getString("material_id"),
                r.getString("material_code"),
                r.getString("material_name"),
                r.getString("unit"),
                r.getString("stock_document_id"),
                r.getString("stock_document_item_id"),
                r.getString("document_no"),
                r.getString("kind"),
                r.getString("source_type"),
                r.getString("source_id"),
                r.getString("source_no"),
                r.getString("source_line_id"),
                r.getBigDecimal("change_quantity").toPlainString(),
                r.getBigDecimal("before_quantity").toPlainString(),
                r.getBigDecimal("after_quantity").toPlainString(),
                r.getString("operator_name"),
                instant(r, "occurred_at")));
  }

  private Document mapDocument(ResultSet r, int row) throws SQLException {
    return new Document(
        r.getString("id"),
        r.getLong("version"),
        r.getString("document_no"),
        r.getString("kind"),
        r.getString("direction"),
        r.getString("status"),
        r.getString("warehouse_id"),
        r.getString("warehouse_name"),
        r.getString("source_type"),
        r.getString("source_id"),
        r.getString("source_no"),
        instant(r, "created_at"),
        instant(r, "executed_at"));
  }

  private Instant instant(ResultSet r, String name) throws SQLException {
    var t = r.getTimestamp(name);
    return t == null ? null : t.toInstant();
  }

  private <T> PageResponse<T> query(
      String select,
      String tables,
      String where,
      List<Object> args,
      String order,
      int page,
      int size,
      RowMapper<T> mapper) {
    long total =
        jdbc.queryForObject("select count(*)" + tables + where, Long.class, args.toArray());
    var paged = new ArrayList<>(args);
    paged.add(size);
    paged.add((long) (page - 1) * size);
    return new PageResponse<>(
        jdbc.query(
            select + where + " order by " + order + " limit ? offset ?", mapper, paged.toArray()),
        page,
        size,
        Long.toString(total));
  }

  private void paging(int page, int size) {
    if (page < 1 || size < 1 || size > 100) throw ApiException.invalid("分页范围无效");
  }

  private String order(String sort) {
    return switch (sort) {
      case "createdAt,desc" -> "d.created_at desc,d.id desc";
      case "id,desc" -> "d.id desc";
      default -> throw ApiException.invalid("排序字段不支持");
    };
  }

  private void id(StringBuilder sql, List<Object> args, String column, String value) {
    if (value != null) {
      sql.append(" and ").append(column).append("=?");
      args.add(Policy.id(value));
    }
  }

  private void enumeration(
      StringBuilder sql, List<Object> args, String column, String value, Set<String> allowed) {
    if (value != null) {
      if (!allowed.contains(value)) throw ApiException.invalid("过滤枚举无效");
      sql.append(" and ").append(column).append("=?");
      args.add(value);
    }
  }

  private void source(StringBuilder sql, List<Object> args, String prefix, String type, String id) {
    enumeration(
        sql,
        args,
        prefix + "source_type",
        type,
        Set.of("PURCHASE_ORDER", "PRODUCTION_ORDER", "SALES_ORDER"));
    id(sql, args, prefix + "source_id", id);
  }

  private void period(
      StringBuilder sql, List<Object> args, String column, Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to)) throw ApiException.invalid("时间区间无效");
    if (from != null) {
      sql.append(" and ").append(column).append(">=?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" and ").append(column).append("<?");
      args.add(Timestamp.from(to));
    }
  }

  private void search(StringBuilder sql, List<Object> args, String q, String code, String name) {
    if (q == null) return;
    if (q.length() > 100) throw ApiException.invalid("搜索文字最多100字");
    String pattern = "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    sql.append(" and (")
        .append(code)
        .append(" like ? escape '!' or ")
        .append(name)
        .append(" like ? escape '!')");
    args.add(pattern);
    args.add(pattern);
  }
}
