package io.github.lightmanufacturing;

import io.github.lightmanufacturing.system.application.AuditIdentity;
import io.github.lightmanufacturing.system.application.AuditService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBoundaryFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestBoundaryFilter.class);
  private final ErrorSupport errors;
  private final AuditService audit;

  public RequestBoundaryFilter(ErrorSupport errors, AuditService audit) {
    this.errors = errors;
    this.audit = audit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader("X-Trace-Id");
    String trace =
        incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put("traceId", trace);
    response.setHeader("X-Trace-Id", trace);
    response.setHeader("Cache-Control", "no-store");
    try {
      chain.doFilter(request, response);
    } catch (Exception e) {
      boolean dep = ErrorSupport.dependency(e);
      LOG.warn(
          "request_failed code={} traceId={}",
          dep ? "DEPENDENCY_UNAVAILABLE" : "INTERNAL_ERROR",
          trace);
      errors.write(
          response,
          dep ? 503 : 500,
          dep ? "DEPENDENCY_UNAVAILABLE" : "INTERNAL_ERROR",
          dep ? "依赖服务暂不可用" : "服务暂时无法处理请求");
    } finally {
      if (!"GET".equals(request.getMethod())
          && response.getStatus() >= 400
          && request.getRequestURI().startsWith("/api/v1/")) {
        try {
          FailureResource resource = failureResource(request);
          audit.failure(
              resource.action(),
              resource.type(),
              resource.id(),
              (AuditIdentity) request.getAttribute(AuditIdentity.REQUEST_ATTRIBUTE),
              "HTTP_" + response.getStatus(),
              request.getRemoteAddr());
        } catch (Exception ignored) {
          LOG.warn("audit_unavailable traceId={}", trace);
        }
      }
      MDC.remove("traceId");
    }
  }

  private record FailureResource(String action, String type, Long id) {}

  private FailureResource failureResource(HttpServletRequest request) {
    String path = request.getRequestURI();
    String method = request.getMethod();
    if (path.equals("/api/v1/auth/login")) return new FailureResource("LOGIN", "SESSION", null);
    if (path.equals("/api/v1/auth/logout")) return new FailureResource("LOGOUT", "SESSION", null);
    if (path.equals("/api/v1/auth/password"))
      return new FailureResource("PASSWORD_CHANGE", "USER", null);
    var matcher =
        java.util.regex.Pattern.compile(
                "^/api/v1/system/(users|roles)(?:/([1-9][0-9]{0,18}))?(?:/(roles|reset-password|permissions))?$")
            .matcher(path);
    if (!matcher.matches()) return new FailureResource("WRITE", "API", null);
    String type = matcher.group(1).equals("users") ? "USER" : "ROLE";
    Long id = null;
    if (matcher.group(2) != null) {
      try {
        id = Long.parseLong(matcher.group(2));
      } catch (NumberFormatException ignored) {
      }
    }
    String suffix = matcher.group(3);
    String action =
        suffix == null
            ? (method.equals("POST") ? "CREATE" : method.equals("DELETE") ? "DELETE" : "UPDATE")
            : suffix.equals("roles")
                ? "ROLES"
                : suffix.equals("permissions") ? "PERMISSIONS" : "PASSWORD_RESET";
    return new FailureResource(
        action.equals("PASSWORD_RESET") ? action : type + "_" + action, type, id);
  }
}
