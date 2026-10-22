package io.github.lightmanufacturing.erp.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.erp.domain.*;
import io.github.lightmanufacturing.erp.dto.ErpDtos.LineWrite;
import io.github.lightmanufacturing.master.application.MasterReferenceService;
import io.github.lightmanufacturing.shared.api.Quantities;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Common line validation; purchase and sales retain independent transaction orchestration. */
@Component
public class OrderDraftValidator {
  private final MasterReferenceService master;

  public OrderDraftValidator(MasterReferenceService master) {
    this.master = master;
  }

  public record Draft(
      long partner,
      String partnerName,
      long warehouse,
      String warehouseName,
      String remark,
      BigDecimal total,
      List<OrderLine> lines) {}

  public Draft prepare(
      OrderType t,
      String partnerValue,
      String warehouseValue,
      String remark,
      List<LineWrite> items,
      OrderHeader old,
      List<OrderLine> oldLines) {
    long partner = Policy.id(partnerValue), warehouse = Policy.id(warehouseValue);
    String note = remark == null ? "" : remark.trim();
    if (note.length() > 500) throw ApiException.invalid("备注不能超过500字符");
    if (items == null || items.isEmpty() || items.size() > 100)
      throw ApiException.invalid("明细须为1至100行");
    Set<Long> ids = new TreeSet<>(), previous = new HashSet<>();
    oldLines.forEach(l -> previous.add(l.materialId()));
    for (var i : items)
      if (i == null || !ids.add(Policy.id(i.materialId())))
        throw ApiException.invalid("明细物料不能重复或为空");
    master.markMaterialsReferenced(ids);
    var refs = master.requireMaterials(ids, false);
    var newIds = new TreeSet<>(ids);
    newIds.removeAll(previous);
    master.requireMaterials(newIds, true);
    var p =
        t == OrderType.PURCHASE
            ? master.requireSupplier(partner, old == null || old.partnerId() != partner)
            : master.requireCustomer(partner, old == null || old.partnerId() != partner);
    var w = master.requireWarehouse(warehouse, old == null || old.warehouseId() != warehouse);
    List<OrderLine> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var i : items) {
      var m = refs.get(Policy.id(i.materialId()));
      if (!m.type().equals(t.materialType)) throw ApiException.invalid("采购只允许原料，销售只允许成品");
      var qty = Quantities.positive(i.quantity());
      var price = Money.price(i.unitPrice());
      var amount = Money.amount(qty.multiply(price));
      total = Money.amount(total.add(amount));
      lines.add(
          new OrderLine(
              0, lines.size() + 1, m.id(), m.code(), m.name(), m.unit(), qty, price, amount));
    }
    return new Draft(
        partner, p.name(), warehouse, w.name(), note, Money.amount(total), List.copyOf(lines));
  }

  public void draft(OrderHeader h, Long version) {
    version(h, version);
    if (!h.status().equals("DRAFT")) throw ApiException.conflict("INVALID_STATE", "仅草稿可编辑、删除或取消");
  }

  public void version(OrderHeader h, Long version) {
    if (version == null || version < 0 || version > 9007199254740991L)
      throw ApiException.invalid("版本须为非负安全整数");
    if (h.version() != version) throw ApiException.conflict("VERSION_CONFLICT", "单据版本已变更");
  }

  public List<OrderLine> frozen(OrderType t, List<OrderLine> lines) {
    var ids = lines.stream().map(OrderLine::materialId).toList();
    master.markMaterialsReferenced(ids);
    var refs = master.requireMaterials(ids, true);
    return lines.stream()
        .map(
            l -> {
              var m = refs.get(l.materialId());
              if (!m.type().equals(t.materialType)) throw ApiException.invalid("物料类型不匹配");
              return new OrderLine(
                  l.id(),
                  l.lineNo(),
                  l.materialId(),
                  m.code(),
                  m.name(),
                  m.unit(),
                  l.quantity(),
                  l.unitPrice(),
                  l.lineAmount());
            })
        .toList();
  }
}
