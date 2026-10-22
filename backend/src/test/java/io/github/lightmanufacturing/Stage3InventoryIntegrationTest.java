package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.lightmanufacturing.wms.application.InventoryService;
import io.github.lightmanufacturing.wms.domain.StockPosting;
import io.github.lightmanufacturing.wms.domain.StockPosting.Kind;
import java.math.BigDecimal;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL transactions: no test-managed outer rollback, each worker commits independently. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Stage3InventoryIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired InventoryService inventory;
  @Autowired PlatformTransactionManager manager;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;
  long warehouse, material, secondMaterial, actor;

  @BeforeEach
  void fixtures() {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    actor = jdbc.queryForObject("select id from sys_user where username='admin'", Long.class);
    String tag = UUID.randomUUID().toString();
    jdbc.update(
        "insert into warehouse(code,name,address,enabled,created_at,updated_at,version) values(?,?,"
            + " '',true,utc_timestamp(3),utc_timestamp(3),0)",
        "W" + tag,
        "库存测试仓");
    warehouse = jdbc.queryForObject("select id from warehouse where code=?", Long.class, "W" + tag);
    material = material("M" + tag);
    secondMaterial = material("N" + tag);
  }

  long material(String code) {
    jdbc.update(
        "insert into"
            + " material(code,name,type,unit,specification,min_stock,enabled,referenced,created_at,updated_at,version)"
            + " values(?,?,'RAW_MATERIAL','件','',0,true,false,utc_timestamp(3),utc_timestamp(3),0)",
        code,
        "电脑零部件");
    return jdbc.queryForObject("select id from material where code=?", Long.class, code);
  }

  StockPosting command(Kind kind, long source, long quantity) {
    return new StockPosting(
        kind,
        source,
        "TEST-" + source,
        warehouse,
        actor,
        List.of(new StockPosting.Line(source, material, BigDecimal.valueOf(quantity))));
  }

  long source() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1);
  }

  <T> T tx(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(manager).execute(s -> work.get());
  }

  BigDecimal balance(long id) {
    var rows =
        jdbc.queryForList(
            "select quantity from stock_balance where warehouse_id=? and material_id=?",
            BigDecimal.class,
            warehouse,
            id);
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  void reconciled() {
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_balance b where b.warehouse_id=? and b.quantity<>(select"
                + " coalesce(sum(l.change_quantity),0) from stock_ledger l where"
                + " l.balance_id=b.id)",
            Integer.class,
            warehouse));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_ledger where warehouse_id=? and"
                + " (before_quantity+change_quantity<>after_quantity or after_quantity<0)",
            Integer.class,
            warehouse));
  }

  @Test
  void writeRequiresAnExistingBusinessTransaction() {
    assertThrows(
        org.springframework.transaction.IllegalTransactionStateException.class,
        () -> inventory.post(command(Kind.PURCHASE_RECEIPT, source(), 1)));
    assertEquals(0, balance(material).signum());
  }

  @Test
  void concurrentFirstReceiptsCreateOneBalanceAndBothCommit() throws Exception {
    race(
        List.of(
            command(Kind.PURCHASE_RECEIPT, source(), 20),
            command(Kind.PURCHASE_RECEIPT, source(), 30)),
        2);
    assertEquals(new BigDecimal("50"), balance(material));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from stock_balance where warehouse_id=? and material_id=?",
            Integer.class,
            warehouse,
            material));
    reconciled();
  }

  @Test
  void concurrentShipmentsNeverOverdrawAndFailedDocumentRollsBack() throws Exception {
    tx(() -> inventory.post(command(Kind.PURCHASE_RECEIPT, source(), 10)));
    race(
        List.of(
            command(Kind.PRODUCTION_ISSUE, source(), 7),
            command(Kind.PRODUCTION_ISSUE, source(), 7)),
        1);
    assertEquals(new BigDecimal("3"), balance(material));
    assertEquals(
        2,
        jdbc.queryForObject(
            "select count(*) from stock_document where warehouse_id=?", Integer.class, warehouse));
    reconciled();
  }

  @Test
  void duplicateConcurrentPostingIsIdempotentAndChangedPayloadRejected() throws Exception {
    long source = source();
    var command = command(Kind.PURCHASE_RECEIPT, source, 5);
    race(List.of(command, command), 2);
    assertEquals(new BigDecimal("5"), balance(material));
    var replay = tx(() -> inventory.post(command));
    assertTrue(replay.replayed());
    ApiException conflict =
        assertThrows(
            ApiException.class,
            () -> tx(() -> inventory.post(command(Kind.PURCHASE_RECEIPT, source, 6))));
    assertEquals("SOURCE_CONFLICT", conflict.code);
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from stock_ledger where warehouse_id=?", Integer.class, warehouse));
    reconciled();
  }

  @Test
  void laterInsufficientLineRollsBackEntireDocument() {
    tx(() -> inventory.post(command(Kind.PURCHASE_RECEIPT, source(), 10)));
    long source = source();
    var posting =
        new StockPosting(
            Kind.PRODUCTION_ISSUE,
            source,
            "TEST-" + source,
            warehouse,
            actor,
            List.of(
                new StockPosting.Line(1, material, new BigDecimal("2")),
                new StockPosting.Line(2, secondMaterial, BigDecimal.ONE)));
    assertEquals(
        "INSUFFICIENT_STOCK",
        assertThrows(ApiException.class, () -> tx(() -> inventory.post(posting))).code);
    assertEquals(new BigDecimal("10"), balance(material));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_balance where warehouse_id=? and material_id=?",
            Integer.class,
            warehouse,
            secondMaterial));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_document where kind='PRODUCTION_ISSUE' and source_id=?",
            Integer.class,
            source));
    reconciled();
  }

  @Test
  void outerSourceUpdateAndStockCommitOrRollbackTogether() {
    // Test-only source row represents future ERP orchestration; never a production migration.
    jdbc.execute(
        "create table if not exists stage3_test_source(id bigint primary key,status varchar(20) not"
            + " null) engine=InnoDB");
    long source = source();
    jdbc.update("insert into stage3_test_source values(?,'CONFIRMED')", source);
    assertThrows(
        IllegalStateException.class,
        () ->
            tx(
                () -> {
                  jdbc.queryForObject(
                      "select status from stage3_test_source where id=? for update",
                      String.class,
                      source);
                  inventory.post(command(Kind.PURCHASE_RECEIPT, source, 4));
                  jdbc.update("update stage3_test_source set status='RECEIVED' where id=?", source);
                  throw new IllegalStateException("simulate source orchestration failure");
                }));
    assertEquals(
        "CONFIRMED",
        jdbc.queryForObject(
            "select status from stage3_test_source where id=?", String.class, source));
    assertEquals(0, balance(material).signum());
    assertFalse(
        jdbc.queryForObject("select referenced from material where id=?", Boolean.class, material));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_document where source_id=?", Integer.class, source));
    tx(
        () -> {
          inventory.post(command(Kind.PURCHASE_RECEIPT, source, 4));
          return jdbc.update("update stage3_test_source set status='RECEIVED' where id=?", source);
        });
    assertEquals(
        "RECEIVED",
        jdbc.queryForObject(
            "select status from stage3_test_source where id=?", String.class, source));
    assertEquals(new BigDecimal("4"), balance(material));
    reconciled();
  }

  @Test
  void pendingReceiptDoesNotAddStockAndExecutionOnlyAddsOnce() {
    long source = source();
    // A production receipt must contain a finished good.
    jdbc.update("update material set type='FINISHED_GOOD' where id=?", material);
    var posting = command(Kind.PRODUCTION_RECEIPT, source, 10);
    var pending = tx(() -> inventory.prepareProductionReceipt(posting));
    assertEquals("PENDING", pending.status());
    assertEquals(0, balance(material).signum());
    tx(() -> inventory.execute(pending.id(), 0, actor));
    tx(() -> inventory.execute(pending.id(), 0, actor));
    assertEquals(new BigDecimal("10"), balance(material));
    assertEquals(
        "EXECUTED",
        jdbc.queryForObject(
            "select status from stock_document where id=?", String.class, pending.id()));
    reconciled();
  }

  @Test
  void invalidAndOverflowQuantitiesAndDuplicateLinesCannotPost() {
    long source = source();
    for (String quantity : List.of("0", "-1", "1.5", "1000000000000")) {
      var posting =
          new StockPosting(
              Kind.PURCHASE_RECEIPT,
              source,
              "T",
              warehouse,
              actor,
              List.of(new StockPosting.Line(1, material, new BigDecimal(quantity))));
      assertThrows(ApiException.class, () -> tx(() -> inventory.post(posting)));
    }
    var duplicate =
        new StockPosting(
            Kind.PURCHASE_RECEIPT,
            source,
            "T",
            warehouse,
            actor,
            List.of(
                new StockPosting.Line(1, material, BigDecimal.ONE),
                new StockPosting.Line(2, material, BigDecimal.ONE)));
    assertThrows(ApiException.class, () -> tx(() -> inventory.post(duplicate)));
    tx(() -> inventory.post(command(Kind.PURCHASE_RECEIPT, source(), 999999999999L)));
    assertThrows(
        ApiException.class,
        () -> tx(() -> inventory.post(command(Kind.PURCHASE_RECEIPT, source(), 1))));
    assertEquals(new BigDecimal("999999999999"), balance(material));
    reconciled();
  }

  @Test
  void multiMaterialOppositeInputOrderUsesOneLockOrder() throws Exception {
    long source = source();
    var inbound =
        new StockPosting(
            Kind.PURCHASE_RECEIPT,
            source,
            "T-" + source,
            warehouse,
            actor,
            List.of(
                new StockPosting.Line(1, material, new BigDecimal("10")),
                new StockPosting.Line(2, secondMaterial, new BigDecimal("10"))));
    tx(() -> inventory.post(inbound));
    long a = source(), b = source();
    var first =
        new StockPosting(
            Kind.PRODUCTION_ISSUE,
            a,
            "T-" + a,
            warehouse,
            actor,
            List.of(
                new StockPosting.Line(1, material, new BigDecimal("7")),
                new StockPosting.Line(2, secondMaterial, new BigDecimal("7"))));
    var second =
        new StockPosting(
            Kind.PRODUCTION_ISSUE,
            b,
            "T-" + b,
            warehouse,
            actor,
            List.of(
                new StockPosting.Line(2, secondMaterial, new BigDecimal("7")),
                new StockPosting.Line(1, material, new BigDecimal("7"))));
    race(List.of(first, second), 1);
    assertEquals(new BigDecimal("3"), balance(material));
    assertEquals(new BigDecimal("3"), balance(secondMaterial));
    reconciled();
  }

  @Test
  void stockHttpIsAuthorizedReadOnlyAndPreservesSourceSnapshots() throws Exception {
    String base = "http://127.0.0.1:" + port;
    var guest = HttpClient.newHttpClient();
    assertEquals(
        401,
        guest
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/stock-balances")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client = HttpClient.newBuilder().cookieHandler(cookies).build();
    var csrfResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = json.readTree(csrfResponse.body()).path("token").asText();
    String login =
        json.writeValueAsString(
            Map.of("username", "viewer", "password", System.getenv("MFG_VIEWER_PASSWORD")));
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(login))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var before =
        httpGet(
            client,
            base + "/api/v1/stock-balances?warehouseId=" + warehouse + "&materialId=" + material);
    assertEquals("0", before.path("items").get(0).path("quantity").asText());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_balance where warehouse_id=?", Integer.class, warehouse));
    long source = source();
    var posted = tx(() -> inventory.post(command(Kind.PURCHASE_RECEIPT, source, 4)));
    jdbc.update("update material set name='改名后',enabled=false where id=?", material);
    var history = httpGet(client, base + "/api/v1/stock-documents/" + posted.id());
    assertEquals("电脑零部件", history.path("items").get(0).path("materialName").asText());
    assertEquals(Long.toString(source), history.path("items").get(0).path("sourceLineId").asText());
    assertEquals(
        "0",
        httpGet(
                client,
                base
                    + "/api/v1/stock-balances?warehouseId="
                    + warehouse
                    + "&materialId="
                    + material)
            .path("total")
            .asText());
    assertEquals(
        "4",
        httpGet(
                client,
                base
                    + "/api/v1/stock-balances?enabled=false&warehouseId="
                    + warehouse
                    + "&materialId="
                    + material)
            .path("items")
            .get(0)
            .path("quantity")
            .asText());
    var ledgers =
        httpGet(
            client, base + "/api/v1/stock-ledgers?sourceType=PURCHASE_ORDER&sourceId=" + source);
    assertEquals("1", ledgers.path("total").asText());
    assertEquals("电脑零部件", ledgers.path("items").get(0).path("materialName").asText());
    assertTrue(ledgers.path("items").get(0).path("stockDocumentItemId").isTextual());
    var bad =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/stock-balances?sort=quantity,desc"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, bad.statusCode());
    csrfResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    token = json.readTree(csrfResponse.body()).path("token").asText();
    var mutation =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/stock-balances"))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(404, mutation.statusCode());
    reconciled();
  }

  JsonNode httpGet(HttpClient client, String url) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  void race(List<StockPosting> postings, int expectedSuccess) throws Exception {
    var ready = new CountDownLatch(postings.size());
    var start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(postings.size());
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var posting : postings)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(10, TimeUnit.SECONDS));
                  try {
                    tx(() -> inventory.post(posting));
                    return true;
                  } catch (ApiException failure) {
                    assertEquals("INSUFFICIENT_STOCK", failure.code);
                    return false;
                  }
                }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      int successes = 0;
      for (var result : results) if (result.get(20, TimeUnit.SECONDS)) successes++;
      assertEquals(expectedSuccess, successes);
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }
}
