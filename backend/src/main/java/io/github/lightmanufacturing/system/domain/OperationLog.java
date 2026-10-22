package io.github.lightmanufacturing.system.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "operation_log")
@org.hibernate.annotations.Immutable
public class OperationLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long actorId;

  @Column(nullable = false, length = 80)
  private String actorName;

  @Column(nullable = false, length = 30)
  private String module;

  @Column(nullable = false, length = 40)
  private String action;

  @Column(length = 30)
  private String resourceType;

  private Long resourceId;

  @Column(length = 64)
  private String resourceNo;

  @Column(nullable = false)
  private boolean success;

  @Column(length = 64)
  private String errorCode;

  @Column(nullable = false, length = 500)
  private String summary;

  @Column(nullable = false, length = 64)
  private String traceId;

  @Column(nullable = false, length = 64)
  private String clientIp;

  @Column(nullable = false)
  private Instant occurredAt;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getActorId() {
    return actorId;
  }

  public void setActorId(Long value) {
    this.actorId = value;
  }

  public String getActorName() {
    return actorName;
  }

  public void setActorName(String value) {
    this.actorName = value;
  }

  public String getModule() {
    return module;
  }

  public void setModule(String value) {
    this.module = value;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String value) {
    this.action = value;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String value) {
    this.resourceType = value;
  }

  public Long getResourceId() {
    return resourceId;
  }

  public void setResourceId(Long value) {
    this.resourceId = value;
  }

  public String getResourceNo() {
    return resourceNo;
  }

  public void setResourceNo(String value) {
    this.resourceNo = value;
  }

  public boolean getSuccess() {
    return success;
  }

  public void setSuccess(boolean value) {
    this.success = value;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String value) {
    this.errorCode = value;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String value) {
    this.summary = value;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String value) {
    this.traceId = value;
  }

  public String getClientIp() {
    return clientIp;
  }

  public void setClientIp(String value) {
    this.clientIp = value;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant value) {
    this.occurredAt = value;
  }
}
