package io.github.lightmanufacturing.mes.domain;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "bom")
public class Bom extends AuditedEntity {
  private String code;
  private String name;
  private Long productMaterialId;
  private int revision = 1;
  private boolean enabled;

  @OneToMany(mappedBy = "bom", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("lineNo")
  private List<BomItem> items = new ArrayList<>();

  public String getCode() {
    return code;
  }

  public void setCode(String v) {
    code = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public Long getProductMaterialId() {
    return productMaterialId;
  }

  public void setProductMaterialId(Long v) {
    productMaterialId = v;
  }

  public int getRevision() {
    return revision;
  }

  public void setRevision(int v) {
    revision = v;
  }

  public boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public List<BomItem> getItems() {
    return items;
  }
}
