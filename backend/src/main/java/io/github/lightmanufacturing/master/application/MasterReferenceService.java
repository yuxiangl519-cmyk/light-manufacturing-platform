package io.github.lightmanufacturing.master.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.master.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class MasterReferenceService {
  public record MaterialRef(
      long id, String code, String name, String type, String unit, boolean enabled) {}

  public record WarehouseRef(long id, String code, String name, boolean enabled) {}

  public record PartnerRef(long id, String name, boolean enabled) {}

  private final MaterialRepository materials;
  private final WarehouseRepository warehouses;
  private final CustomerRepository customers;
  private final SupplierRepository suppliers;

  public MasterReferenceService(
      MaterialRepository materials,
      WarehouseRepository warehouses,
      CustomerRepository customers,
      SupplierRepository suppliers) {
    this.materials = materials;
    this.warehouses = warehouses;
    this.customers = customers;
    this.suppliers = suppliers;
  }

  @Transactional
  public Map<Long, MaterialRef> requireMaterials(Collection<Long> ids, boolean requireEnabled) {
    Map<Long, MaterialRef> result = new LinkedHashMap<>();
    for (long id : new TreeSet<>(ids)) {
      var m =
          (requireEnabled ? materials.readLockById(id) : materials.findById(id))
              .orElseThrow(ApiException::missing);
      if (requireEnabled && !m.getEnabled())
        throw ApiException.conflict("DISABLED_REFERENCE", "物料已停用");
      result.put(
          id,
          new MaterialRef(id, m.getCode(), m.getName(), m.getType(), m.getUnit(), m.getEnabled()));
    }
    return Collections.unmodifiableMap(result);
  }

  @Transactional
  public WarehouseRef requireWarehouse(long id, boolean requireEnabled) {
    var w =
        (requireEnabled ? warehouses.readLockById(id) : warehouses.findById(id))
            .orElseThrow(ApiException::missing);
    if (requireEnabled && !w.getEnabled())
      throw ApiException.conflict("DISABLED_REFERENCE", "仓库已停用");
    return new WarehouseRef(id, w.getCode(), w.getName(), w.getEnabled());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markMaterialsReferenced(Collection<Long> ids) {
    for (long id : new TreeSet<>(ids)) {
      var m = materials.lockById(id).orElseThrow(ApiException::missing);
      m.setReferenced(true);
    }
    materials.flush();
  }

  @Transactional
  public PartnerRef requireCustomer(long id, boolean requireEnabled) {
    var p =
        (requireEnabled ? customers.readLockById(id) : customers.findById(id))
            .orElseThrow(ApiException::missing);
    if (requireEnabled && !p.getEnabled())
      throw ApiException.conflict("DISABLED_REFERENCE", "客户已停用");
    return new PartnerRef(id, p.getName(), p.getEnabled());
  }

  @Transactional
  public PartnerRef requireSupplier(long id, boolean requireEnabled) {
    var p =
        (requireEnabled ? suppliers.readLockById(id) : suppliers.findById(id))
            .orElseThrow(ApiException::missing);
    if (requireEnabled && !p.getEnabled())
      throw ApiException.conflict("DISABLED_REFERENCE", "供应商已停用");
    return new PartnerRef(id, p.getName(), p.getEnabled());
  }
}
