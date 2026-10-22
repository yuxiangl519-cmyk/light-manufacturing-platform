package io.github.lightmanufacturing;

import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiAdvice {
  private static final Logger LOG = LoggerFactory.getLogger(ApiAdvice.class);
  private final ErrorSupport errors;

  public ApiAdvice(ErrorSupport errors) {
    this.errors = errors;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e, HttpServletResponse response) {
    if (e.status == 429) response.setHeader("Retry-After", "900");
    return ResponseEntity.status(e.status).body(errors.body(e.code, e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    var fields =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorSupport.FieldError(f.getField(), "字段格式不符合要求"))
            .distinct()
            .toList();
    return ResponseEntity.badRequest()
        .body(
            new ErrorSupport.ErrorBody(
                "VALIDATION_FAILED", "请求字段校验失败", MDC.get("traceId"), fields, Map.of()));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> json(Exception e) {
    return ResponseEntity.badRequest().body(errors.body("INVALID_JSON", "请求格式错误"));
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<?> forbidden(Exception e) {
    return ResponseEntity.status(403).body(errors.body("FORBIDDEN", "没有操作权限"));
  }

  @ExceptionHandler({
    org.springframework.dao.OptimisticLockingFailureException.class,
    jakarta.persistence.OptimisticLockException.class
  })
  ResponseEntity<?> version(Exception e) {
    return ResponseEntity.status(409).body(errors.body("VERSION_CONFLICT", "记录已变更，请刷新"));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> integrity(Exception e) {
    return ResponseEntity.status(409).body(errors.body("DUPLICATE_CODE", "数据唯一性或引用约束冲突"));
  }

  @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
  ResponseEntity<?> lock(Exception e) {
    return ResponseEntity.status(409).body(errors.body("CONCURRENT_OPERATION", "发生并发操作，请重试"));
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.HttpRequestMethodNotSupportedException.class
  })
  ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(404).body(errors.body("NOT_FOUND", "接口不存在"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> other(Exception e) {
    boolean dependency = ErrorSupport.dependency(e);
    LOG.warn(
        "request_failed code={} traceId={} exceptionType={} location={}",
        dependency ? "DEPENDENCY_UNAVAILABLE" : "INTERNAL_ERROR",
        MDC.get("traceId"),
        e.getClass().getName(),
        java.util.Arrays.stream(e.getStackTrace())
            .filter(f -> f.getClassName().startsWith("io.github.lightmanufacturing"))
            .findFirst()
            .map(StackTraceElement::toString)
            .orElse("framework"));
    return ResponseEntity.status(dependency ? 503 : 500)
        .body(
            errors.body(
                dependency ? "DEPENDENCY_UNAVAILABLE" : "INTERNAL_ERROR",
                dependency ? "依赖服务暂不可用" : "服务暂时无法处理请求"));
  }
}
