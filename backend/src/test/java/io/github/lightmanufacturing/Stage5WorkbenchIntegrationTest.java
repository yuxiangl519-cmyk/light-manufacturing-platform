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
class Stage5WorkbenchIntegrationTest {
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
  void aggregatesRealOrdersAndValidatesPeriodsAndDirections() throws Exception {
    var order=create("/purchase-orders",purchase(raw,"3","12.30"));
    String path="/purchase-orders/"+order.path("id").asText();
    var confirmed=admin.expect("POST",path+"/confirm",Map.of("version",0),200);
    admin.expect("POST",path+"/receive",Map.of("version",confirmed.path("document").path("version").asLong()),200);
    var summary=admin.expect("GET","/workbench",null,200);
    var from=java.sql.Timestamp.from(java.time.Instant.parse(summary.path("period").path("from").asText()));
    var to=java.sql.Timestamp.from(java.time.Instant.parse(summary.path("period").path("to").asText()));
    long purchases=jdbc.queryForObject("select count(*) from purchase_order where deleted_at is null and status<>'CANCELLED' and created_at>=? and created_at<?",Long.class,from,to);
    assertTrue(purchases>0);
    assertEquals(Long.toString(purchases),summary.path("cards").path("purchaseCount").asText());
    assertEquals(jdbc.queryForObject("select coalesce(sum(total_amount),0) from purchase_order where deleted_at is null and status='RECEIVED' and created_at>=? and created_at<?",java.math.BigDecimal.class,from,to).setScale(2).toPlainString(),summary.path("cards").path("receivedPurchaseAmount").asText());
    assertEquals(7,summary.path("trend").size());
    assertEquals(jdbc.queryForObject("select coalesce(sum(total_amount),0) from purchase_order where deleted_at is null and status in('CONFIRMED','RECEIVED') and created_at>=? and created_at<?",java.math.BigDecimal.class,from,to).setScale(2).toPlainString(),summary.path("cards").path("confirmedPurchaseAmount").asText());
    var receivedTodo=java.util.stream.StreamSupport.stream(summary.path("todos").spliterator(),false).filter(t->t.path("type").asText().equals("PURCHASE_RECEIVE")).findFirst().orElseThrow();
    assertEquals(jdbc.queryForObject("select count(*) from purchase_order where deleted_at is null and status='CONFIRMED'",Long.class).toString(),receivedTodo.path("count").asText());
    for(var state:summary.path("production").path("byStatus"))assertEquals(jdbc.queryForObject("select count(*) from production_order where deleted_at is null and status=?",Long.class,state.path("status").asText()).toString(),state.path("count").asText());
    var completed=jdbc.queryForList("select unit,sum(planned_quantity) quantity from production_order where deleted_at is null and status='COMPLETED' and completed_at>=? and completed_at<? group by unit order by unit",from,to);
    assertEquals(completed.size(),summary.path("production").path("completedQuantities").size());
    for(int i=0;i<completed.size();i++){assertEquals(completed.get(i).get("unit"),summary.path("production").path("completedQuantities").get(i).path("unit").asText());assertEquals(((java.math.BigDecimal)completed.get(i).get("quantity")).toPlainString(),summary.path("production").path("completedQuantities").get(i).path("quantity").asText());}
    for(var point:summary.path("trend")) {
      var day=java.time.LocalDate.parse(point.path("date").asText());
      var zone=java.time.ZoneId.of("Asia/Shanghai");
      assertEquals(jdbc.queryForObject("select count(*) from purchase_order where deleted_at is null and status<>'CANCELLED' and created_at>=? and created_at<?",Long.class,java.sql.Timestamp.from(day.atStartOfDay(zone).toInstant()),java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant())).toString(),point.path("purchaseCount").asText());
    }
    for(String direction:List.of("IN","OUT")) {
      var page=admin.expect("GET","/stock-documents?direction="+direction+"&size=1&page=1",null,200);
      long total=jdbc.queryForObject("select count(*) from stock_document where direction=?",Long.class,direction);
      assertEquals(Long.toString(total),page.path("total").asText());
      assertEquals((int)Math.min(total,1),page.path("items").size());
      for(var item:page.path("items")) assertEquals(direction,item.path("direction").asText());
      var second=admin.expect("GET","/stock-documents?direction="+direction+"&size=1&page=2",null,200);
      assertEquals((int)Math.min(Math.max(0,total-1),1),second.path("items").size());
      assertEquals(page.path("total"),second.path("total"));
    }
    admin.expect("GET","/stock-documents?direction=SIDEWAYS",null,400);
    admin.expect("GET","/workbench?from=2026-10-01T00:00:00Z&to=2026-10-01T00:00:00Z",null,400);
    admin.expect("GET","/workbench?from=2020-01-01T00:00:00Z&to=2026-01-01T00:00:00Z",null,400);
    admin.expect("GET","/workbench?from=bad",null,400);
    new Client().expect("GET","/workbench",null,401);
  }

  @Test
  void permissionFilteredProjectionDoesNotLeakOtherModules() throws Exception {
    String suffix=UUID.randomUUID().toString().substring(0,8);
    var role=create("/system/roles",Map.of("code","WB_"+suffix.toUpperCase(java.util.Locale.ROOT),"name","采购工作台","description",""));
    admin.expect("PUT","/system/roles/"+role.path("id").asText()+"/permissions",Map.of("version",role.path("version").asLong(),"permissionCodes",List.of("workbench:read","erp:purchase:read")),200);
    String password=UUID.randomUUID().toString();
    create("/system/users",Map.of("username","wb-"+suffix,"displayName","采购工作台用户","initialPassword",password,"enabled",true,"roleIds",List.of(role.path("id").asText())));
    var limited=new Client(); limited.login("wb-"+suffix,password);
    var result=limited.expect("GET","/workbench",null,200);
    assertTrue(result.path("cards").has("purchaseCount"));
    assertFalse(result.path("cards").has("salesCount"));
    assertFalse(result.path("cards").has("shippedSalesAmount"));
    assertFalse(result.path("cards").has("confirmedSalesAmount"));
    assertFalse(result.has("production")); assertFalse(result.has("inventory"));
    assertEquals(0,result.path("recentProduction").size()); assertEquals(0,result.path("todos").size());
    for(var point:result.path("trend")) assertFalse(point.has("salesCount"));
    for(var order:result.path("recentOrders")) assertEquals("PURCHASE_ORDER",order.path("kind").asText());
    var current=admin.expect("GET","/system/roles/"+role.path("id").asText(),null,200);
    admin.expect("PUT","/system/roles/"+role.path("id").asText()+"/permissions",Map.of("version",current.path("version").asLong(),"permissionCodes",List.of("erp:purchase:read")),200);
    limited.expect("GET","/workbench",null,403);
  }
  @Test
  void systemSearchTreatsUnderscoreAndPercentAsLiteralCharacters() throws Exception {
    String suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    String roleCode="UI_"+suffix;
    create("/system/roles",Map.of("code",roleCode,"name","搜索%_"+suffix,"description",""));
    var rolePage=admin.expect("GET","/system/roles?q="+roleCode,null,200);
    assertEquals("1",rolePage.path("total").asText());
    var literalPage=admin.expect("GET","/system/roles?q="+URLEncoder.encode("搜索%_"+suffix,java.nio.charset.StandardCharsets.UTF_8),null,200);
    assertEquals("1",literalPage.path("total").asText());
    String username="ui_"+suffix.toLowerCase(Locale.ROOT);
    create("/system/users",Map.of("username",username,"displayName","搜索用户","initialPassword",UUID.randomUUID().toString(),"enabled",true,"roleIds",List.of()));
    assertEquals("1",admin.expect("GET","/system/users?q="+username,null,200).path("total").asText());
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


