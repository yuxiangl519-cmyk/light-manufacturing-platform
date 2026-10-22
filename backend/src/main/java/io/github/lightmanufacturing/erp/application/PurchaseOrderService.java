package io.github.lightmanufacturing.erp.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.erp.domain.*;
import io.github.lightmanufacturing.erp.dto.ErpDtos.*;
import io.github.lightmanufacturing.erp.repository.OrderRepository;
import io.github.lightmanufacturing.master.application.MasterReferenceService;
import io.github.lightmanufacturing.shared.api.*;
import io.github.lightmanufacturing.shared.api.BusinessDtos.ActionResponse;
import io.github.lightmanufacturing.shared.application.CommandService;
import io.github.lightmanufacturing.shared.security.BusinessActor;
import io.github.lightmanufacturing.system.application.*;
import io.github.lightmanufacturing.wms.application.*;
import io.github.lightmanufacturing.wms.domain.StockPosting;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
public class PurchaseOrderService {
  private static final OrderType TYPE = OrderType.PURCHASE;
  private final OrderRepository orders;
  private final OrderDraftValidator validation;
  private final MasterReferenceService master;
  private final InventoryService inventory;
  private final StockQueryService stock;
  private final CommandService commands;
  private final OperatorDirectory operators;
  private final AuditService audit;

  public PurchaseOrderService(
      OrderRepository orders,
      OrderDraftValidator validation,
      MasterReferenceService master,
      InventoryService inventory,
      StockQueryService stock,
      CommandService commands,
      OperatorDirectory operators,
      AuditService audit) {
    this.orders = orders;
    this.validation = validation;
    this.master = master;
    this.inventory = inventory;
    this.stock = stock;
    this.commands = commands;
    this.operators = operators;
    this.audit = audit;
  }

  public PurchaseDetail create(PurchaseWrite b) {
    var d =
        validation.prepare(
            TYPE, b.supplierId(), b.warehouseId(), b.remark(), b.items(), null, List.of());
    long actor = BusinessActor.requireId();
    String no =
        TYPE.prefix
            + io.github.lightmanufacturing.shared.time.BusinessTime.today(
                    ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-"
            + UUID.randomUUID();
    long id =
        orders.create(
            TYPE,
            no,
            d.partner(),
            d.partnerName(),
            d.warehouse(),
            d.warehouseName(),
            d.remark(),
            d.total(),
            actor,
            operators.displayName(actor),
            d.lines());
    audit.success("CREATE", TYPE.resource, id);
    return detail(id);
  }

  public PurchaseDetail update(long id, PurchaseUpdate b) {
    var h = orders.get(TYPE, id, true);
    validation.draft(h, b.version());
    var d =
        validation.prepare(
            TYPE,
            b.supplierId(),
            b.warehouseId(),
            b.remark(),
            b.items(),
            h,
            orders.lines(TYPE, id));
    orders.updateDraft(
        TYPE,
        id,
        d.partner(),
        d.partnerName(),
        d.warehouse(),
        d.warehouseName(),
        d.remark(),
        d.total(),
        BusinessActor.requireId(),
        d.lines());
    audit.success("UPDATE", TYPE.resource, id);
    return detail(id);
  }

  public void delete(long id, long version) {
    var h = orders.get(TYPE, id, true);
    validation.draft(h, version);
    orders.delete(TYPE, id, BusinessActor.requireId());
    audit.success("DELETE", TYPE.resource, id);
  }

  public ActionResponse<PurchaseDetail> cancel(long id, Long version) {
    var h = orders.get(TYPE, id, true);
    var replay = commands.find(TYPE.resource, id, "CANCEL");
    if (replay.isPresent()) return response(id, replay.get(), true);
    validation.draft(h, version);
    orders.transition(TYPE, id, "CANCELLED", BusinessActor.requireId());
    return success(id, "CANCEL");
  }

  public ActionResponse<PurchaseDetail> confirm(long id, Long version) {
    var h = orders.get(TYPE, id, true);
    var replay = commands.find(TYPE.resource, id, "CONFIRM");
    if (replay.isPresent()) return response(id, replay.get(), true);
    validation.draft(h, version);
    var lines = validation.frozen(TYPE, orders.lines(TYPE, id));
    var p = master.requireSupplier(h.partnerId(), true);
    var w = master.requireWarehouse(h.warehouseId(), true);
    orders.confirmSnapshot(TYPE, id, p.name(), w.name(), lines);
    orders.transition(TYPE, id, "CONFIRMED", BusinessActor.requireId());
    return success(id, "CONFIRM");
  }

  public ActionResponse<PurchaseDetail> receive(long id, Long version) {
    var h = orders.get(TYPE, id, true);
    var replay = commands.find(TYPE.resource, id, "RECEIVE");
    if (replay.isPresent()) return response(id, replay.get(), true);
    validation.version(h, version);
    if (!h.status().equals("CONFIRMED")) throw ApiException.conflict("INVALID_STATE", "仅已确认单据可收货");
    long actor = BusinessActor.requireId();
    var lines =
        orders.lines(TYPE, id).stream()
            .map(l -> new StockPosting.Line(l.id(), l.materialId(), l.quantity()))
            .toList();
    inventory.post(
        new StockPosting(
            StockPosting.Kind.PURCHASE_RECEIPT, id, h.orderNo(), h.warehouseId(), actor, lines));
    orders.transition(TYPE, id, TYPE.doneStatus, actor);
    return success(id, "RECEIVE");
  }

  private ActionResponse<PurchaseDetail> success(long id, String action) {
    String operation = commands.record(TYPE.resource, id, action);
    audit.success(action, TYPE.resource, id);
    return response(id, operation, false);
  }

  private ActionResponse<PurchaseDetail> response(long id, String operation, boolean replayed) {
    return new ActionResponse<>(
        operation, replayed, detail(id), stock.forSource(TYPE.resource, id));
  }

  @Transactional(readOnly = true)
  public PurchaseDetail detail(long id) {
    return view(orders.get(TYPE, id, false));
  }

  private PurchaseDetail view(OrderHeader h) {
    var lines =
        orders.lines(TYPE, h.id()).stream()
            .map(
                l ->
                    new MoneyLine(
                        l.lineNo(),
                        Long.toString(l.materialId()),
                        l.materialCode(),
                        l.materialName(),
                        l.unit(),
                        l.quantity().toPlainString(),
                        l.unitPrice().toPlainString(),
                        l.lineAmount().toPlainString()))
            .toList();
    return new PurchaseDetail(
        Long.toString(h.id()),
        h.version(),
        h.orderNo(),
        Long.toString(h.partnerId()),
        h.partnerName(),
        Long.toString(h.warehouseId()),
        h.warehouseName(),
        h.status(),
        h.totalAmount().toPlainString(),
        h.createdAt(),
        h.updatedAt(),
        h.createdByName(),
        h.remark(),
        h.confirmedAt(),
        h.doneAt(),
        h.cancelledAt(),
        lines,
        stock.forSource(TYPE.resource, h.id()));
  }

  @Transactional(readOnly = true)
  public PageResponse<PurchaseSummary> page(
      String q,
      String status,
      String partner,
      String warehouse,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort) {
    var result = orders.page(TYPE, q, status, partner, warehouse, from, to, page, size, sort);
    return new PageResponse<>(
        result.items().stream()
            .map(
                h ->
                    new PurchaseSummary(
                        Long.toString(h.id()),
                        h.version(),
                        h.orderNo(),
                        Long.toString(h.partnerId()),
                        h.partnerName(),
                        Long.toString(h.warehouseId()),
                        h.warehouseName(),
                        h.status(),
                        h.totalAmount().toPlainString(),
                        h.createdAt(),
                        h.updatedAt(),
                        h.createdByName()))
            .toList(),
        page,
        size,
        result.total());
  }
}
