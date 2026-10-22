package io.github.lightmanufacturing.master.application;

import static io.github.lightmanufacturing.master.dto.MasterDtos.*;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.master.domain.*;
import io.github.lightmanufacturing.master.repository.*;
import io.github.lightmanufacturing.shared.api.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MasterService {
  private final MaterialRepository materials;
  private final CustomerRepository customers;
  private final SupplierRepository suppliers;
  private final WarehouseRepository warehouses;
  private final io.github.lightmanufacturing.system.application.AuditService audit;

  public MasterService(
      MaterialRepository m,
      CustomerRepository c,
      SupplierRepository s,
      WarehouseRepository w,
      io.github.lightmanufacturing.system.application.AuditService audit) {
    materials = m;
    customers = c;
    suppliers = s;
    warehouses = w;
    this.audit = audit;
  }

  public MaterialDto material(String id) {
    return dto(materials.findById(Policy.id(id)).orElseThrow(ApiException::missing));
  }

  public PageResponse<MaterialDto> materials(
      String q, Boolean enabled, String type, int page, int size, String sort) {
    Specification<Material> spec = MasterQuery.filter(q, enabled);
    if (type != null) {
      validType(type);
      spec = spec.and((r, x, b) -> b.equal(r.get("type"), type));
    }
    var p = materials.findAll(spec, MasterQuery.page(page, size, sort));
    return new PageResponse<>(
        p.map(this::dto).getContent(), page, size, Long.toString(p.getTotalElements()));
  }

  @Transactional
  public MaterialDto saveMaterial(String id, MaterialWrite b) {
    var m =
        id == null
            ? new Material()
            : materials.lockById(Policy.id(id)).orElseThrow(ApiException::missing);
    String code = Policy.text(b.code(), 50, "编码");
    String type = b.type();
    validType(type);
    String unit = Policy.text(b.unit(), 10, "单位");
    if (id != null) {
      MasterQuery.version(m, b.version());
      if (!m.getType().equals(type)) throw ApiException.conflict("IMMUTABLE_FIELD", "物料类型不可修改");
      if (m.getReferenced() && (!m.getCode().equals(code) || !m.getUnit().equals(unit)))
        throw ApiException.conflict("REFERENCED_DATA", "被引用物料的编码和单位不可修改");
    }
    if ((id == null || !m.getCode().equals(code)) && materials.existsByCode(code))
      throw ApiException.conflict("DUPLICATE_CODE", "编码已存在");
    m.setCode(code);
    m.setName(Policy.text(b.name(), 120, "名称"));
    m.setType(type);
    m.setUnit(unit);
    m.setSpecification(MasterQuery.optional(b.specification(), 255));
    m.setMinStock(Quantities.nonNegative(b.minStock()));
    if (b.enabled() == null) throw ApiException.invalid("启用状态必填");
    m.setEnabled(b.enabled());
    materials.saveAndFlush(m);
    audit.success(id == null ? "CREATE" : "UPDATE", "MATERIAL", m.getId());
    return dto(m);
  }

  private void validType(String v) {
    if (!java.util.Set.of("RAW_MATERIAL", "FINISHED_GOOD").contains(v == null ? "" : v))
      throw ApiException.invalid("物料类型只允许原料或成品");
  }

  private MaterialDto dto(Material m) {
    return new MaterialDto(
        m.getId().toString(),
        m.getVersion(),
        m.getCode(),
        m.getName(),
        m.getType(),
        m.getUnit(),
        m.getSpecification(),
        m.getMinStock().toPlainString(),
        m.getEnabled(),
        m.getCreatedAt(),
        m.getUpdatedAt());
  }

  public PartnerDto customer(String id) {
    return dto(customers.findById(Policy.id(id)).orElseThrow(ApiException::missing));
  }

  public PageResponse<PartnerDto> customers(
      String q, Boolean enabled, int page, int size, String sort) {
    var p = customers.findAll(MasterQuery.filter(q, enabled), MasterQuery.page(page, size, sort));
    return new PageResponse<>(
        p.map(this::dto).getContent(), page, size, Long.toString(p.getTotalElements()));
  }

  @Transactional
  public PartnerDto saveCustomer(String id, PartnerWrite b) {
    var m =
        id == null
            ? new Customer()
            : customers.lockById(Policy.id(id)).orElseThrow(ApiException::missing);
    String code = Policy.text(b.code(), 50, "编码");
    if (id != null) {
      MasterQuery.version(m, b.version());
      if (!m.getCode().equals(code)) throw ApiException.conflict("IMMUTABLE_FIELD", "编码不可修改");
    }
    if (id == null && customers.existsByCode(code))
      throw ApiException.conflict("DUPLICATE_CODE", "编码已存在");
    m.setCode(code);
    m.setName(Policy.text(b.name(), 120, "名称"));
    m.setAddress(MasterQuery.optional(b.address(), 255));
    m.setContactName(MasterQuery.optional(b.contactName(), 80));
    m.setPhone(MasterQuery.optional(b.phone(), 40));
    if (b.enabled() == null) throw ApiException.invalid("启用状态必填");
    m.setEnabled(b.enabled());
    customers.saveAndFlush(m);
    audit.success(id == null ? "CREATE" : "UPDATE", "CUSTOMER", m.getId());
    return dto(m);
  }

  private PartnerDto dto(Customer m) {
    return new PartnerDto(
        m.getId().toString(),
        m.getVersion(),
        m.getCode(),
        m.getName(),
        m.getContactName(),
        m.getPhone(),
        m.getAddress(),
        m.getEnabled(),
        m.getCreatedAt(),
        m.getUpdatedAt());
  }

  public PartnerDto supplier(String id) {
    return dto(suppliers.findById(Policy.id(id)).orElseThrow(ApiException::missing));
  }

  public PageResponse<PartnerDto> suppliers(
      String q, Boolean enabled, int page, int size, String sort) {
    var p = suppliers.findAll(MasterQuery.filter(q, enabled), MasterQuery.page(page, size, sort));
    return new PageResponse<>(
        p.map(this::dto).getContent(), page, size, Long.toString(p.getTotalElements()));
  }

  @Transactional
  public PartnerDto saveSupplier(String id, PartnerWrite b) {
    var m =
        id == null
            ? new Supplier()
            : suppliers.lockById(Policy.id(id)).orElseThrow(ApiException::missing);
    String code = Policy.text(b.code(), 50, "编码");
    if (id != null) {
      MasterQuery.version(m, b.version());
      if (!m.getCode().equals(code)) throw ApiException.conflict("IMMUTABLE_FIELD", "编码不可修改");
    }
    if (id == null && suppliers.existsByCode(code))
      throw ApiException.conflict("DUPLICATE_CODE", "编码已存在");
    m.setCode(code);
    m.setName(Policy.text(b.name(), 120, "名称"));
    m.setAddress(MasterQuery.optional(b.address(), 255));
    m.setContactName(MasterQuery.optional(b.contactName(), 80));
    m.setPhone(MasterQuery.optional(b.phone(), 40));
    if (b.enabled() == null) throw ApiException.invalid("启用状态必填");
    m.setEnabled(b.enabled());
    suppliers.saveAndFlush(m);
    audit.success(id == null ? "CREATE" : "UPDATE", "SUPPLIER", m.getId());
    return dto(m);
  }

  private PartnerDto dto(Supplier m) {
    return new PartnerDto(
        m.getId().toString(),
        m.getVersion(),
        m.getCode(),
        m.getName(),
        m.getContactName(),
        m.getPhone(),
        m.getAddress(),
        m.getEnabled(),
        m.getCreatedAt(),
        m.getUpdatedAt());
  }

  public WarehouseDto warehouse(String id) {
    return dto(warehouses.findById(Policy.id(id)).orElseThrow(ApiException::missing));
  }

  public PageResponse<WarehouseDto> warehouses(
      String q, Boolean enabled, int page, int size, String sort) {
    var p = warehouses.findAll(MasterQuery.filter(q, enabled), MasterQuery.page(page, size, sort));
    return new PageResponse<>(
        p.map(this::dto).getContent(), page, size, Long.toString(p.getTotalElements()));
  }

  @Transactional
  public WarehouseDto saveWarehouse(String id, WarehouseWrite b) {
    var m =
        id == null
            ? new Warehouse()
            : warehouses.lockById(Policy.id(id)).orElseThrow(ApiException::missing);
    String code = Policy.text(b.code(), 50, "编码");
    if (id != null) {
      MasterQuery.version(m, b.version());
      if (!m.getCode().equals(code)) throw ApiException.conflict("IMMUTABLE_FIELD", "编码不可修改");
    }
    if (id == null && warehouses.existsByCode(code))
      throw ApiException.conflict("DUPLICATE_CODE", "编码已存在");
    m.setCode(code);
    m.setName(Policy.text(b.name(), 120, "名称"));
    m.setAddress(MasterQuery.optional(b.address(), 255));
    if (b.enabled() == null) throw ApiException.invalid("启用状态必填");
    m.setEnabled(b.enabled());
    warehouses.saveAndFlush(m);
    audit.success(id == null ? "CREATE" : "UPDATE", "WAREHOUSE", m.getId());
    return dto(m);
  }

  private WarehouseDto dto(Warehouse m) {
    return new WarehouseDto(
        m.getId().toString(),
        m.getVersion(),
        m.getCode(),
        m.getName(),
        m.getAddress(),
        m.getEnabled(),
        m.getCreatedAt(),
        m.getUpdatedAt());
  }
}
