package io.github.lightmanufacturing.system.application;

import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import io.github.lightmanufacturing.system.domain.*;
import io.github.lightmanufacturing.system.repository.*;
import io.github.lightmanufacturing.system.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
  private final LogRepository logs;
  private final UserRepository users;
  private final HttpServletRequest request;

  public AuditService(LogRepository logs, UserRepository users, HttpServletRequest request) {
    this.logs = logs;
    this.users = users;
    this.request = request;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void success(String action, String resource, Long id) {
    String clientIp =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null
            ? "internal"
            : request.getRemoteAddr();
    write(action, resource, id, true, null, clientIp);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failure(String action, String code) {
    write(action, null, null, false, code, "unknown");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failure(String action, String code, String clientIp) {
    write(action, null, null, false, code, clientIp);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failure(
      String action,
      String resource,
      Long resourceId,
      AuditIdentity identity,
      String code,
      String clientIp) {
    save(
        action,
        resource,
        resourceId,
        false,
        code,
        clientIp,
        identity == null ? null : identity.id(),
        identity == null ? "anonymous" : identity.name());
  }

  private void write(
      String action, String resource, Long id, boolean success, String code, String clientIp) {
    Long actor = AuditedEntity.actor();
    String actorName =
        actor == null
            ? "anonymous"
            : users.findById(actor).map(u -> u.getDisplayName()).orElse("unknown");
    save(action, resource, id, success, code, clientIp, actor, actorName);
  }

  private void save(
      String action,
      String resource,
      Long id,
      boolean success,
      String code,
      String clientIp,
      Long actor,
      String actorName) {
    OperationLog log = new OperationLog();
    log.setActorId(actor);
    log.setActorName(actorName);
    log.setModule(
        resource != null
                && java.util.Set.of("MATERIAL", "CUSTOMER", "SUPPLIER", "WAREHOUSE")
                    .contains(resource)
            ? "master"
            : resource != null && java.util.Set.of("BOM", "PRODUCTION_ORDER").contains(resource)
                ? "mes"
                : resource != null
                        && java.util.Set.of("PURCHASE_ORDER", "SALES_ORDER").contains(resource)
                    ? "erp"
                    : "STOCK_DOCUMENT".equals(resource) ? "wms" : "system");
    log.setAction(action);
    log.setResourceType(resource);
    log.setResourceId(id);
    log.setSuccess(success);
    log.setErrorCode(code);
    log.setSummary(success ? "操作成功" : "操作失败");
    log.setTraceId(MDC.get("traceId") == null ? "startup" : MDC.get("traceId"));
    log.setClientIp(clientIp == null ? "unknown" : clientIp);
    log.setOccurredAt(io.github.lightmanufacturing.shared.time.BusinessTime.now());
    logs.save(log);
  }
}
