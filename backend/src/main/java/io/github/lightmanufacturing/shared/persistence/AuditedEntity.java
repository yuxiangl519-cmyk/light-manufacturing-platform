package io.github.lightmanufacturing.shared.persistence;

import io.github.lightmanufacturing.shared.security.ActorPrincipal;
import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;

@MappedSuperclass
public abstract class AuditedEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  private Long createdBy;
  private Long updatedBy;

  @Version
  @Column(nullable = false)
  private Long version;

  @PrePersist
  void create() {
    createdAt = updatedAt = io.github.lightmanufacturing.shared.time.BusinessTime.now();
    createdBy = updatedBy = actor();
  }

  @PreUpdate
  void update() {
    updatedAt = io.github.lightmanufacturing.shared.time.BusinessTime.now();
    updatedBy = actor();
  }

  public static Long actor() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    return a != null && a.getPrincipal() instanceof ActorPrincipal p ? p.actorId() : null;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant value) {
    this.updatedAt = value;
  }

  public Long getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(Long value) {
    this.createdBy = value;
  }

  public Long getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(Long value) {
    this.updatedBy = value;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long value) {
    this.version = value;
  }
}
