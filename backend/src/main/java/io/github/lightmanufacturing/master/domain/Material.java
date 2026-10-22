package io.github.lightmanufacturing.master.domain;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "material")
public class Material extends AuditedEntity {
  private String code;

  public String getCode() {
    return code;
  }

  public void setCode(String v) {
    code = v;
  }

  private String name;

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  private String type;

  public String getType() {
    return type;
  }

  public void setType(String v) {
    type = v;
  }

  private String unit;

  public String getUnit() {
    return unit;
  }

  public void setUnit(String v) {
    unit = v;
  }

  private String specification;

  public String getSpecification() {
    return specification;
  }

  public void setSpecification(String v) {
    specification = v;
  }

  @Column(precision = 12, scale = 0)
  private java.math.BigDecimal minStock;

  public java.math.BigDecimal getMinStock() {
    return minStock;
  }

  public void setMinStock(java.math.BigDecimal v) {
    minStock = v;
  }

  private boolean enabled;

  public boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  private boolean referenced;

  public boolean getReferenced() {
    return referenced;
  }

  public void setReferenced(boolean v) {
    referenced = v;
  }
}
