package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Reusable computer-assembly acceptance through real HTTP, MySQL and Redis. No outer rollback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Stage4BusinessIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired io.github.lightmanufacturing.erp.application.PurchaseOrderService purchases;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  Client admin;
  List<String> raw;
  String product, rawWarehouse, finishedWarehouse, supplier, customer, bom;

  @BeforeEach
  void setup() throws Exception {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    admin = new Client();
    admin.login("admin", System.getenv("MFG_ADMIN_PASSWORD"));
    raw = new ArrayList<>();
    for (String name : List.of("CPU", "主板", "内存条", "SSD", "电源", "机箱"))
      raw.add(material(name, "RAW_MATERIAL"));
    product = material("标准办公台式机", "FINISHED_GOOD");
    rawWarehouse =
        create(
                "/warehouses",
                Map.of("code", code(), "name", "零部件仓", "address", "", "enabled", true))
            .path("id")
            .asText();
    finishedWarehouse =
        create("/warehouses", Map.of("code", code(), "name", "整机仓", "address", "", "enabled", true))
            .path("id")
            .asText();
    supplier =
        create(
                "/suppliers",
                Map.of(
                    "code",
                    code(),
                    "name",
                    "电脑零部件供应商",
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
    List<Map<String, String>> items = new ArrayList<>();
    for (int i = 0; i < 6; i++)
      items.add(Map.of("materialId", raw.get(i), "quantityPer", i == 2 ? "2" : "1"));
    bom =
        create(
                "/boms",
                Map.of(
                    "code",
                    code(),
                    "name",
                    "标准办公台式机BOM",
                    "productMaterialId",
                    product,
                    "enabled",
                    true,
                    "items",
                    items))
            .path("id")
            .asText();
  }

  String code() {
    return "S4-" + UUID.randomUUID();
  }

  String material(String name, String type) throws Exception {
    return create(
            "/materials",
            Map.of(
                "code",
                code(),
                "name",
                name,
                "type",
                type,
                "unit",
                type.equals("FINISHED_GOOD") ? "台" : "件",
                "specification",
                "",
                "minStock",
                "0",
                "enabled",
                true))
        .path("id")
        .asText();
  }

  JsonNode create(String path, Object body) throws Exception {
    return admin.expect("POST", path, body, 201);
  }

  JsonNode detail(String path) throws Exception {
    return admin.expect("GET", path, null, 200);
  }

  JsonNode action(String path, String action) throws Exception {
    long version = detail(path).path("version").asLong();
    return admin.expect("POST", path + "/" + action, Map.of("version", version), 200);
  }

  String purchase() throws Exception {
    List<Map<String, String>> items = new ArrayList<>();
    for (int i = 0; i < 6; i++)
      items.add(
          Map.of("materialId", raw.get(i), "quantity", i == 2 ? "40" : "20", "unitPrice", "10.00"));
    var p =
        create(
            "/purchase-orders",
            Map.of(
                "supplierId",
                supplier,
                "warehouseId",
                rawWarehouse,
                "remark",
                "电脑零部件采购",
                "items",
                items));
    assertEquals("1400.00", p.path("totalAmount").asText());
    return "/purchase-orders/" + p.path("id").asText();
  }

  String production(String quantity) throws Exception {
    return "/production-orders/"
        + create(
                "/production-orders",
                Map.of(
                    "productMaterialId",
                    product,
                    "bomId",
                    bom,
                    "plannedQuantity",
                    quantity,
                    "issueWarehouseId",
                    rawWarehouse,
                    "receiptWarehouseId",
                    finishedWarehouse,
                    "remark",
                    "标准办公台式机组装"))
            .path("id")
            .asText();
  }

  String sale(String quantity) throws Exception {
    return "/sales-orders/"
        + create(
                "/sales-orders",
                Map.of(
                    "customerId",
                    customer,
                    "warehouseId",
                    finishedWarehouse,
                    "remark",
                    "整机销售",
                    "items",
                    List.of(
                        Map.of(
                            "materialId", product, "quantity", quantity, "unitPrice", "1200.50"))))
            .path("id")
            .asText();
  }

  String balance(String warehouse, String material) {
    var rows =
        jdbc.queryForList(
            "select quantity from stock_balance where warehouse_id=? and material_id=?",
            java.math.BigDecimal.class,
            warehouse,
            material);
    return rows.isEmpty() ? "0" : rows.get(0).toPlainString();
  }

  void reconcile() {
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_balance b where b.warehouse_id in(?,?) and"
                + " b.quantity<>(select coalesce(sum(l.change_quantity),0) from stock_ledger l"
                + " where l.balance_id=b.id)",
            Integer.class,
            rawWarehouse,
            finishedWarehouse));
  }

  @Test
  void completeComputerAssemblyTenThenSellThreeAndReplayEveryAction() throws Exception {
    String p = purchase();
    var pc = action(p, "confirm");
    var pr = action(p, "receive");
    assertEquals("RECEIVED", pr.path("document").path("status").asText());
    String mo = production("10");
    var confirmed = action(mo, "confirm");
    assertEquals(6, confirmed.path("document").path("materialSnapshot").size());
    var bomDetail = detail("/boms/" + bom);
    List<Map<String, String>> revised = new ArrayList<>();
    for (int i = 0; i < 6; i++)
      revised.add(Map.of("materialId", raw.get(i), "quantityPer", i == 2 ? "3" : "1"));
    admin.expect(
        "PUT",
        "/boms/" + bom,
        Map.of(
            "version",
            bomDetail.path("version").asLong(),
            "code",
            bomDetail.path("code").asText(),
            "name",
            "仅影响后续工单",
            "productMaterialId",
            product,
            "enabled",
            true,
            "items",
            revised),
        200);
    var preview = detail(mo + "/material-requirements");
    assertFalse(preview.path("hasShortage").asBoolean());
    var issued = action(mo, "issue");
    assertEquals("IN_PRODUCTION", issued.path("document").path("status").asText());
    for (int i = 0; i < 6; i++)
      assertEquals(i == 2 ? "20" : "10", balance(rawWarehouse, raw.get(i)));
    var reported = action(mo, "report");
    assertEquals("PENDING_INBOUND", reported.path("document").path("status").asText());
    assertEquals("0", balance(finishedWarehouse, product));
    var receipt =
        reported.path("relatedDocuments").findValues("kind").isEmpty()
            ? null
            : reported.path("relatedDocuments");
    assertNotNull(receipt);
    String receiptId = "";
    for (var d : receipt)
      if (d.path("kind").asText().equals("PRODUCTION_RECEIPT")) {
        assertEquals("PENDING", d.path("status").asText());
        receiptId = d.path("id").asText();
      }
    assertFalse(receiptId.isBlank());
    var executed = action("/stock-documents/" + receiptId, "execute");
    assertEquals("COMPLETED", executed.path("relatedProductionOrder").path("status").asText());
    assertEquals("10", balance(finishedWarehouse, product));
    String so = sale("3");
    assertEquals("3601.50", detail(so).path("totalAmount").asText());
    var sc = action(so, "confirm");
    var shipped = action(so, "ship");
    assertEquals("7", balance(finishedWarehouse, product));
    assertEquals(
        8,
        jdbc.queryForObject(
            "select count(*) from command_record where (resource_type='PURCHASE_ORDER' and"
                + " resource_id=?) or (resource_type='PRODUCTION_ORDER' and resource_id=?) or"
                + " (resource_type='SALES_ORDER' and resource_id=?) or"
                + " (resource_type='STOCK_DOCUMENT' and resource_id=?)",
            Integer.class,
            p.substring(p.lastIndexOf('/') + 1),
            mo.substring(mo.lastIndexOf('/') + 1),
            so.substring(so.lastIndexOf('/') + 1),
            receiptId));
    for (var replay :
        List.of(
            Map.entry(p + "/confirm", pc),
            Map.entry(p + "/receive", pr),
            Map.entry(mo + "/confirm", confirmed),
            Map.entry(mo + "/issue", issued),
            Map.entry(mo + "/report", reported),
            Map.entry("/stock-documents/" + receiptId + "/execute", executed),
            Map.entry(so + "/confirm", sc),
            Map.entry(so + "/ship", shipped))) {
      var result = admin.expect("POST", replay.getKey(), Map.of("version", 0), 200);
      assertTrue(result.path("replayed").asBoolean());
      assertEquals(
          replay.getValue().path("operationId").asText(), result.path("operationId").asText());
    }
    assertEquals("7", balance(finishedWarehouse, product));
    assertEquals(
        14,
        jdbc.queryForObject(
            "select count(*) from stock_ledger where warehouse_id in(?,?)",
            Integer.class,
            rawWarehouse,
            finishedWarehouse));
    reconcile();
  }

  @Test
  void invalidStatesMissingMaterialsAndConfirmedEditsAreRejected() throws Exception {
    String mo = production("10");
    assertEquals(
        "INVALID_STATE",
        admin.expect("POST", mo + "/report", Map.of("version", 0), 409).path("code").asText());
    action(mo, "confirm");
    var preview = detail(mo + "/material-requirements");
    assertTrue(preview.path("hasShortage").asBoolean());
    assertEquals(
        "INSUFFICIENT_STOCK",
        admin
            .expect(
                "POST", mo + "/issue", Map.of("version", detail(mo).path("version").asLong()), 409)
            .path("code")
            .asText());
    assertEquals("READY_TO_ISSUE", detail(mo).path("status").asText());
    admin.expect(
        "POST", mo + "/cancel", Map.of("version", detail(mo).path("version").asLong()), 409);
    admin.expect("DELETE", mo + "?version=" + detail(mo).path("version").asLong(), null, 409);
    admin.expect(
        "PUT",
        mo,
        Map.of(
            "version",
            detail(mo).path("version").asLong(),
            "productMaterialId",
            product,
            "bomId",
            bom,
            "plannedQuantity",
            "11",
            "issueWarehouseId",
            rawWarehouse,
            "receiptWarehouseId",
            finishedWarehouse,
            "remark",
            "不可变更"),
        409);
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_document where source_type='PRODUCTION_ORDER' and"
                + " source_id=?",
            Integer.class,
            mo.substring(mo.lastIndexOf('/') + 1)));
    reconcile();
  }

  void manufacture() throws Exception {
    String p = purchase();
    action(p, "confirm");
    action(p, "receive");
    String mo = production("10");
    action(mo, "confirm");
    action(mo, "issue");
    var report = action(mo, "report");
    for (var d : report.path("relatedDocuments"))
      if (d.path("kind").asText().equals("PRODUCTION_RECEIPT"))
        action("/stock-documents/" + d.path("id").asText(), "execute");
  }

  @Test
  void multiLineSaleFailureRollsBackAllStockAndSourceState() throws Exception {
    manufacture();
    String unavailable = material("未生产的整机", "FINISHED_GOOD");
    String so =
        "/sales-orders/"
            + create(
                    "/sales-orders",
                    Map.of(
                        "customerId",
                        customer,
                        "warehouseId",
                        finishedWarehouse,
                        "remark",
                        "整单回滚",
                        "items",
                        List.of(
                            Map.of("materialId", product, "quantity", "2", "unitPrice", "1.00"),
                            Map.of(
                                "materialId", unavailable, "quantity", "1", "unitPrice", "1.00"))))
                .path("id")
                .asText();
    action(so, "confirm");
    admin.expect("POST", so + "/ship", Map.of("version", detail(so).path("version").asLong()), 409);
    assertEquals("CONFIRMED", detail(so).path("status").asText());
    assertEquals("10", balance(finishedWarehouse, product));
    assertEquals("0", balance(finishedWarehouse, unavailable));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_document where source_type='SALES_ORDER' and source_id=?",
            Integer.class,
            so.substring(so.lastIndexOf('/') + 1)));
    reconcile();
  }

  @Test
  void concurrentSalesCannotOvershipAndDuplicateReceiptCommitsOnlyOnce() throws Exception {
    String p = purchase();
    action(p, "confirm");
    var duplicate =
        race(
            p + "/receive",
            p + "/receive",
            detail(p).path("version").asLong(),
            detail(p).path("version").asLong());
    assertEquals(List.of(200, 200), duplicate.stream().sorted().toList());
    for (int i = 0; i < 6; i++)
      assertEquals(i == 2 ? "40" : "20", balance(rawWarehouse, raw.get(i)));
    String mo = production("10");
    action(mo, "confirm");
    action(mo, "issue");
    var report = action(mo, "report");
    for (var d : report.path("relatedDocuments"))
      if (d.path("kind").asText().equals("PRODUCTION_RECEIPT"))
        action("/stock-documents/" + d.path("id").asText(), "execute");
    String first = sale("7"), second = sale("7");
    action(first, "confirm");
    action(second, "confirm");
    var statuses =
        race(
            first + "/ship",
            second + "/ship",
            detail(first).path("version").asLong(),
            detail(second).path("version").asLong());
    assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
    assertEquals("3", balance(finishedWarehouse, product));
    reconcile();
  }

  @Test
  void businessWritesNeedPermissionAndCannotInjectStateOrAmounts() throws Exception {
    var guest = new Client();
    assertEquals(401, guest.send("GET", "/production-orders", null, true).statusCode());
    var viewer = new Client();
    viewer.login("viewer", System.getenv("MFG_VIEWER_PASSWORD"));
    viewer.expect("GET", "/production-orders", null, 200);
    viewer.expect(
        "POST",
        "/production-orders",
        Map.of(
            "productMaterialId",
            product,
            "bomId",
            bom,
            "plannedQuantity",
            "10",
            "issueWarehouseId",
            rawWarehouse,
            "receiptWarehouseId",
            finishedWarehouse,
            "remark",
            ""),
        403);
    Map<String, Object> body =
        new HashMap<>(
            Map.of(
                "productMaterialId",
                product,
                "bomId",
                bom,
                "plannedQuantity",
                "10",
                "issueWarehouseId",
                rawWarehouse,
                "receiptWarehouseId",
                finishedWarehouse,
                "remark",
                ""));
    body.put("status", "COMPLETED");
    admin.expect("POST", "/production-orders", body, 400);
    body.remove("status");
    assertEquals(403, admin.send("POST", "/production-orders", body, false).statusCode());
    String so = sale("11");
    admin.expect("POST", so + "/confirm", Map.of("version", 9007199254740992L), 400);
    action(so, "confirm");
    admin.expect("POST", so + "/ship", Map.of("version", detail(so).path("version").asLong()), 409);
    assertEquals("0", balance(finishedWarehouse, product));
  }

  @Test
  void concurrentProductionActionsCreateOnlyOneSnapshotIssueReportAndReceipt() throws Exception {
    String p = purchase();
    action(p, "confirm");
    action(p, "receive");
    String mo = production("10");
    for (String operation : List.of("confirm", "issue", "report")) {
      long version = detail(mo).path("version").asLong();
      assertEquals(
          List.of(200, 200),
          race(mo + "/" + operation, mo + "/" + operation, version, version).stream()
              .sorted()
              .toList());
    }
    assertEquals("0", balance(finishedWarehouse, product));
    String receiptId = "";
    for (var d : detail(mo).path("stockDocuments"))
      if (d.path("kind").asText().equals("PRODUCTION_RECEIPT")) receiptId = d.path("id").asText();
    assertFalse(receiptId.isBlank());
    long version = detail("/stock-documents/" + receiptId).path("version").asLong();
    assertEquals(
        List.of(200, 200),
        race(
                "/stock-documents/" + receiptId + "/execute",
                "/stock-documents/" + receiptId + "/execute",
                version,
                version)
            .stream()
            .sorted()
            .toList());
    String id = mo.substring(mo.lastIndexOf('/') + 1);
    assertEquals(
        6,
        jdbc.queryForObject(
            "select count(*) from production_material_snapshot where production_order_id=?",
            Integer.class,
            id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from production_report where production_order_id=?",
            Integer.class,
            id));
    assertEquals("COMPLETED", detail(mo).path("status").asText());
    assertEquals("10", balance(finishedWarehouse, product));
    assertEquals(
        13,
        jdbc.queryForObject(
            "select count(*) from stock_ledger where warehouse_id in(?,?)",
            Integer.class,
            rawWarehouse,
            finishedWarehouse));
    reconcile();
  }

  @Test
  void successfulPreviewDoesNotReserveStockAndIssueRechecks() throws Exception {
    String p = purchase();
    action(p, "confirm");
    action(p, "receive");
    String first = production("11"), second = production("10");
    action(first, "confirm");
    action(second, "confirm");
    assertFalse(detail(second + "/material-requirements").path("hasShortage").asBoolean());
    action(first, "issue");
    admin.expect(
        "POST", second + "/issue", Map.of("version", detail(second).path("version").asLong()), 409);
    assertEquals("READY_TO_ISSUE", detail(second).path("status").asText());
    for (int i = 0; i < 6; i++)
      assertEquals(i == 2 ? "18" : "9", balance(rawWarehouse, raw.get(i)));
    reconcile();
  }

  @Test
  void outerFailureAfterSuccessfulReceiptRollsBackStateStockCommandsAndAudit() throws Exception {
    String p = purchase();
    action(p, "confirm");
    var header = detail(p);
    long id = header.path("id").asLong(), version = header.path("version").asLong();
    long actor = jdbc.queryForObject("select id from sys_user where username='admin'", Long.class),
        authVersion =
            jdbc.queryForObject("select auth_version from sys_user where id=?", Long.class, actor);
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated(
                new io.github.lightmanufacturing.system.security.SessionPrincipal(
                    actor, authVersion),
                null,
                List.of()));
    org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                  .execute(
                      tx -> {
                        purchases.receive(id, version);
                        throw new IllegalStateException("caller fails after all receipt effects");
                      }));
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    assertEquals("CONFIRMED", detail(p).path("status").asText());
    assertEquals(version, detail(p).path("version").asLong());
    assertEquals("0", balance(rawWarehouse, raw.get(0)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from command_record where resource_type='PURCHASE_ORDER' and"
                + " resource_id=? and action='RECEIVE'",
            Integer.class,
            id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from operation_log where resource_type='PURCHASE_ORDER' and"
                + " resource_id=? and action='RECEIVE' and success=true",
            Integer.class,
            id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_document where source_type='PURCHASE_ORDER' and"
                + " source_id=?",
            Integer.class,
            id));
    action(p, "receive");
    assertEquals("20", balance(rawWarehouse, raw.get(0)));
    reconcile();
  }

  @Test
  void productionDraftEditCancelDeleteAndReceiptSourceAreRestricted() throws Exception {
    String mo = production("10");
    var h = detail(mo);
    Map<String, Object> edit =
        new HashMap<>(
            Map.of(
                "version",
                h.path("version").asLong(),
                "productMaterialId",
                product,
                "bomId",
                bom,
                "plannedQuantity",
                "12",
                "issueWarehouseId",
                rawWarehouse,
                "receiptWarehouseId",
                finishedWarehouse,
                "remark",
                "修改计划"));
    var updated = admin.expect("PUT", mo, edit, 200);
    assertEquals("12", updated.path("plannedQuantity").asText());
    admin.expect("PUT", mo, edit, 409);
    var cancelled = action(mo, "cancel");
    assertEquals("CANCELLED", cancelled.path("document").path("status").asText());
    assertTrue(
        admin
            .expect("POST", mo + "/cancel", Map.of("version", 0), 200)
            .path("replayed")
            .asBoolean());
    admin.expect(
        "POST", mo + "/confirm", Map.of("version", detail(mo).path("version").asLong()), 409);
    String deleted = production("1");
    admin.expect("DELETE", deleted + "?version=0", null, 204);
    admin.expect("GET", deleted, null, 404);
    String p = purchase();
    action(p, "confirm");
    var received = action(p, "receive");
    String stockId = received.path("relatedDocuments").get(0).path("id").asText();
    admin.expect("POST", "/stock-documents/" + stockId + "/execute", Map.of("version", 1), 409);
    assertEquals("20", balance(rawWarehouse, raw.get(0)));
    reconcile();
  }

  List<Integer> race(String a, String b, long av, long bv) throws Exception {
    var first = new Client();
    first.login("admin", System.getenv("MFG_ADMIN_PASSWORD"));
    var second = new Client();
    second.login("admin", System.getenv("MFG_ADMIN_PASSWORD"));
    var start = new CountDownLatch(1);
    var ready = new CountDownLatch(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var f =
          pool.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return first.send("POST", a, Map.of("version", av), true).statusCode();
              });
      var s =
          pool.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return second.send("POST", b, Map.of("version", bv), true).statusCode();
              });
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      return List.of(f.get(20, TimeUnit.SECONDS), s.get(20, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
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
