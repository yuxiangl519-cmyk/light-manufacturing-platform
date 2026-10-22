package io.github.lightmanufacturing.system.domain;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "sys_permission")
public class Permission {
  @Id
  @Column(length = 80)
  private String code;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(nullable = false, length = 30)
  private String module;

  @Column(nullable = false)
  private int sortOrder;

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

  public String getModule() {
    return module;
  }

  public void setModule(String value) {
    this.module = value;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int value) {
    this.sortOrder = value;
  }
}
