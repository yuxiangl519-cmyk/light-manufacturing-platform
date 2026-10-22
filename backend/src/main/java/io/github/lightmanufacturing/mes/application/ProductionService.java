package io.github.lightmanufacturing.mes.application;

import static io.github.lightmanufacturing.mes.dto.ProductionDtos.*;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.master.application.MasterReferenceService;
import io.github.lightmanufacturing.mes.domain.*;
import io.github.lightmanufacturing.mes.repository.*;
import io.github.lightmanufacturing.shared.api.*;
import io.github.lightmanufacturing.shared.api.BusinessDtos.ActionResponse;
import io.github.lightmanufacturing.shared.application.CommandService;
import io.github.lightmanufacturing.shared.security.BusinessActor;
import io.github.lightmanufacturing.system.application.*;
import io.github.lightmanufacturing.wms.application.*;
import io.github.lightmanufacturing.wms.domain.StockPosting;
import io.github.lightmanufacturing.wms.domain.StockPosting.Kind;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductionService {
  private final ProductionOrderRepository orders;
  private final BomRepository boms;
  private final ProductionSnapshotRepository snapshots;
  private final ProductionReportRepository reports;
  private final ProductionConfirmationService confirmation;
  private final MasterReferenceService master;
  private final InventoryService inventory;
  private final StockQueryService stock;
  private final CommandService commands;
  private final AuditService audit;
  private final OperatorDirectory operators;
  private final EntityManager em;

  public ProductionService(
      ProductionOrderRepository orders,
      BomRepository boms,
      ProductionSnapshotRepository snapshots,
      ProductionReportRepository reports,
      ProductionConfirmationService confirmation,
      MasterReferenceService master,
      InventoryService inventory,
      StockQueryService stock,
      CommandService commands,
      AuditService audit,
      OperatorDirectory operators,
      EntityManager em) {
    this.orders = orders;
    this.boms = boms;
    this.snapshots = snapshots;
    this.reports = reports;
    this.confirmation = confirmation;
    this.master = master;
    this.inventory = inventory;
    this.stock = stock;
    this.commands = commands;
    this.audit = audit;
    this.operators = operators;
    this.em = em;
  }

  public Detail detail(long id) {
    return dto(existing(id, false));
  }

  public PageResponse<Summary> page(
      String q,
      String status,
      String productMaterialId,
      String warehouseId,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort) {
    if (page < 1 || size < 1 || size > 100 || q != null && q.length() > 100)
      throw ApiException.invalid("查询参数超出范围");
    if (status != null
        && !Set.of(
                "DRAFT",
                "READY_TO_ISSUE",
                "IN_PRODUCTION",
                "PENDING_INBOUND",
                "COMPLETED",
                "CANCELLED")
            .contains(status)) throw ApiException.invalid("工单状态无效");
    if (from != null && to != null && !from.isBefore(to)) throw ApiException.invalid("日期区间无效");
    if (!Set.of("createdAt,desc", "id,desc").contains(sort)) throw ApiException.invalid("排序字段不支持");
    Long product = productMaterialId == null ? null : Policy.id(productMaterialId),
        warehouse = warehouseId == null ? null : Policy.id(warehouseId);
    Specification<ProductionOrder> spec =
        (r, x, b) -> {
          var filter = b.isNull(r.get("deletedAt"));
          if (q != null && !q.isBlank()) {
            String text =
                "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filter =
                b.and(
                    filter,
                    b.or(
                        b.like(r.get("orderNo"), text, '!'),
                        b.like(r.get("productName"), text, '!')));
          }
          if (status != null) filter = b.and(filter, b.equal(r.get("status"), status));
          if (product != null) filter = b.and(filter, b.equal(r.get("productMaterialId"), product));
          if (warehouse != null)
            filter =
                b.and(
                    filter,
                    b.or(
                        b.equal(r.get("issueWarehouseId"), warehouse),
                        b.equal(r.get("receiptWarehouseId"), warehouse)));
          if (from != null)
            filter = b.and(filter, b.greaterThanOrEqualTo(r.get("createdAt"), from));
          if (to != null) filter = b.and(filter, b.lessThan(r.get("createdAt"), to));
          return filter;
        };
    var result =
        orders.findAll(
            spec,
            PageRequest.of(
                page - 1,
                size,
                sort.startsWith("createdAt")
                    ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                    : Sort.by(Sort.Order.desc("id"))));
    return new PageResponse<>(
        result.getContent().stream().map(this::summary).toList(),
        page,
        size,
        Long.toString(result.getTotalElements()));
  }

  @Transactional(rollbackFor = Exception.class)
  public Detail create(Create body) {
    BusinessActor.requireId();
    var order = new ProductionOrder();
    order.setOrderNo(
        "MO-"
            + io.github.lightmanufacturing.shared.time.BusinessTime.today(
                    ZoneId.of("Asia/Shanghai"))
                .toString()
                .replace("-", "")
            + "-"
            + UUID.randomUUID());
    order.setStatus("DRAFT");
    apply(
        order,
        new Draft(
            body.productMaterialId(),
            body.bomId(),
            body.plannedQuantity(),
            body.issueWarehouseId(),
            body.receiptWarehouseId(),
            body.remark()));
    orders.saveAndFlush(order);
    audit.success("CREATE", "PRODUCTION_ORDER", order.getId());
    return dto(order);
  }

  @Transactional(rollbackFor = Exception.class)
  public Detail update(long id, Update body) {
    BusinessActor.requireId();
    var order = existing(id, true);
    draft(order, body.version());
    apply(
        order,
        new Draft(
            body.productMaterialId(),
            body.bomId(),
            body.plannedQuantity(),
            body.issueWarehouseId(),
            body.receiptWarehouseId(),
            body.remark()));
    orders.flush();
    audit.success("UPDATE", "PRODUCTION_ORDER", id);
    return dto(order);
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(long id, long version) {
    BusinessActor.requireId();
    var order = existing(id, true);
    draft(order, version);
    order.setDeletedAt(io.github.lightmanufacturing.shared.time.BusinessTime.now());
    orders.flush();
    audit.success("DELETE", "PRODUCTION_ORDER", id);
  }

  @Transactional(rollbackFor = Exception.class)
  public ActionResponse<Detail> cancel(long id, Long version) {
    BusinessActor.requireId();
    var o = existing(id, true);
    var prior = commands.find("PRODUCTION_ORDER", id, "CANCEL");
    if (prior.isPresent()) return response(o, prior.get(), true);
    draft(o, version);
    o.setStatus("CANCELLED");
    o.setCancelledAt(io.github.lightmanufacturing.shared.time.BusinessTime.now());
    orders.flush();
    return complete(o, "CANCEL");
  }

  @Transactional(rollbackFor = Exception.class)
  public ActionResponse<Detail> confirm(long id, Long version) {
    BusinessActor.requireId();
    var o = existing(id, true);
    var prior = commands.find("PRODUCTION_ORDER", id, "CONFIRM");
    if (prior.isPresent()) return response(o, prior.get(), true);
    draft(o, version);
    confirmation.confirm(id, version);
    String operation = commands.record("PRODUCTION_ORDER", id, "CONFIRM");
    return response(o, operation, false);
  }

  @Transactional(rollbackFor = Exception.class)
  public ActionResponse<Detail> issue(long id, Long version) {
    long actor = BusinessActor.requireId();
    var o = existing(id, true);
    var prior = commands.find("PRODUCTION_ORDER", id, "ISSUE");
    if (prior.isPresent()) return response(o, prior.get(), true);
    state(o, version, "READY_TO_ISSUE");
    var lines = snapshots.findByProductionOrderIdOrderByLineNo(id);
    if (lines.isEmpty()) throw ApiException.conflict("INVALID_STATE", "工单没有确认用料快照");
    inventory.post(
        new StockPosting(
            Kind.PRODUCTION_ISSUE,
            id,
            o.getOrderNo(),
            o.getIssueWarehouseId(),
            actor,
            lines.stream()
                .map(
                    s ->
                        new StockPosting.Line(
                            s.getId(), s.getMaterialId(), s.getRequiredQuantity()))
                .toList()));
    o.setStatus("IN_PRODUCTION");
    o.setIssuedAt(io.github.lightmanufacturing.shared.time.BusinessTime.now());
    orders.flush();
    return complete(o, "ISSUE");
  }

  @Transactional(rollbackFor = Exception.class)
  public ActionResponse<Detail> report(long id, Long version) {
    long actor = BusinessActor.requireId();
    var o = existing(id, true);
    var prior = commands.find("PRODUCTION_ORDER", id, "REPORT");
    if (prior.isPresent()) return response(o, prior.get(), true);
    state(o, version, "IN_PRODUCTION");
    var report =
        new ProductionReport(
            id,
            o.getPlannedQuantity(),
            actor,
            operators.displayName(actor),
            io.github.lightmanufacturing.shared.time.BusinessTime.now());
    em.persist(report);
    em.flush();
    inventory.prepareProductionReceipt(
        new StockPosting(
            Kind.PRODUCTION_RECEIPT,
            id,
            o.getOrderNo(),
            o.getReceiptWarehouseId(),
            actor,
            List.of(
                new StockPosting.Line(
                    report.getId(), o.getProductMaterialId(), o.getPlannedQuantity()))));
    o.setStatus("PENDING_INBOUND");
    o.setReportedAt(report.getReportedAt());
    orders.flush();
    return complete(o, "REPORT");
  }

  @Transactional(rollbackFor = Exception.class)
  public ReceiptResult executeReceipt(long documentId, Long version) {
    long actor = BusinessActor.requireId();
    // Read immutable source first, then lock the order before the stock service locks its document.
    var document = stock.document(Long.toString(documentId));
    if (!document.kind().equals("PRODUCTION_RECEIPT")
        || !document.sourceType().equals("PRODUCTION_ORDER"))
      throw ApiException.conflict("INVALID_STATE", "仅允许执行生产成品入库");
    long orderId = Policy.id(document.sourceId());
    var o = existing(orderId, true);
    var prior = commands.find("STOCK_DOCUMENT", documentId, "EXECUTE");
    if (prior.isPresent()) return receiptResult(o, documentId, prior.get(), true);
    if (version == null || version < 0 || version > 9007199254740991L)
      throw ApiException.invalid("动作须提供非负安全整数version");
    if (!o.getStatus().equals("PENDING_INBOUND") || !document.status().equals("PENDING"))
      throw ApiException.conflict("INVALID_STATE", "工单或入库单不在待入库状态");
    if (document.version() != version) throw ApiException.conflict("VERSION_CONFLICT", "记录已变更");
    var report =
        reports
            .findByProductionOrderId(orderId)
            .orElseThrow(() -> ApiException.conflict("INVALID_STATE", "工单缺少报工记录"));
    if (!document.warehouseId().equals(o.getReceiptWarehouseId().toString())
        || document.items().size() != 1) throw ApiException.conflict("SOURCE_CONFLICT", "入库单来源不一致");
    var line = document.items().get(0);
    if (!line.materialId().equals(o.getProductMaterialId().toString())
        || !line.sourceLineId().equals(report.getId().toString())
        || new BigDecimal(line.quantity()).compareTo(o.getPlannedQuantity()) != 0)
      throw ApiException.conflict("SOURCE_CONFLICT", "入库明细与工单不一致");
    inventory.execute(documentId, version, actor);
    o.setStatus("COMPLETED");
    o.setCompletedAt(io.github.lightmanufacturing.shared.time.BusinessTime.now());
    orders.flush();
    String operation = commands.record("STOCK_DOCUMENT", documentId, "EXECUTE");
    audit.success("EXECUTE", "STOCK_DOCUMENT", documentId);
    return receiptResult(o, documentId, operation, false);
  }

  public Requirements requirements(long id) {
    var o = existing(id, false);
    if (o.getStatus().equals("DRAFT") || o.getStatus().equals("CANCELLED"))
      throw ApiException.conflict("INVALID_STATE", "请先确认工单以保存用料快照");
    var lines = snapshots.findByProductionOrderIdOrderByLineNo(id);
    var available =
        stock.availableQuantities(
            o.getIssueWarehouseId(),
            lines.stream().map(ProductionMaterialSnapshot::getMaterialId).toList());
    var items =
        lines.stream()
            .map(
                s -> {
                  BigDecimal balance = available.get(s.getMaterialId()),
                      shortage = s.getRequiredQuantity().subtract(balance).max(BigDecimal.ZERO);
                  return new Requirement(
                      s.getLineNo(),
                      s.getMaterialId().toString(),
                      s.getMaterialCode(),
                      s.getMaterialName(),
                      s.getUnit(),
                      s.getRequiredQuantity().toPlainString(),
                      balance.toPlainString(),
                      shortage.toPlainString(),
                      shortage.signum() > 0);
                })
            .toList();
    return new Requirements(
        Long.toString(id), o.getStatus(), items.stream().anyMatch(Requirement::shortage), items);
  }

  private void apply(ProductionOrder o, Draft d) {
    long product = Policy.id(d.product()),
        bomId = Policy.id(d.bom()),
        issue = Policy.id(d.issueWarehouse()),
        receipt = Policy.id(d.receiptWarehouse());
    var quantity = Quantities.positive(d.quantity());
    var bom = boms.lockById(bomId).orElseThrow(ApiException::missing);
    if (!bom.getEnabled()) throw ApiException.conflict("DISABLED_REFERENCE", "BOM已停用");
    if (bom.getProductMaterialId() != product) throw ApiException.invalid("BOM与工单成品不一致");
    master.markMaterialsReferenced(List.of(product));
    var material = master.requireMaterials(List.of(product), true).get(product);
    if (!material.type().equals("FINISHED_GOOD")) throw ApiException.invalid("工单只能生产成品");
    Map<Long, MasterReferenceService.WarehouseRef> warehouses = new HashMap<>();
    for (long id : new TreeSet<>(List.of(issue, receipt)))
      warehouses.put(id, master.requireWarehouse(id, true));
    String remark = d.remark() == null ? "" : d.remark().trim();
    if (remark.length() > 500) throw ApiException.invalid("备注最多500字");
    o.setProductMaterialId(product);
    o.setProductCode(material.code());
    o.setProductName(material.name());
    o.setUnit(material.unit());
    o.setPlannedQuantity(quantity);
    o.setBomId(bomId);
    o.setBomCode(bom.getCode());
    o.setBomRevision(null);
    o.setIssueWarehouseId(issue);
    o.setIssueWarehouseName(warehouses.get(issue).name());
    o.setReceiptWarehouseId(receipt);
    o.setReceiptWarehouseName(warehouses.get(receipt).name());
    o.setRemark(remark);
  }

  private ProductionOrder existing(long id, boolean lock) {
    if (id <= 0) throw ApiException.invalid("工单ID无效");
    var o = (lock ? orders.lockById(id) : orders.findById(id)).orElseThrow(ApiException::missing);
    if (o.getDeletedAt() != null) throw ApiException.missing();
    return o;
  }

  private void draft(ProductionOrder o, Long version) {
    state(o, version, "DRAFT");
  }

  private void state(ProductionOrder o, Long version, String expected) {
    if (version == null || version < 0 || version > 9007199254740991L)
      throw ApiException.invalid("动作须提供非负安全整数version");
    if (!o.getStatus().equals(expected))
      throw ApiException.conflict("INVALID_STATE", "当前工单状态不允许此操作");
    if (!o.getVersion().equals(version)) throw ApiException.conflict("VERSION_CONFLICT", "记录已变更");
  }

  private ActionResponse<Detail> complete(ProductionOrder o, String action) {
    String op = commands.record("PRODUCTION_ORDER", o.getId(), action);
    audit.success(action, "PRODUCTION_ORDER", o.getId());
    return response(o, op, false);
  }

  private ActionResponse<Detail> response(ProductionOrder o, String op, boolean replay) {
    var dto = dto(o);
    return new ActionResponse<>(op, replay, dto, dto.stockDocuments());
  }

  private ReceiptResult receiptResult(
      ProductionOrder o, long documentId, String op, boolean replay) {
    return new ReceiptResult(
        op,
        replay,
        stock.document(Long.toString(documentId)),
        stock.forSource("PRODUCTION_ORDER", o.getId()).stream()
            .filter(d -> d.id().equals(Long.toString(documentId)))
            .toList(),
        new RelatedOrder(o.getId().toString(), o.getVersion(), o.getOrderNo(), o.getStatus()));
  }

  private String createdBy(ProductionOrder o) {
    return o.getCreatedBy() == null ? "系统" : operators.displayName(o.getCreatedBy());
  }

  private Summary summary(ProductionOrder o) {
    return new Summary(
        o.getId().toString(),
        o.getVersion(),
        o.getOrderNo(),
        o.getProductMaterialId().toString(),
        o.getProductCode(),
        o.getProductName(),
        o.getUnit(),
        o.getPlannedQuantity().toPlainString(),
        o.getIssueWarehouseId().toString(),
        o.getIssueWarehouseName(),
        o.getReceiptWarehouseId().toString(),
        o.getReceiptWarehouseName(),
        o.getStatus(),
        o.getCreatedAt(),
        o.getUpdatedAt(),
        createdBy(o));
  }

  private Detail dto(ProductionOrder o) {
    var lines =
        snapshots.findByProductionOrderIdOrderByLineNo(o.getId()).stream()
            .map(
                s ->
                    new Snapshot(
                        s.getId().toString(),
                        s.getLineNo(),
                        s.getMaterialId().toString(),
                        s.getMaterialCode(),
                        s.getMaterialName(),
                        s.getUnit(),
                        s.getQuantityPer().toPlainString(),
                        s.getRequiredQuantity().toPlainString()))
            .toList();
    var report =
        reports
            .findByProductionOrderId(o.getId())
            .map(
                r ->
                    new Report(
                        r.getId().toString(),
                        r.getReportedQuantity().toPlainString(),
                        r.getReportedAt(),
                        r.getReportedByName()))
            .orElse(null);
    return new Detail(
        o.getId().toString(),
        o.getVersion(),
        o.getOrderNo(),
        o.getProductMaterialId().toString(),
        o.getProductCode(),
        o.getProductName(),
        o.getUnit(),
        o.getPlannedQuantity().toPlainString(),
        o.getIssueWarehouseId().toString(),
        o.getIssueWarehouseName(),
        o.getReceiptWarehouseId().toString(),
        o.getReceiptWarehouseName(),
        o.getStatus(),
        o.getCreatedAt(),
        o.getUpdatedAt(),
        createdBy(o),
        o.getBomId().toString(),
        o.getBomCode(),
        o.getBomRevision(),
        o.getRemark(),
        o.getConfirmedAt(),
        o.getIssuedAt(),
        o.getReportedAt(),
        o.getCompletedAt(),
        o.getCancelledAt(),
        lines,
        report,
        stock.forSource("PRODUCTION_ORDER", o.getId()));
  }

  private record Draft(
      String product,
      String bom,
      String quantity,
      String issueWarehouse,
      String receiptWarehouse,
      String remark) {}
}
