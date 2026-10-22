package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.lightmanufacturing.master.application.*;
import io.github.lightmanufacturing.master.dto.MasterDtos.*;
import io.github.lightmanufacturing.mes.application.*;
import io.github.lightmanufacturing.mes.domain.ProductionOrder;
import io.github.lightmanufacturing.mes.dto.BomDtos.*;
import io.github.lightmanufacturing.mes.repository.ProductionOrderRepository;
import io.github.lightmanufacturing.shared.api.Quantities;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Stage3MasterIntegrationTest {
  @Autowired MasterService master;
  @Autowired BomService boms;
  @Autowired ProductionConfirmationService confirmation;
  @Autowired ProductionOrderRepository orders;
  @Autowired PlatformTransactionManager manager;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  String code() {
    return "S3-" + UUID.randomUUID().toString();
  }

  MaterialDto material(String type) {
    return master.saveMaterial(
        null, new MaterialWrite(null, code(), "电脑物料", type, "件", "", "0", true));
  }

  WarehouseDto warehouse() {
    return master.saveWarehouse(null, new WarehouseWrite(null, code(), "电脑装配仓", "", true));
  }

  BomWrite bomWrite(MaterialDto product, List<ItemWrite> items, boolean enabled) {
    return new BomWrite(null, code(), "标准办公台式机BOM", product.id(), enabled, items);
  }

  @Test
  void databaseAndCodesAndSearchAreReal() {
    assertEquals("mini_mfg_test", jdbc.queryForObject("select database()", String.class));
    var m = material("RAW_MATERIAL");
    assertThrows(
        ApiException.class,
        () ->
            master.saveMaterial(
                null, new MaterialWrite(null, m.code(), "重复", "RAW_MATERIAL", "件", "", "0", true)));
    assertEquals(
        m.id(),
        master
            .materials(m.code(), true, "RAW_MATERIAL", 1, 20, "createdAt,desc")
            .items()
            .get(0)
            .id());
    assertThrows(
        ApiException.class,
        () -> master.materials("a".repeat(101), null, null, 1, 20, "createdAt,desc"));
    assertThrows(
        ApiException.class, () -> master.materials(null, null, null, 0, 20, "createdAt,desc"));
  }

  @Test
  void partnerWarehouseLifecycleAndOptimisticVersion() {
    var c = master.saveCustomer(null, new PartnerWrite(null, code(), "客户", "联系人", "", "", true));
    var disabled =
        master.saveCustomer(
            c.id(), new PartnerWrite(c.version(), c.code(), "客户历史", "联系人", "", "", false));
    assertFalse(disabled.enabled());
    assertEquals("客户历史", master.customer(c.id()).name());
    assertThrows(
        ApiException.class,
        () ->
            master.saveCustomer(
                c.id(), new PartnerWrite(c.version(), c.code(), "过期", "", "", "", true)));
    assertThrows(
        ApiException.class,
        () -> master.saveCustomer(null, new PartnerWrite(null, c.code(), "重复", "", "", "", true)));
    var s = master.saveSupplier(null, new PartnerWrite(null, code(), "供应商", "", "", "", true));
    assertEquals(
        s.id(), master.suppliers(s.code(), true, 1, 20, "createdAt,desc").items().get(0).id());
    var w = warehouse();
    assertThrows(
        ApiException.class,
        () ->
            master.saveWarehouse(w.id(), new WarehouseWrite(w.version(), code(), "改编码", "", true)));
    assertEquals(
        w.id(), master.warehouses(w.code(), true, 1, 20, "createdAt,desc").items().get(0).id());
  }

  @Test
  void invalidBomTypesDuplicateAndQuantitiesAreRejected() {
    var raw = material("RAW_MATERIAL");
    var product = material("FINISHED_GOOD");
    assertThrows(
        ApiException.class,
        () -> boms.save(null, bomWrite(raw, List.of(new ItemWrite(raw.id(), "1")), true)));
    assertThrows(
        ApiException.class,
        () -> boms.save(null, bomWrite(product, List.of(new ItemWrite(product.id(), "1")), true)));
    assertThrows(
        ApiException.class,
        () ->
            boms.save(
                null,
                bomWrite(
                    product,
                    List.of(new ItemWrite(raw.id(), "1"), new ItemWrite(raw.id(), "2")),
                    true)));
    for (String q : List.of("0", "-1", "1.5", "1e2", "1000000000000"))
      assertThrows(
          ApiException.class,
          () -> boms.save(null, bomWrite(product, List.of(new ItemWrite(raw.id(), q)), true)));
    assertEquals(
        0,
        jdbc.queryForObject("select referenced from material where id=?", Integer.class, raw.id()));
  }

  @Test
  void onlyOneEnabledBomPerProductButManyDisabled() {
    var raw = material("RAW_MATERIAL");
    var p = material("FINISHED_GOOD");
    var first = boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "1")), true));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "2")), true)));
    boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "2")), false));
    boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "3")), false));
    assertTrue(boms.get(first.id()).enabled());
  }

  @Test
  void referencedMaterialIdentityIsFrozenAndDisabledCannotNewReference() {
    var raw = material("RAW_MATERIAL");
    var p = material("FINISHED_GOOD");
    boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "1")), true));
    var latest = master.material(raw.id());
    assertThrows(
        ApiException.class,
        () ->
            master.saveMaterial(
                raw.id(),
                new MaterialWrite(
                    latest.version(), code(), raw.name(), raw.type(), raw.unit(), "", "0", true)));
    assertThrows(
        ApiException.class,
        () ->
            master.saveMaterial(
                raw.id(),
                new MaterialWrite(
                    latest.version(),
                    raw.code(),
                    raw.name(),
                    "FINISHED_GOOD",
                    raw.unit(),
                    "",
                    "0",
                    true)));
    var off =
        master.saveMaterial(
            raw.id(),
            new MaterialWrite(
                latest.version(), raw.code(), raw.name(), raw.type(), raw.unit(), "", "0", false));
    assertFalse(off.enabled());
    assertEquals(raw.name(), master.material(raw.id()).name());
    assertThrows(
        ApiException.class,
        () -> boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "1")), false)));
  }

  long order(MaterialDto p, WarehouseDto w, String bomId, String qty) {
    return new TransactionTemplate(manager)
        .execute(
            tx -> {
              var o = new ProductionOrder();
              o.setOrderNo(code());
              o.setProductMaterialId(Long.valueOf(p.id()));
              o.setProductCode("");
              o.setProductName("");
              o.setUnit("");
              o.setPlannedQuantity(Quantities.positive(qty));
              o.setBomId(Long.valueOf(bomId));
              o.setBomCode("");
              o.setIssueWarehouseId(Long.valueOf(w.id()));
              o.setIssueWarehouseName("");
              o.setReceiptWarehouseId(Long.valueOf(w.id()));
              o.setReceiptWarehouseName("");
              o.setStatus("DRAFT");
              o.setRemark("");
              return orders.saveAndFlush(o).getId();
            });
  }

  @Test
  void disablingExistingBomRetainsHistoricalDisabledComponentsButRejectsNewDisabledReferences() {
    var p = material("FINISHED_GOOD");
    var old = material("RAW_MATERIAL");
    var added = material("RAW_MATERIAL");
    var b = boms.save(null, bomWrite(p, List.of(new ItemWrite(old.id(), "1")), true));
    var latest = master.material(old.id());
    master.saveMaterial(
        old.id(),
        new MaterialWrite(
            latest.version(), old.code(), old.name(), old.type(), old.unit(), "", "0", false));
    master.saveMaterial(
        added.id(),
        new MaterialWrite(
            added.version(),
            added.code(),
            added.name(),
            added.type(),
            added.unit(),
            "",
            "0",
            false));
    assertThrows(
        ApiException.class,
        () ->
            boms.save(
                b.id(),
                new BomWrite(
                    b.version(),
                    b.code(),
                    b.name(),
                    p.id(),
                    false,
                    List.of(new ItemWrite(old.id(), "1"), new ItemWrite(added.id(), "1")))));
    var off =
        boms.save(
            b.id(),
            new BomWrite(
                b.version(),
                b.code(),
                b.name(),
                p.id(),
                false,
                List.of(new ItemWrite(old.id(), "1"))));
    assertFalse(off.enabled());
    assertEquals(old.id(), off.items().get(0).materialId());
  }

  @Test
  void committedConfirmationSnapshotSurvivesBomAndNamesChanges() {
    var raw = material("RAW_MATERIAL");
    var p = material("FINISHED_GOOD");
    var w = warehouse();
    var bom = boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "2")), true));
    long id = order(p, w, bom.id(), "10");
    var confirmed = confirmation.confirm(id, 0);
    assertEquals("20", confirmed.items().get(0).requiredQuantity());
    boms.save(
        bom.id(),
        new BomWrite(
            bom.version(),
            bom.code(),
            "新BOM",
            p.id(),
            true,
            List.of(new ItemWrite(raw.id(), "3"))));
    var latest = master.material(raw.id());
    master.saveMaterial(
        raw.id(),
        new MaterialWrite(
            latest.version(), raw.code(), "更名", raw.type(), raw.unit(), "", "0", false));
    var frozen = confirmation.snapshot(id);
    assertEquals("2", frozen.items().get(0).quantityPer());
    assertEquals("20", frozen.items().get(0).requiredQuantity());
    assertEquals(raw.name(), frozen.items().get(0).materialName());
    assertThrows(ApiException.class, () -> confirmation.confirm(id, 0));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from production_material_snapshot where production_order_id=?",
            Integer.class,
            id));
  }

  @Test
  void confirmationOverflowAndDisabledWarehouseRollBackEverything() {
    var raw = material("RAW_MATERIAL");
    var p = material("FINISHED_GOOD");
    var w = warehouse();
    var b = boms.save(null, bomWrite(p, List.of(new ItemWrite(raw.id(), "2")), true));
    long overflow = order(p, w, b.id(), "999999999999");
    assertThrows(ApiException.class, () -> confirmation.confirm(overflow, 0));
    assertEquals(
        "DRAFT",
        jdbc.queryForObject(
            "select status from production_order where id=?", String.class, overflow));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from production_material_snapshot where production_order_id=?",
            Integer.class,
            overflow));
    long id = order(p, w, b.id(), "1");
    master.saveWarehouse(w.id(), new WarehouseWrite(w.version(), w.code(), w.name(), "", false));
    assertThrows(ApiException.class, () -> confirmation.confirm(id, 0));
    assertEquals(
        "DRAFT",
        jdbc.queryForObject("select status from production_order where id=?", String.class, id));
  }

  @Test
  void httpRequiresAuthenticationPermissionsCsrfAndStringQuantities() throws Exception {
    Client guest = new Client();
    assertEquals(401, guest.request("GET", "/api/v1/materials", null, false).statusCode());
    Client viewer = new Client();
    viewer.login("viewer", System.getenv("MFG_VIEWER_PASSWORD"));
    assertEquals(200, viewer.request("GET", "/api/v1/materials", null, false).statusCode());
    Map<String, Object> body =
        new HashMap<>(
            Map.of(
                "code",
                code(),
                "name",
                "测试物料",
                "type",
                "RAW_MATERIAL",
                "unit",
                "件",
                "specification",
                "",
                "minStock",
                "0",
                "enabled",
                true));
    assertEquals(403, viewer.request("POST", "/api/v1/materials", body, true).statusCode());
    Client admin = new Client();
    admin.login("admin", System.getenv("MFG_ADMIN_PASSWORD"));
    assertEquals(403, admin.request("POST", "/api/v1/materials", body, false).statusCode());
    body.put("minStock", 1);
    assertEquals(400, admin.request("POST", "/api/v1/materials", body, true).statusCode());
    body.put("minStock", "0");
    assertEquals(201, admin.request("POST", "/api/v1/materials", body, true).statusCode());
    assertEquals(404, admin.request("DELETE", "/api/v1/materials/1", null, true).statusCode());
  }

  class Client {
    HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String csrf;

    HttpResponse<String> request(String method, String path, Object body, boolean token)
        throws Exception {
      var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (token) {
        if (csrf == null) {
          var r = request("GET", "/api/v1/auth/csrf", null, false);
          csrf = json.readTree(r.body()).path("token").asText();
        }
        b.header("X-CSRF-TOKEN", csrf);
      }
      b.header("Content-Type", "application/json");
      b.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    void login(String user, String pass) throws Exception {
      assertEquals(
          200,
          request("POST", "/api/v1/auth/login", Map.of("username", user, "password", pass), true)
              .statusCode());
      csrf = null;
    }
  }
}
