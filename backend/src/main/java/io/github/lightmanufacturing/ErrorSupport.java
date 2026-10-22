package io.github.lightmanufacturing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class ErrorSupport {
  private final ObjectMapper mapper;

  public ErrorSupport(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public record FieldError(String field, String message) {}

  public record ErrorBody(
      String code,
      String message,
      String traceId,
      List<FieldError> fieldErrors,
      Map<String, Object> details) {}

  public ErrorBody body(String code, String message) {
    return new ErrorBody(code, message, MDC.get("traceId"), List.of(), Map.of());
  }

  public void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    if (response.isCommitted()) return;
    response.resetBuffer();
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    mapper.writeValue(response.getOutputStream(), body(code, message));
  }

  public static boolean dependency(Throwable failure) {
    for (Throwable e = failure; e != null; e = e.getCause()) {
      String n = e.getClass().getName();
      if (n.startsWith("org.springframework.data.redis")
          || n.startsWith("io.lettuce")
          || e instanceof org.springframework.dao.DataAccessResourceFailureException
          || e instanceof org.springframework.transaction.CannotCreateTransactionException)
        return true;
    }
    return false;
  }
}
