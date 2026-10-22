package io.github.lightmanufacturing.master.domain;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "customer")
public class Customer extends AuditedEntity {
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

  private String contactName;

  public String getContactName() {
    return contactName;
  }

  public void setContactName(String v) {
    contactName = v;
  }

  private String phone;

  public String getPhone() {
    return phone;
  }

  public void setPhone(String v) {
    phone = v;
  }

  private String address;

  public String getAddress() {
    return address;
  }

  public void setAddress(String v) {
    address = v;
  }

  private boolean enabled;

  public boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }
}
