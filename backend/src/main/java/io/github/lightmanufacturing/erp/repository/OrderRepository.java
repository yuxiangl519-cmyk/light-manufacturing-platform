package io.github.lightmanufacturing.erp.repository;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.erp.domain.*;
import io.github.lightmanufacturing.shared.api.PageResponse;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;

/** SQL identifiers are selected exclusively from OrderType, never from request strings. */
@Repository
public class OrderRepository {
  private final JdbcTemplate jdbc;

  public OrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public OrderHeader get(OrderType type, long id, boolean lock) {
    var rows =
        jdbc.query(
            "select * from "
                + type.table
                + " where id=? and deleted_at is null"
                + (lock ? " for update" : ""),
            (rs, n) -> header(type, rs),
            id);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.get(0);
  }

  private OrderHeader header(OrderType t, ResultSet rs) throws SQLException {
    return new OrderHeader(
        rs.getLong("id"),
        rs.getLong("version"),
        rs.getString("order_no"),
        rs.getLong(t.partnerColumn),
        rs.getString("partner_name"),
        rs.getLong("warehouse_id"),
        rs.getString("warehouse_name"),
        rs.getString("status"),
        rs.getBigDecimal("total_amount"),
        rs.getString("remark"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getString("created_by_name"),
        instant(rs, "confirmed_at"),
        instant(rs, t.doneColumn),
        instant(rs, "cancelled_at"));
  }

  private Instant instant(ResultSet rs, String c) throws SQLException {
    Timestamp v = rs.getTimestamp(c);
    return v == null ? null : v.toInstant();
  }

  public List<OrderLine> lines(OrderType t, long id) {
    return jdbc.query(
        "select * from " + t.itemTable + " where " + t.table + "_id=? order by line_no",
        (rs, n) ->
            new OrderLine(
                rs.getLong("id"),
                rs.getInt("line_no"),
                rs.getLong("material_id"),
                rs.getString("material_code"),
                rs.getString("material_name"),
                rs.getString("unit"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_amount")),
        id);
  }

  public long create(
      OrderType t,
      String no,
      long partner,
      String partnerName,
      long warehouse,
      String warehouseName,
      String remark,
      BigDecimal total,
      long actor,
      String actorName,
      List<OrderLine> lines) {
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement s =
              c.prepareStatement(
                  "insert into "
                      + t.table
                      + "(order_no,"
                      + t.partnerColumn
                      + ",partner_name,warehouse_id,warehouse_name,remark,total_amount,created_by,updated_by,created_by_name,created_at,updated_at)"
                      + " values(?,?,?,?,?,?,?,?,?,?,"
                      + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
                      + ","
                      + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
                      + ")",
                  Statement.RETURN_GENERATED_KEYS);
          Object[] values = {
            no,
            partner,
            partnerName,
            warehouse,
            warehouseName,
            remark,
            total,
            actor,
            actor,
            actorName
          };
          for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
          return s;
        },
        key);
    long id = Objects.requireNonNull(key.getKey()).longValue();
    insertLines(t, id, lines);
    return id;
  }

  public void updateDraft(
      OrderType t,
      long id,
      long partner,
      String partnerName,
      long warehouse,
      String warehouseName,
      String remark,
      BigDecimal total,
      long actor,
      List<OrderLine> lines) {
    jdbc.update(
        "update "
            + t.table
            + " set "
            + t.partnerColumn
            + "=?,partner_name=?,warehouse_id=?,warehouse_name=?,remark=?,total_amount=?,updated_by=?,updated_at="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",version=version+1"
            + " where id=?",
        partner,
        partnerName,
        warehouse,
        warehouseName,
        remark,
        total,
        actor,
        id);
    jdbc.update("delete from " + t.itemTable + " where " + t.table + "_id=?", id);
    insertLines(t, id, lines);
  }

  private void insertLines(OrderType t, long id, List<OrderLine> lines) {
    for (var l : lines)
      jdbc.update(
          "insert into "
              + t.itemTable
              + "("
              + t.table
              + "_id,line_no,material_id,material_code,material_name,unit,quantity,unit_price,line_amount)"
              + " values(?,?,?,?,?,?,?,?,?)",
          id,
          l.lineNo(),
          l.materialId(),
          l.materialCode(),
          l.materialName(),
          l.unit(),
          l.quantity(),
          l.unitPrice(),
          l.lineAmount());
  }

  public void confirmSnapshot(
      OrderType t, long id, String partnerName, String warehouseName, List<OrderLine> lines) {
    jdbc.update(
        "update " + t.table + " set partner_name=?,warehouse_name=? where id=?",
        partnerName,
        warehouseName,
        id);
    for (var l : lines)
      jdbc.update(
          "update " + t.itemTable + " set material_code=?,material_name=?,unit=? where id=?",
          l.materialCode(),
          l.materialName(),
          l.unit(),
          l.id());
  }

  public void transition(OrderType t, long id, String status, long actor) {
    String date =
        switch (status) {
          case "CONFIRMED" -> "confirmed_at";
          case "CANCELLED" -> "cancelled_at";
          default -> t.doneColumn;
        };
    jdbc.update(
        "update "
            + t.table
            + " set status=?,"
            + date
            + "="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",updated_at="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",updated_by=?,version=version+1 where"
            + " id=?",
        status,
        actor,
        id);
  }

  public void delete(OrderType t, long id, long actor) {
    jdbc.update(
        "update "
            + t.table
            + " set deleted_at="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",updated_at="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",updated_by=?,version=version+1"
            + " where id=?",
        actor,
        id);
  }

  public PageResponse<OrderHeader> page(
      OrderType t,
      String q,
      String status,
      String partner,
      String warehouse,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort) {
    if (page < 1 || size < 1 || size > 100 || !Set.of("createdAt,desc", "id,desc").contains(sort))
      throw ApiException.invalid("分页或排序无效");
    if (q != null && q.length() > 100) throw ApiException.invalid("查询词过长");
    if (status != null && !Set.of("DRAFT", "CONFIRMED", t.doneStatus, "CANCELLED").contains(status))
      throw ApiException.invalid("状态无效");
    if (from != null && to != null && !from.isBefore(to)) throw ApiException.invalid("时间范围无效");
    StringBuilder where = new StringBuilder(" where deleted_at is null");
    List<Object> args = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      where.append(" and locate(?,order_no)>0");
      args.add(q.trim());
    }
    if (status != null) {
      where.append(" and status=?");
      args.add(status);
    }
    if (partner != null) {
      where.append(" and ").append(t.partnerColumn).append("=?");
      args.add(Policy.id(partner));
    }
    if (warehouse != null) {
      where.append(" and warehouse_id=?");
      args.add(Policy.id(warehouse));
    }
    if (from != null) {
      where.append(" and created_at>=?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      where.append(" and created_at<?");
      args.add(Timestamp.from(to));
    }
    long total =
        jdbc.queryForObject("select count(*) from " + t.table + where, Long.class, args.toArray());
    String order = sort.equals("id,desc") ? "id desc" : "created_at desc,id desc";
    args.add(size);
    args.add(((long) page - 1) * size);
    var items =
        jdbc.query(
            "select * from " + t.table + where + " order by " + order + " limit ? offset ?",
            (rs, n) -> header(t, rs),
            args.toArray());
    return new PageResponse<>(items, page, size, Long.toString(total));
  }
}
