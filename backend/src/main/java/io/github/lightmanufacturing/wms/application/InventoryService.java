package io.github.lightmanufacturing.wms.application;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.master.application.MasterReferenceService;
import io.github.lightmanufacturing.system.application.OperatorDirectory;
import io.github.lightmanufacturing.wms.domain.StockPosting;
import io.github.lightmanufacturing.wms.domain.StockPosting.*;
import io.github.lightmanufacturing.wms.domain.StockQuantity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** All writes join the caller's source transaction. Never expose this as a CRUD HTTP controller. */
@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class InventoryService {
  private final JdbcTemplate jdbc;
  private final MasterReferenceService master;
  private final OperatorDirectory operators;

  public InventoryService(
      JdbcTemplate jdbc, MasterReferenceService master, OperatorDirectory operators) {
    this.jdbc = jdbc;
    this.master = master;
    this.operators = operators;
  }

  public Result post(StockPosting command) {
    var plan = normalize(command);
    var claimed = claim(plan);
    return executeLocked(claimed, command.operatorId(), claimed.version(), !claimed.fresh());
  }

  public Result prepareProductionReceipt(StockPosting command) {
    if (command == null || command.kind() != Kind.PRODUCTION_RECEIPT)
      throw ApiException.invalid("只允许准备生产成品入库单");
    var claimed = claim(normalize(command));
    return result(claimed, !claimed.fresh());
  }

  public Result execute(long documentId, long expectedVersion, long operatorId) {
    if (documentId <= 0 || expectedVersion < 0 || operatorId <= 0)
      throw ApiException.invalid("库存单标识或版本无效");
    // Read immutable material IDs first. Global order: master materials, document, balances.
    var ids =
        jdbc.queryForList(
            "select material_id from stock_document_item where stock_document_id=? order by"
                + " material_id",
            Long.class,
            documentId);
    if (ids.isEmpty()) throw ApiException.missing();
    master.markMaterialsReferenced(ids);
    var docs =
        jdbc.query(
            "select id,document_no,status,version,kind,warehouse_id,source_id,source_no from"
                + " stock_document where id=? for update",
            (rs, n) ->
                new Document(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getLong(4),
                    Kind.valueOf(rs.getString(5)),
                    rs.getLong(6),
                    rs.getLong(7),
                    rs.getString(8),
                    false),
            documentId);
    if (docs.isEmpty()) throw ApiException.missing();
    return executeLocked(docs.get(0), operatorId, expectedVersion, true);
  }

  private StockPosting normalize(StockPosting c) {
    if (c == null
        || c.kind() == null
        || c.sourceId() <= 0
        || c.warehouseId() <= 0
        || c.operatorId() <= 0) throw ApiException.invalid("库存记账来源、仓库和操作人不能为空");
    String no = Policy.text(c.sourceNo(), 64, "来源单号");
    if (c.lines() == null || c.lines().isEmpty() || c.lines().size() > 100)
      throw ApiException.invalid("库存明细须为1至100行");
    Set<Long> materials = new HashSet<>(), sources = new HashSet<>();
    List<Line> lines = new ArrayList<>();
    for (var line : c.lines()) {
      if (line == null
          || line.materialId() <= 0
          || line.sourceLineId() <= 0
          || !materials.add(line.materialId())
          || !sources.add(line.sourceLineId())) throw ApiException.invalid("库存明细物料和来源明细不能重复或无效");
      lines.add(
          new Line(
              line.sourceLineId(), line.materialId(), StockQuantity.positive(line.quantity())));
    }
    lines.sort(Comparator.comparingLong(Line::materialId));
    return new StockPosting(c.kind(), c.sourceId(), no, c.warehouseId(), c.operatorId(), lines);
  }

  private Document claim(StockPosting c) {
    var ids = c.lines().stream().map(Line::materialId).toList();
    // This also freezes material code/unit before snapshots and orders all first-use locks.
    master.markMaterialsReferenced(ids);
    var materials = master.requireMaterials(ids, false);
    var warehouse = master.requireWarehouse(c.warehouseId(), false);
    for (var line : c.lines())
      if (!c.kind().materialType().equals(materials.get(line.materialId()).type()))
        throw ApiException.invalid("库存业务类型与原料/成品类型不匹配");
    actorName(c.operatorId());
    String fingerprint = fingerprint(c), no = "ST-" + UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " stock_document(document_no,kind,direction,status,source_type,source_id,source_no,warehouse_id,warehouse_name,fingerprint,created_at,updated_at,created_by,updated_by,version)"
            + " values(?,?,?,'PENDING',?,?,?,?,?,?,"
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ","
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",?,?,0) on"
            + " duplicate key update id=id",
        no,
        c.kind().name(),
        c.kind().direction(),
        c.kind().sourceType(),
        c.sourceId(),
        c.sourceNo(),
        c.warehouseId(),
        warehouse.name(),
        fingerprint,
        c.operatorId(),
        c.operatorId());
    var doc =
        jdbc.queryForObject(
            "select id,document_no,status,version,kind,warehouse_id,source_id,source_no,fingerprint"
                + " from stock_document where kind=? and source_type=? and source_id=? for update",
            (rs, n) -> {
              if (!fingerprint.equals(rs.getString(9)))
                throw ApiException.conflict("SOURCE_CONFLICT", "相同来源的库存记账内容发生变化");
              return new Document(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getLong(4),
                  Kind.valueOf(rs.getString(5)),
                  rs.getLong(6),
                  rs.getLong(7),
                  rs.getString(8),
                  no.equals(rs.getString(2)));
            },
            c.kind().name(),
            c.kind().sourceType(),
            c.sourceId());
    if (doc.fresh()) {
      int lineNo = 0;
      for (var line : c.lines()) {
        var m = materials.get(line.materialId());
        jdbc.update(
            "insert into"
                + " stock_document_item(stock_document_id,line_no,source_line_id,material_id,material_code,material_name,unit,quantity)"
                + " values(?,?,?,?,?,?,?,?)",
            doc.id(),
            ++lineNo,
            line.sourceLineId(),
            m.id(),
            m.code(),
            m.name(),
            m.unit(),
            line.quantity());
      }
    }
    return doc;
  }

  private Result executeLocked(Document doc, long actor, long expectedVersion, boolean replay) {
    String name = actorName(actor);
    if (doc.status().equals("EXECUTED")) return result(doc, true);
    if (doc.version() != expectedVersion)
      throw ApiException.conflict("VERSION_CONFLICT", "库存单版本已变更");
    var lines =
        jdbc.query(
            "select id,source_line_id,material_id,quantity from stock_document_item where"
                + " stock_document_id=? order by material_id",
            (rs, n) ->
                new StoredLine(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)),
            doc.id());
    if (lines.isEmpty()) throw ApiException.conflict("INVALID_STATE", "库存单没有明细");
    List<Movement> movements = new ArrayList<>();
    for (var line : lines) {
      // Unique key arbitrates two transactions creating the first balance. Upsert holds row X lock.
      jdbc.update(
          "insert into stock_balance(warehouse_id,material_id,quantity,version,updated_at)"
              + " values(?,?,0,0,"
              + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
              + ") on duplicate key update quantity=quantity",
          doc.warehouseId(),
          line.materialId());
      var balance =
          jdbc.queryForObject(
              "select id,quantity from stock_balance where warehouse_id=? and material_id=? for"
                  + " update",
              (rs, n) -> new Balance(rs.getLong(1), rs.getBigDecimal(2)),
              doc.warehouseId(),
              line.materialId());
      BigDecimal delta =
          doc.kind().direction().equals("IN") ? line.quantity() : line.quantity().negate();
      BigDecimal after = balance.quantity().add(delta);
      if (after.signum() < 0) throw ApiException.conflict("INSUFFICIENT_STOCK", "库存不足，整单未记账");
      movements.add(new Movement(line, balance, delta, StockQuantity.balance(after)));
    }
    for (var move : movements) {
      jdbc.update(
          "update stock_balance set quantity=?,version=version+1,updated_at="
              + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
              + " where"
              + " id=?",
          move.after(),
          move.balance().id());
      jdbc.update(
          "insert into"
              + " stock_ledger(balance_id,warehouse_id,material_id,stock_document_id,stock_document_item_id,source_type,source_id,source_no,source_line_id,kind,change_quantity,before_quantity,after_quantity,operator_id,operator_name,occurred_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
              + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
              + ")",
          move.balance().id(),
          doc.warehouseId(),
          move.line().materialId(),
          doc.id(),
          move.line().id(),
          doc.kind().sourceType(),
          doc.sourceId(),
          doc.sourceNo(),
          move.line().sourceLineId(),
          doc.kind().name(),
          move.delta(),
          move.balance().quantity(),
          move.after(),
          actor,
          name);
    }
    jdbc.update(
        "update stock_document set"
            + " status='EXECUTED',executed_by=?,executed_by_name=?,executed_at="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",updated_at="
            + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
            + ",updated_by=?,version=version+1"
            + " where id=?",
        actor,
        name,
        actor,
        doc.id());
    return new Result(doc.id(), doc.no(), "EXECUTED", doc.version() + 1, replay);
  }

  private String actorName(long actor) {
    return operators.displayName(actor);
  }

  private Result result(Document d, boolean replay) {
    return new Result(d.id(), d.no(), d.status(), d.version(), replay);
  }

  private String fingerprint(StockPosting c) {
    StringBuilder text =
        new StringBuilder(c.kind().name())
            .append('|')
            .append(c.sourceId())
            .append('|')
            .append(c.sourceNo().length())
            .append(':')
            .append(c.sourceNo())
            .append('|')
            .append(c.warehouseId());
    for (var l : c.lines())
      text.append('|')
          .append(l.sourceLineId())
          .append(':')
          .append(l.materialId())
          .append(':')
          .append(l.quantity().toPlainString());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Document(
      long id,
      String no,
      String status,
      long version,
      Kind kind,
      long warehouseId,
      long sourceId,
      String sourceNo,
      boolean fresh) {}

  private record StoredLine(long id, long sourceLineId, long materialId, BigDecimal quantity) {}

  private record Balance(long id, BigDecimal quantity) {}

  private record Movement(StoredLine line, Balance balance, BigDecimal delta, BigDecimal after) {}
}
