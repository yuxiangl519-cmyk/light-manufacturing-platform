package io.github.lightmanufacturing.system.domain;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "sys_role")
public class SystemRole extends AuditedEntity {
  @Column(nullable = false, length = 40)
  private String code;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(nullable = false, length = 255)
  private String description = "";

  @Column(nullable = false)
  private boolean builtin;

  @ElementCollection
  @CollectionTable(name = "sys_role_permission", joinColumns = @JoinColumn(name = "role_id"))
  @Column(name = "permission_code", nullable = false, length = 80)
  private Set<String> permissions = new HashSet<>();

  public String getCode() {
    return code;
  }

  public void setCode(String value) {
    this.code = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String value) {
    this.description = value;
  }

  public boolean getBuiltin() {
    return builtin;
  }

  public void setBuiltin(boolean value) {
    this.builtin = value;
  }

  public Set<String> getPermissions() {
    return permissions;
  }

  public void setPermissions(Set<String> value) {
    this.permissions = value;
  }
}
