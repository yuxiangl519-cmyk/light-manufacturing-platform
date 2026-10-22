package io.github.lightmanufacturing.mes.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.master.application.MasterReferenceService;
import io.github.lightmanufacturing.mes.domain.*;
import io.github.lightmanufacturing.mes.repository.*;
import io.github.lightmanufacturing.shared.api.Quantities;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductionConfirmationService {
  public record SnapshotLine(
      String materialId,
      String materialCode,
      String materialName,
      String unit,
      String quantityPer,
      String requiredQuantity) {}

  public record Confirmation(
      String orderId, String status, int bomRevision, List<SnapshotLine> items) {}

  private final ProductionOrderRepository orders;
  private final BomRepository boms;
  private final ProductionSnapshotRepository snapshots;
  private final MasterReferenceService master;
  private final EntityManager em;
  private final io.github.lightmanufacturing.system.application.AuditService audit;

  public ProductionConfirmationService(
      ProductionOrderRepository o,
      BomRepository b,
      ProductionSnapshotRepository s,
      MasterReferenceService m,
      EntityManager em,
      io.github.lightmanufacturing.system.application.AuditService audit) {
    orders = o;
    boms = b;
    snapshots = s;
    master = m;
    this.em = em;
    this.audit = audit;
  }

  @Transactional
  public Confirmation confirm(long orderId, long expectedVersion) {
    var order = orders.lockById(orderId).orElseThrow(ApiException::missing);
    if (order.getDeletedAt() != null) throw ApiException.missing();
    if (!"DRAFT".equals(order.getStatus()))
      throw ApiException.conflict("INVALID_STATE", "只允许确认草稿工单");
    if (order.getVersion() != expectedVersion)
      throw ApiException.conflict("VERSION_CONFLICT", "记录已变更");
    var bom = boms.lockById(order.getBomId()).orElseThrow(ApiException::missing);
    if (!bom.getEnabled()) throw ApiException.conflict("DISABLED_REFERENCE", "BOM已停用");
    if (!bom.getProductMaterialId().equals(order.getProductMaterialId()))
      throw ApiException.invalid("BOM与工单成品不一致");
    var ids = new TreeSet<Long>();
    ids.add(order.getProductMaterialId());
    bom.getItems().forEach(i -> ids.add(i.getMaterialId()));
    master.markMaterialsReferenced(ids);
    var refs = master.requireMaterials(ids, true);
    var product = refs.get(order.getProductMaterialId());
    if (!product.type().equals("FINISHED_GOOD") || bom.getItems().isEmpty())
      throw ApiException.invalid("工单成品或BOM不合法");
    var issue = master.requireWarehouse(order.getIssueWarehouseId(), true);
    var receipt = master.requireWarehouse(order.getReceiptWarehouseId(), true);
    var planned = Quantities.positive(order.getPlannedQuantity());
    for (var item : bom.getItems()) {
      var material = refs.get(item.getMaterialId());
      if (!material.type().equals("RAW_MATERIAL")) throw ApiException.invalid("BOM明细必须原料");
      em.persist(
          new ProductionMaterialSnapshot(
              orderId,
              item.getLineNo(),
              material.id(),
              material.code(),
              material.name(),
              material.unit(),
              Quantities.positive(item.getQuantityPer()),
              Quantities.positive(item.getQuantityPer().multiply(planned))));
    }
    order.setProductCode(product.code());
    order.setProductName(product.name());
    order.setUnit(product.unit());
    order.setBomCode(bom.getCode());
    order.setBomRevision(bom.getRevision());
    order.setIssueWarehouseName(issue.name());
    order.setReceiptWarehouseName(receipt.name());
    order.setStatus("READY_TO_ISSUE");
    order.setConfirmedAt(io.github.lightmanufacturing.shared.time.BusinessTime.now());
    orders.flush();
    audit.success("CONFIRM", "PRODUCTION_ORDER", orderId);
    return snapshot(orderId);
  }

  public Confirmation snapshot(long orderId) {
    var o = orders.findById(orderId).orElseThrow(ApiException::missing);
    if (o.getBomRevision() == null) throw ApiException.conflict("INVALID_STATE", "工单尚未确认");
    return new Confirmation(
        Long.toString(orderId),
        o.getStatus(),
        o.getBomRevision(),
        snapshots.findByProductionOrderIdOrderByLineNo(orderId).stream()
            .map(
                s ->
                    new SnapshotLine(
                        s.getMaterialId().toString(),
                        s.getMaterialCode(),
                        s.getMaterialName(),
                        s.getUnit(),
                        s.getQuantityPer().toPlainString(),
                        s.getRequiredQuantity().toPlainString()))
            .toList());
  }
}
