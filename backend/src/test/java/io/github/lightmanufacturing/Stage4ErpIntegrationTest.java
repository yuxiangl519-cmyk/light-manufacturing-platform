package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Real HTTP ERP boundary checks. No shared test resource is run concurrently. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Stage4ErpIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  Client admin;
  String raw, product, supplier, customer, warehouse;

  @BeforeEach
  void fixtures() throws Exception {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    admin = new Client();
    admin.login("admin", System.getenv("MFG_ADMIN_PASSWORD"));
    raw =
        create(
                "/materials",
                Map.of(
                    "code",
                    code(),
                    "name",
                    "电脑CPU",
                    "type",
                    "RAW_MATERIAL",
                    "unit",
                    "件",
                    "specification",
                    "",
                    "minStock",
                    "0",
                    "enabled",
                    true))
            .path("id")
            .asText();
    product =
        create(
                "/materials",
                Map.of(
                    "code",
                    code(),
                    "name",
                    "标准办公台式机",
                    "type",
                    "FINISHED_GOOD",
                    "unit",
                    "台",
                    "specification",
                    "",
                    "minStock",
                    "0",
                    "enabled",
                    true))
            .path("id")
            .asText();
    supplier =
        create(
                "/suppliers",
                Map.of(
                    "code",
                    code(),
                    "name",
                    "电脑供应商",
                    "contactName",
                    "",
                    "phone",
                    "",
                    "address",
                    "",
                    "enabled",
                    true))
            .path("id")
            .asText();
    customer =
        create(
                "/customers",
                Map.of(
                    "code",
                    code(),
                    "name",
                    "办公电脑客户",
                    "contactName",
                    "",
                    "phone",
                    "",
                    "address",
                    "",
                    "enabled",
                    true))
            .path("id")
            .asText();
    warehouse =
        create(
                "/warehouses",
                Map.of("code", code(), "name", "电脑工厂测试仓", "address", "", "enabled", true))
            .path("id")
            .asText();
  }

  String code() {
    return "ERP-" + UUID.randomUUID();
  }

  JsonNode create(String path, Object body) throws Exception {
    return admin.expect("POST", path, body, 201);
  }

  Map<String, Object> purchase(String material, String qty, String price) {
    return new HashMap<>(
        Map.of(
            "supplierId",
            supplier,
            "warehouseId",
            warehouse,
            "remark",
            "电脑采购",
            "items",
            List.of(Map.of("materialId", material, "quantity", qty, "unitPrice", price))));
  }

  @Test
  void rejectsMoneyOverflowDuplicateLinesWrongKindsAndUnknownFields() throws Exception {
    admin.expect("POST", "/purchase-orders", purchase(raw, "1", "1.001"), 400);
    admin.expect("POST", "/purchase-orders", purchase(raw, "2", "9999999999999999.99"), 400);
    admin.expect("POST", "/purchase-orders", purchase(product, "1", "1"), 400);
    var duplicate = purchase(raw, "1", "1");
    duplicate.put(
        "items",
        List.of(
            Map.of("materialId", raw, "quantity", "1", "unitPrice", "1"),
            Map.of("materialId", raw, "quantity", "2", "unitPrice", "1")));
    admin.expect("POST", "/purchase-orders", duplicate, 400);
    var injected = purchase(raw, "1", "1");
    injected.put("totalAmount", "0.01");
    admin.expect("POST", "/purchase-orders", injected, 400);
    injected.remove("totalAmount");
    injected.put("status", "RECEIVED");
    admin.expect("POST", "/purchase-orders", injected, 400);
    var numeric = purchase(raw, "1", "1");
    numeric.put("supplierId", Long.parseLong(supplier));
    admin.expect("POST", "/purchase-orders", numeric, 400);
    var sale =
        new HashMap<>(
            Map.of(
                "customerId",
                customer,
                "warehouseId",
                warehouse,
                "remark",
                "",
                "items",
                List.of(Map.of("materialId", raw, "quantity", "1", "unitPrice", "1"))));
    admin.expect("POST", "/sales-orders", sale, 400);
  }

  @Test
  void draftUpdateRequiresVersionCancelReplaysAndLogicalDeleteHidesOrder() throws Exception {
    var order = create("/purchase-orders", purchase(raw, "3", "12.30"));
    String path = "/purchase-orders/" + order.path("id").asText();
    assertEquals("36.90", order.path("totalAmount").asText());
    var update = purchase(raw, "4", "12.30");
    admin.expect("PUT", path, update, 400);
    update.put("version", 0);
    var updated = admin.expect("PUT", path, update, 200);
    assertEquals("49.20", updated.path("totalAmount").asText());
    admin.expect("PUT", path, update, 409);
    var cancelled =
        admin.expect(
            "POST", path + "/cancel", Map.of("version", updated.path("version").asLong()), 200);
    var replay = admin.expect("POST", path + "/cancel", Map.of("version", 0), 200);
    assertTrue(replay.path("replayed").asBoolean());
    assertEquals(cancelled.path("operationId"), replay.path("operationId"));
    admin.expect(
        "DELETE", path + "?version=" + replay.path("document").path("version").asLong(), null, 409);
    var other = create("/purchase-orders", purchase(raw, "1", "0"));
    String otherPath = "/purchase-orders/" + other.path("id").asText();
    admin.expect("DELETE", otherPath + "?version=0", null, 204);
    admin.expect("GET", otherPath, null, 404);
    var page =
        admin.expect("GET", "/purchase-orders?supplierId=" + supplier + "&status=DRAFT", null, 200);
    assertEquals(0, page.path("items").size());
  }

  @Test
  void confirmationFreezesSnapshotsAndRejectsDisabledReferences() throws Exception {
    var order = create("/purchase-orders", purchase(raw, "2", "10"));
    String path = "/purchase-orders/" + order.path("id").asText();
    var material = admin.expect("GET", "/materials/" + raw, null, 200);
    var change = new HashMap<String, Object>();
    for (String f : List.of("code", "name", "type", "unit", "specification", "minStock"))
      change.put(f, material.path(f).asText());
    change.put("version", material.path("version").asLong());
    change.put("enabled", false);
    var disabled = admin.expect("PUT", "/materials/" + raw, change, 200);
    admin.expect("POST", path + "/confirm", Map.of("version", 0), 409);
    assertEquals("DRAFT", admin.expect("GET", path, null, 200).path("status").asText());
    change.put("version", disabled.path("version").asLong());
    change.put("enabled", true);
    change.put("name", "确认前CPU新名称");
    admin.expect("PUT", "/materials/" + raw, change, 200);
    var confirmed = admin.expect("POST", path + "/confirm", Map.of("version", 0), 200);
    assertEquals(
        "确认前CPU新名称", confirmed.path("document").path("items").get(0).path("materialName").asText());
    var update = purchase(raw, "4", "10");
    update.put("version", confirmed.path("document").path("version").asLong());
    admin.expect("PUT", path, update, 409);
    var latest = admin.expect("GET", "/materials/" + raw, null, 200);
    change.put("version", latest.path("version").asLong());
    change.put("name", "确认后CPU名称");
    change.put("enabled", false);
    admin.expect("PUT", "/materials/" + raw, change, 200);
    var received =
        admin.expect(
            "POST",
            path + "/receive",
            Map.of("version", confirmed.path("document").path("version").asLong()),
            200);
    assertEquals(
        "确认前CPU新名称", received.path("document").path("items").get(0).path("materialName").asText());
    assertEquals("RECEIVED", received.path("document").path("status").asText());
  }

  @Test
  void permissionsAndCsrfProtectEveryBusinessWrite() throws Exception {
    var viewer = new Client();
    viewer.login("viewer", System.getenv("MFG_VIEWER_PASSWORD"));
    viewer.expect("GET", "/purchase-orders", null, 200);
    viewer.expect("POST", "/purchase-orders", purchase(raw, "1", "1"), 403);
    assertEquals(
        403, admin.send("POST", "/purchase-orders", purchase(raw, "1", "1"), false).statusCode());
    var guest = new Client();
    assertEquals(401, guest.send("GET", "/purchase-orders", null, true).statusCode());
  }

  class Client {
    HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String csrf;

    HttpResponse<String> send(String method, String path, Object body, boolean token)
        throws Exception {
      if (token && csrf == null) {
        var r = send("GET", "/auth/csrf", null, false);
        csrf = json.readTree(r.body()).path("token").asText();
      }
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
              .header("Content-Type", "application/json");
      if (token) request.header("X-CSRF-TOKEN", csrf);
      return http.send(
          request
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode expect(String method, String path, Object body, int status) throws Exception {
      var r = send(method, path, body, true);
      assertEquals(status, r.statusCode(), r.body());
      return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    void login(String username, String password) throws Exception {
      expect("POST", "/auth/login", Map.of("username", username, "password", password), 200);
      csrf = null;
    }
  }
}
