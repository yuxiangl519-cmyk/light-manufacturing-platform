package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** HTTP, MySQL8, Flyway, Redis Session and Security exercise together; no mocked authentication. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Stage2IntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ApplicationContext application;

  @BeforeEach
  void resetOnlyThisProjectsTestLoginCounters() {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    List<String> keys = new ArrayList<>();
    try (var cursor =
        redis.scan(
            ScanOptions.scanOptions().match("light-mfg:test:login-limit:*").count(100).build())) {
      cursor.forEachRemaining(keys::add);
    }
    if (!keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void isolatedServicesAreTheLockedMySql8AndRedis7Versions() {
    assertEquals("8.4.7", jdbc.queryForObject("select version()", String.class));
    var server =
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                connection -> connection.serverCommands().info("server"));
    assertNotNull(server);
    assertEquals("7.4.2", server.getProperty("redis_version"));
  }

  @Test
  void anonymousIs401AndMinimalHealthDoesNotExposeDetails() throws Exception {
    Client guest = new Client();
    Reply unauthenticated = guest.get("/api/v1/auth/me");
    assertEquals(401, unauthenticated.status);
    assertEquals("UNAUTHENTICATED", unauthenticated.body.path("code").asText());
    assertFalse(unauthenticated.body.path("traceId").asText().isBlank());
    Reply health = guest.get("/actuator/health");
    assertEquals(200, health.status);
    assertEquals("UP", health.body.path("status").asText());
    assertFalse(health.body.has("components"));
    assertFalse(health.body.has("details"));
  }

  @Test
  void csrfRequiredForLoginAndProtectedMutation() throws Exception {
    Client guest = new Client();
    Reply missing =
        guest.send(
            "POST",
            "/api/v1/auth/login",
            Map.of("username", "admin", "password", secret("MFG_ADMIN_PASSWORD")),
            false);
    assertEquals(403, missing.status);
    assertEquals("CSRF_INVALID", missing.body.path("code").asText());
    Client admin = admin();
    Reply mutation =
        admin.send(
            "POST",
            "/api/v1/system/roles",
            Map.of("code", unique("csrf"), "name", "不应创建", "description", ""),
            false);
    assertEquals(403, mutation.status);
    assertEquals("CSRF_INVALID", mutation.body.path("code").asText());
  }

  @Test
  void loginSuccessFailurePermissionsAndLogoutInvalidateOldCookie() throws Exception {
    Client admin = admin();
    Reply me = admin.get("/api/v1/auth/me");
    assertEquals(200, me.status);
    assertEquals("admin", me.body.path("username").asText());
    assertTrue(me.body.path("permissions").isArray());
    assertEquals(34, me.body.path("permissions").size());
    assertFalse(me.body.has("password"));
    assertFalse(me.body.has("passwordHash"));
    assertEquals(34, admin.get("/api/v1/auth/permissions").body.path("permissions").size());
    String oldCookie = admin.cookie();
    assertEquals(204, admin.send("POST", "/api/v1/auth/logout", null, true).status);
    assertEquals(401, admin.get("/api/v1/auth/me").status);
    HttpResponse<String> replay =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/me"))
                    .header("Cookie", oldCookie)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(401, replay.statusCode());
    Client bad = new Client();
    Reply failed = bad.login(unique("missing"), "incorrect-password");
    assertEquals(401, failed.status);
    assertEquals("UNAUTHENTICATED", failed.body.path("code").asText());
  }

  @Test
  void readonlyUserCannotReadOrWriteSystemAdministration() throws Exception {
    Client viewer = new Client();
    assertEquals(200, viewer.login("viewer", secret("MFG_VIEWER_PASSWORD")).status);
    assertEquals(403, viewer.get("/api/v1/system/users").status);
    assertEquals(
        403,
        viewer.send(
                "POST",
                "/api/v1/system/roles",
                Map.of("code", unique("forbid"), "name", "禁止", "description", ""),
                true)
            .status);
    String viewerId = viewer.get("/api/v1/auth/me").body.path("id").asText();
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from operation_log where success=false and actor_id=?",
                Long.class,
                Long.parseLong(viewerId))
            > 0,
        "Denied operation must retain authenticated actor identity");
    assertEquals(403, viewer.get("/v3/api-docs").status);
  }

  @Test
  void roleGrantRevocationAndUserDisableAffectExistingSessionImmediately() throws Exception {
    Client admin = admin();
    JsonNode role = createRole(admin);
    String roleId = role.path("id").asText();
    role = changePermissions(admin, role, List.of("system:user:read"));
    JsonNode user = createUser(admin, List.of(roleId));
    Client member = new Client();
    assertEquals(
        200, member.login(user.path("username").asText(), secret("MFG_OPERATOR_PASSWORD")).status);
    assertEquals(200, member.get("/api/v1/system/users").status);
    role = changePermissions(admin, role, List.of());
    assertEquals(403, member.get("/api/v1/system/users").status);
    assertTrue(member.get("/api/v1/auth/permissions").body.path("permissions").isEmpty());
    changePermissions(admin, role, List.of("system:user:read"));
    assertEquals(200, member.get("/api/v1/system/users").status);
    Reply disabled =
        admin.send(
            "PUT",
            "/api/v1/system/users/" + user.path("id").asText(),
            Map.of(
                "version", user.path("version").asLong(), "displayName", "停用测试", "enabled", false),
            true);
    assertEquals(200, disabled.status);
    assertEquals(401, member.get("/api/v1/auth/me").status);
    assertEquals(
        401,
        new Client().login(user.path("username").asText(), secret("MFG_OPERATOR_PASSWORD")).status);
  }

  @Test
  void userRoleReplacementAndPasswordResetInvalidateCurrentAuthorization() throws Exception {
    Client admin = admin();
    JsonNode role = changePermissions(admin, createRole(admin), List.of("system:user:read"));
    JsonNode user = createUser(admin, List.of(role.path("id").asText()));
    String id = user.path("id").asText();
    Client member = new Client();
    assertEquals(
        200, member.login(user.path("username").asText(), secret("MFG_OPERATOR_PASSWORD")).status);
    Reply roles =
        admin.send(
            "PUT",
            "/api/v1/system/users/" + id + "/roles",
            Map.of("version", user.path("version").asLong(), "roleIds", List.of()),
            true);
    assertEquals(200, roles.status);
    assertEquals(403, member.get("/api/v1/system/users").status);
    Reply reset =
        admin.send(
            "POST",
            "/api/v1/system/users/" + id + "/reset-password",
            Map.of(
                "version",
                roles.body.path("version").asLong(),
                "newPassword",
                secret("MFG_VIEWER_PASSWORD")),
            true);
    assertEquals(204, reset.status);
    assertEquals(401, member.get("/api/v1/auth/me").status);
    assertEquals(
        200,
        new Client().login(user.path("username").asText(), secret("MFG_VIEWER_PASSWORD")).status);
  }

  @Test
  void bootstrapIsIdempotentAndPreservesExistingAccountAndRoleData() throws Exception {
    Client admin = admin();
    JsonNode customRole = changePermissions(admin, createRole(admin), List.of("system:user:read"));
    String previousHash =
        jdbc.queryForObject(
            "select password_hash from sys_user where username='admin'", String.class);
    long users = jdbc.queryForObject("select count(*) from sys_user", Long.class);
    long grants = jdbc.queryForObject("select count(*) from sys_role_permission", Long.class);
    for (ApplicationRunner runner : application.getBeansOfType(ApplicationRunner.class).values()) {
      runner.run(new DefaultApplicationArguments(new String[0]));
      runner.run(new DefaultApplicationArguments(new String[0]));
    }
    assertEquals(users, jdbc.queryForObject("select count(*) from sys_user", Long.class));
    assertEquals(
        grants, jdbc.queryForObject("select count(*) from sys_role_permission", Long.class));
    assertTrue(
        previousHash.equals(
            jdbc.queryForObject(
                "select password_hash from sys_user where username='admin'", String.class)));
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from sys_user where username='admin'", Integer.class));
    assertEquals(34, jdbc.queryForObject("select count(*) from sys_permission", Integer.class));
    assertEquals(
        1,
        admin
            .get("/api/v1/system/roles/" + customRole.path("id").asText())
            .body
            .path("permissionCodes")
            .size());
  }

  @Test
  void failedLoginCounterHasExpiryAndRejectsSixthAttempt() throws Exception {
    Client guest = new Client();
    String username = unique("rate");
    for (int i = 0; i < 5; i++) assertEquals(401, guest.login(username, "invalid-password").status);
    Reply limited = guest.login(username, "invalid-password");
    assertEquals(429, limited.status);
    assertEquals("LOGIN_RATE_LIMITED", limited.body.path("code").asText());
    assertTrue(Integer.parseInt(limited.raw.headers().firstValue("Retry-After").orElse("0")) > 0);
    boolean expiringCounter = false;
    try (var keys =
        redis.scan(ScanOptions.scanOptions().match("light-mfg:test:login-limit:*").build())) {
      while (keys.hasNext()) {
        Long ttl = redis.getExpire(keys.next());
        if (ttl != null && ttl > 0 && ttl <= 900) expiringCounter = true;
      }
    }
    assertTrue(expiringCounter);
  }

  @Test
  void validationAndUnknownFieldsNeverExposePasswordsOrDatabaseErrors() throws Exception {
    Client admin = admin();
    Reply invalid =
        admin.send(
            "POST",
            "/api/v1/system/users",
            Map.of(
                "username",
                unique("bad"),
                "displayName",
                "错误输入",
                "initialPassword",
                "short",
                "enabled",
                true,
                "roleIds",
                List.of()),
            true);
    assertEquals(400, invalid.status);
    String body = invalid.raw.body().toLowerCase();
    assertFalse(body.contains("short"));
    assertFalse(body.contains("stacktrace"));
    assertFalse(body.contains("sqlstate"));
    assertFalse(body.contains("password_hash"));
    Reply unknown =
        admin.send(
            "PUT",
            "/api/v1/system/users/1",
            Map.of("version", 0, "displayName", "不应执行", "enabled", true, "passwordHash", "forged"),
            true);
    assertEquals(400, unknown.status);
  }

  @Test
  void builtinAdminAndReferencedRolesCannotBeRemoved() throws Exception {
    Client admin = admin();
    JsonNode roles = admin.get("/api/v1/system/roles?q=ADMIN&size=100").body.path("items");
    JsonNode administrator = null;
    for (JsonNode role : roles)
      if (role.path("code").asText().equals("ADMIN")) administrator = role;
    assertNotNull(administrator);
    Reply removed =
        admin.send(
            "PUT",
            "/api/v1/system/roles/" + administrator.path("id").asText() + "/permissions",
            Map.of("version", administrator.path("version").asLong(), "permissionCodes", List.of()),
            true);
    assertEquals(409, removed.status);
    JsonNode myself =
        admin.get("/api/v1/system/users/" + admin.get("/api/v1/auth/me").body.path("id").asText())
            .body;
    assertEquals(
        409,
        admin.send(
                "PUT",
                "/api/v1/system/users/" + myself.path("id").asText(),
                Map.of(
                    "version",
                    myself.path("version").asLong(),
                    "displayName",
                    "管理员",
                    "enabled",
                    false),
                true)
            .status);
    JsonNode custom = createRole(admin);
    JsonNode user = createUser(admin, List.of(custom.path("id").asText()));
    assertFalse(user.path("id").asText().isEmpty());
    assertEquals(
        409,
        admin.send(
                "DELETE",
                "/api/v1/system/roles/"
                    + custom.path("id").asText()
                    + "?version="
                    + custom.path("version").asLong(),
                null,
                true)
            .status);
  }

  @Test
  void logsRecordActionsAndPaginationUsesSafeDtos() throws Exception {
    Client admin = admin();
    JsonNode role = createRole(admin);
    assertFalse(role.path("id").asText().isEmpty());
    Reply users = admin.get("/api/v1/system/users?page=1&size=2");
    assertEquals(200, users.status);
    assertEquals(2, users.body.path("items").size());
    assertTrue(users.body.path("total").isTextual());
    assertTrue(users.body.path("items").get(0).path("id").isTextual());
    Reply logs = admin.get("/api/v1/system/operation-logs?page=1&size=100");
    assertEquals(200, logs.status);
    assertFalse(logs.body.path("items").isEmpty());
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from operation_log where action='LOGIN' and success=true",
                Long.class)
            > 0);
    String wire = logs.raw.body();
    assertFalse(wire.contains(secret("MFG_ADMIN_PASSWORD")));
    assertFalse(wire.contains("passwordHash"));
  }

  private JsonNode createRole(Client admin) throws Exception {
    Reply reply =
        admin.send(
            "POST",
            "/api/v1/system/roles",
            Map.of(
                "code",
                unique("role").toUpperCase(java.util.Locale.ROOT),
                "name",
                "集成测试角色",
                "description",
                "仅本次测试"),
            true);
    assertEquals(201, reply.status, () -> reply.body.path("code").asText());
    return reply.body;
  }

  private JsonNode changePermissions(Client admin, JsonNode role, List<String> permissions)
      throws Exception {
    Reply reply =
        admin.send(
            "PUT",
            "/api/v1/system/roles/" + role.path("id").asText() + "/permissions",
            Map.of("version", role.path("version").asLong(), "permissionCodes", permissions),
            true);
    assertEquals(200, reply.status, () -> reply.body.path("code").asText());
    return reply.body;
  }

  private JsonNode createUser(Client admin, List<String> roleIds) throws Exception {
    Reply reply =
        admin.send(
            "POST",
            "/api/v1/system/users",
            Map.of(
                "username",
                unique("user"),
                "displayName",
                "集成测试用户",
                "initialPassword",
                secret("MFG_OPERATOR_PASSWORD"),
                "enabled",
                true,
                "roleIds",
                roleIds),
            true);
    assertEquals(201, reply.status, () -> reply.body.path("code").asText());
    return reply.body;
  }

  private Client admin() throws Exception {
    Client client = new Client();
    Reply login = client.login("admin", secret("MFG_ADMIN_PASSWORD"));
    assertEquals(200, login.status, () -> login.body.path("code").asText());
    return client;
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private String unique(String prefix) {
    return "it_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private String secret(String name) {
    String value = System.getenv(name);
    assertNotNull(value, "Load the project's private test environment first.");
    return value;
  }

  private record Reply(int status, JsonNode body, HttpResponse<String> raw) {}

  private final class Client {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    String csrf;

    Reply get(String path) throws Exception {
      return send("GET", path, null, false);
    }

    Reply login(String username, String password) throws Exception {
      csrf = get("/api/v1/auth/csrf").body.path("token").asText();
      Reply response =
          send(
              "POST",
              "/api/v1/auth/login",
              Map.of("username", username, "password", password),
              true);
      if (response.status == 200) csrf = get("/api/v1/auth/csrf").body.path("token").asText();
      return response;
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("LIGHT_MFG_SESSION"))
          .map(c -> c.getName() + "=" + c.getValue())
          .findFirst()
          .orElseThrow();
    }

    Reply send(String method, String path, Object body, boolean token) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base() + path))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/json");
      if (token) {
        if (csrf == null) csrf = get("/api/v1/auth/csrf").body.path("token").asText();
        request.header("X-CSRF-TOKEN", csrf);
      }
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode node =
          response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
      return new Reply(response.statusCode(), node, response);
    }
  }
}
