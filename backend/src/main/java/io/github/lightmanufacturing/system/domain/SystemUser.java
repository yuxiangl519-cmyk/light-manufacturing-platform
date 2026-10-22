package io.github.lightmanufacturing.system.domain;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "sys_user")
public class SystemUser extends AuditedEntity {
  @Column(nullable = false, length = 50)
  private String username;

  @Column(nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 80)
  private String displayName;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(nullable = false)
  private long authVersion;

  @ManyToMany
  @JoinTable(
      name = "sys_user_role",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<SystemRole> roles = new HashSet<>();

  public String getUsername() {
    return username;
  }

  public void setUsername(String value) {
    this.username = value;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String value) {
    this.passwordHash = value;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String value) {
    this.displayName = value;
  }

  public boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    this.enabled = value;
  }

  public long getAuthVersion() {
    return authVersion;
  }

  public void setAuthVersion(long value) {
    this.authVersion = value;
  }

  public Set<SystemRole> getRoles() {
    return roles;
  }

  public void setRoles(Set<SystemRole> value) {
    this.roles = value;
  }
}
