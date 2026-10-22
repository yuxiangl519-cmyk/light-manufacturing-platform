package io.github.lightmanufacturing.shared.application;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.shared.security.BusinessActor;
import java.sql.Statement;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Call only after locking the source aggregate; persisted alongside all business effects. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CommandService {
  private final JdbcTemplate jdbc;

  public CommandService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> find(String resource, long id, String action) {
    validate(resource, id, action);
    return jdbc
        .queryForList(
            "select id from command_record where resource_type=? and resource_id=? and action=?",
            String.class,
            resource,
            id,
            action)
        .stream()
        .findFirst();
  }

  public String record(String resource, long id, String action) {
    validate(resource, id, action);
    long actor = BusinessActor.requireId();
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "insert into"
                      + " command_record(resource_type,resource_id,action,actor_id,committed_at,trace_id)"
                      + " values(?,?,?,?,"
                      + io.github.lightmanufacturing.shared.time.BusinessTime.sqlNow()
                      + ",?)",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setString(1, resource);
          statement.setLong(2, id);
          statement.setString(3, action);
          statement.setLong(4, actor);
          statement.setString(5, Objects.requireNonNullElse(MDC.get("traceId"), "internal"));
          return statement;
        },
        key);
    return Objects.requireNonNull(key.getKey()).toString();
  }

  private void validate(String resource, long id, String action) {
    if (id <= 0
        || !Set.of("PURCHASE_ORDER", "PRODUCTION_ORDER", "SALES_ORDER", "STOCK_DOCUMENT")
            .contains(resource)
        || !Set.of("CONFIRM", "RECEIVE", "ISSUE", "REPORT", "EXECUTE", "SHIP", "CANCEL")
            .contains(action)) throw ApiException.invalid("业务动作标识无效");
  }
}
