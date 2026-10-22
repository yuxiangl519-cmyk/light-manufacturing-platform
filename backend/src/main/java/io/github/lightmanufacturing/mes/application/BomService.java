package io.github.lightmanufacturing.mes.application;

import static io.github.lightmanufacturing.mes.dto.BomDtos.*;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.master.application.MasterReferenceService;
import io.github.lightmanufacturing.mes.domain.*;
import io.github.lightmanufacturing.mes.repository.BomRepository;
import io.github.lightmanufacturing.shared.api.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BomService {
  private final BomRepository boms;
  private final MasterReferenceService master;
  private final io.github.lightmanufacturing.system.application.AuditService audit;

  public BomService(
      BomRepository b,
      MasterReferenceService m,
      io.github.lightmanufacturing.system.application.AuditService audit) {
    boms = b;
    master = m;
    this.audit = audit;
  }

  public BomDto get(String id) {
    return dto(boms.findById(Policy.id(id)).orElseThrow(ApiException::missing));
  }

  public PageResponse<BomDto> page(
      String q, Boolean enabled, String productMaterialId, int page, int size) {
    if (page < 1 || size < 1 || size > 100 || q != null && q.length() > 100)
      throw ApiException.invalid("查询参数超出范围");
    Long product = productMaterialId == null ? null : Policy.id(productMaterialId);
    Specification<Bom> spec =
        (r, x, b) -> {
          var p = b.conjunction();
          if (enabled != null) p = b.and(p, b.equal(r.get("enabled"), enabled));
          if (product != null) p = b.and(p, b.equal(r.get("productMaterialId"), product));
          if (q != null && !q.isBlank()) {
            String like =
                "%"
                    + q.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                    + "%";
            p =
                b.and(
                    p,
                    b.or(
                        b.like(b.lower(r.get("code")), like, '\\'),
                        b.like(b.lower(r.get("name")), like, '\\')));
          }
          return p;
        };
    var p =
        boms.findAll(
            spec, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    return new PageResponse<>(
        p.map(this::dto).getContent(), page, size, Long.toString(p.getTotalElements()));
  }

  @Transactional
  public BomDto save(String id, BomWrite body) {
    if (body.items() == null
        || body.items().isEmpty()
        || body.items().size() > 100
        || body.enabled() == null) throw ApiException.invalid("BOM须包含1至100条明细和启用状态");
    long productId = Policy.id(body.productMaterialId());
    var ids = new TreeSet<Long>();
    ids.add(productId);
    var components = new HashSet<Long>();
    for (var line : body.items()) {
      if (line == null) throw ApiException.invalid("BOM明细不能为空");
      long m = Policy.id(line.materialId());
      if (!components.add(m)) throw ApiException.invalid("BOM物料不能重复");
      ids.add(m);
      Quantities.positive(line.quantityPer());
    }
    var b =
        id == null ? new Bom() : boms.lockById(Policy.id(id)).orElseThrow(ApiException::missing);
    // Lock the BOM header before its sorted material identities, matching confirmation.
    master.markMaterialsReferenced(ids);
    var refs = master.requireMaterials(ids, false);
    var historicalIds = new HashSet<Long>();
    if (id != null) {
      historicalIds.add(b.getProductMaterialId());
      b.getItems().forEach(i -> historicalIds.add(i.getMaterialId()));
    }
    for (long materialId : ids)
      if (!refs.get(materialId).enabled()
          && (id == null || body.enabled() || !historicalIds.contains(materialId)))
        throw ApiException.conflict("DISABLED_REFERENCE", "新增或启用BOM不能引用停用物料");
    if (!refs.get(productId).type().equals("FINISHED_GOOD"))
      throw ApiException.invalid("BOM成品必须为成品物料");
    for (long m : components)
      if (!refs.get(m).type().equals("RAW_MATERIAL")) throw ApiException.invalid("BOM明细必须为原料物料");
    String code = Policy.text(body.code(), 50, "BOM编码");
    if (id != null) {
      if (body.version() == null || body.version() < 0)
        throw ApiException.invalid("修改须提供非负version");
      if (!body.version().equals(b.getVersion()))
        throw ApiException.conflict("VERSION_CONFLICT", "记录已变更");
      if (!b.getCode().equals(code) || b.getProductMaterialId() != productId)
        throw ApiException.conflict("IMMUTABLE_FIELD", "BOM编码和成品不可修改");
      b.getItems().clear();
      boms.flush();
      b.setRevision(Math.addExact(b.getRevision(), 1));
    } else if (boms.existsByCode(code)) throw ApiException.conflict("DUPLICATE_CODE", "BOM编码已存在");
    b.setCode(code);
    b.setName(Policy.text(body.name(), 120, "BOM名称"));
    b.setProductMaterialId(productId);
    b.setEnabled(body.enabled());
    int index = 0;
    for (var line : body.items()) {
      var item = new BomItem();
      item.setBom(b);
      item.setLineNo(++index);
      item.setMaterialId(Policy.id(line.materialId()));
      item.setQuantityPer(Quantities.positive(line.quantityPer()));
      b.getItems().add(item);
    }
    boms.saveAndFlush(b);
    audit.success(id == null ? "CREATE" : "UPDATE", "BOM", b.getId());
    return dto(b);
  }

  private BomDto dto(Bom b) {
    var ids = new HashSet<Long>();
    ids.add(b.getProductMaterialId());
    b.getItems().forEach(i -> ids.add(i.getMaterialId()));
    var refs = master.requireMaterials(ids, false);
    var p = refs.get(b.getProductMaterialId());
    return new BomDto(
        b.getId().toString(),
        b.getVersion(),
        b.getCode(),
        b.getName(),
        p.id() + "",
        p.code(),
        p.name(),
        b.getRevision(),
        b.getEnabled(),
        b.getItems().stream()
            .map(
                i -> {
                  var m = refs.get(i.getMaterialId());
                  return new ItemDto(
                      i.getId().toString(),
                      i.getLineNo(),
                      m.id() + "",
                      m.code(),
                      m.name(),
                      m.unit(),
                      i.getQuantityPer().toPlainString());
                })
            .toList(),
        b.getCreatedAt(),
        b.getUpdatedAt());
  }
}
