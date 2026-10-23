package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.lightmanufacturing.ai.application.*;
import io.github.lightmanufacturing.system.application.SystemService;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;

/** Local stub verifies relay mechanics only; live LangGraph/model verification is separate. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"mfg.ai.service-token=integration-test-only-shared-token-32","mfg.ai.quota.per-minute=10000","mfg.ai.quota.per-day=100000"})
@ActiveProfiles("test")
class AiIntegrationTest {
  static final String SERVICE="integration-test-only-shared-token-32";
  static final HttpServer upstream;
  static final java.util.concurrent.atomic.AtomicReference<JsonNode> retryPayload=new java.util.concurrent.atomic.AtomicReference<>();
  static final CountDownLatch release=new CountDownLatch(1);
  static {try{upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.createContext("/v1/analyze",exchange->{
    var incoming=new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
    if(incoming.path("message").asText().equals("stage9-usage-probe")){
      try(var out=exchange.getResponseBody()){out.write("""
        event: result
        data: {"content":"用量传输桩，不是真实模型","context":{},"execution":{"modelCalls":3,"elapsedMs":1250,"usageReports":1,"usage":{"prompt_tokens":100,"completion_tokens":20}}}

        event: done
        data: {"status":"completed"}

        """.getBytes(StandardCharsets.UTF_8));}finally{exchange.close();}return;
    }
    if(incoming.path("message").asText().startsWith("stage6-")){
      try(var out=exchange.getResponseBody()){
        if(incoming.path("message").asText().equals("stage6-resume-probe"))out.write(("event: stage\ndata: {\"node\":\"Context\",\"message\":\"测试条件，不是模型\",\"context\":{\"version\":1,\"domain\":\"sales\",\"metric\":\"sales\",\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-03\",\"dimension\":\"customer\",\"entityQuery\":\"重名公司\",\"question\":\"entity\",\"gatewayToken\":\"must-not-persist\"}}\n\nevent: error\ndata: {\"code\":\"MODEL_REQUEST_FAILED\",\"message\":\"显式模型失败传输桩\"}\n\nevent: done\ndata: {\"status\":\"error\"}\n\n").getBytes(StandardCharsets.UTF_8));
        else{retryPayload.set(incoming);out.write(("event: clarification\ndata: {\"content\":\"请重新选择客户ID（传输桩）\",\"context\":"+incoming.path("resume")+"}\n\nevent: done\ndata: {\"status\":\"clarification\"}\n\n").getBytes(StandardCharsets.UTF_8));}
      }finally{exchange.close();}return;
    }
    try(var out=exchange.getResponseBody()){out.write("event: stage\ndata: {\"node\":\"Planner\",\"message\":\"真实流传输测试桩\"}\n\n".getBytes(StandardCharsets.UTF_8));out.flush();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}out.write("event: result\ndata: {\"content\":\"明确标记的传输测试桩，不是模型报告\",\"context\":{}}\n\nevent: done\ndata: {\"status\":\"completed\"}\n\n".getBytes(StandardCharsets.UTF_8));out.flush();}
    catch(IOException ignored){}finally{exchange.close();}});upstream.start();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("mfg.ai.agent-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());}
  @AfterAll static void stop(){release.countDown();upstream.stop(0);}
  @LocalServerPort int port;
  @Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired AiConversationService conversations;@Autowired AiToolGateway gateway;@Autowired SystemService system;
  Client admin;
  @BeforeEach void login()throws Exception{assertEquals("mini_mfg_test",jdbc.queryForObject("select database()",String.class));admin=new Client();admin.login("admin",System.getenv("MFG_ADMIN_PASSWORD"));}
  @Test void ownerIsolationCsrfAndDefaultDeny()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of("title","AI权限验收"),201).path("id").asText();
    new Client().expect("GET","/api/v1/ai/conversations",null,401);
    Client viewer=new Client();viewer.login("viewer",System.getenv("MFG_VIEWER_PASSWORD"));viewer.expect("GET","/api/v1/ai/conversations/"+id,null,404);
    String foreignTitle="other-factory-"+UUID.randomUUID();
    jdbc.update("insert into ai_conversation(tenant_key,user_id,title,status,created_at,updated_at) select 'other-factory',id,?,'IDLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) from sys_user where username='admin'",foreignTitle);
    String foreignId=jdbc.queryForObject("select id from ai_conversation where tenant_key='other-factory' and title=?",String.class,foreignTitle);
    admin.expect("GET","/api/v1/ai/conversations/"+foreignId,null,404);
    for(var conversation:admin.expect("GET","/api/v1/ai/conversations",null,200))assertNotEquals(foreignId,conversation.path("id").asText());
    admin.expect("POST","/api/v1/ai/conversations",Map.of("title","伪造工厂","tenantId","other"),400);
    var noCsrf=admin.http.send(HttpRequest.newBuilder(uri("/api/v1/ai/conversations")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,noCsrf.statusCode());
    String username="ai-"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    admin.expect("POST","/api/v1/system/users",Map.of("username",username,"displayName","无销售权限","initialPassword",password,"enabled",true,"roleIds",List.of()),201);
    Client denied=new Client();denied.login(username,password);denied.expect("GET","/api/v1/ai/conversations",null,403);
  }
  @Test void toolCapabilitiesSafeTemplatesAndCallLimit()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of(),201).path("id").asText();
    var principal=system.authenticate("admin",System.getenv("MFG_ADMIN_PASSWORD")).principal();
    String ticket;String run;
    try{SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));var started=conversations.begin(Long.parseLong(id),principal.id(),"测试受控工具");run=started.id();ticket=gateway.issue(principal,Long.parseLong(id),run);}finally{SecurityContextHolder.clearContext();}
    String start=LocalDate.of(2026,10,1).toString(),end=LocalDate.of(2026,10,3).toString();var args=Map.of("startDate",start,"endDate",end);
    assertEquals(401,internal("get_sales_summary",args,ticket,false).statusCode());
    assertEquals(403,internal("get_sales_summary",args,"forged-context",true).statusCode());
    var result=internal("get_sales_summary",args,ticket,true);assertEquals(200,result.statusCode());assertEquals("CNY",json.readTree(result.body()).path("currency").asText());
    assertEquals(400,internal("execute_safe_query",Map.of("startDate",start,"endDate",end,"sql","select * from sys_user"),ticket,true).statusCode());
    assertEquals(400,internal("get_sales_summary",Map.of("startDate",start,"endDate",end,"tenantId","other"),ticket,true).statusCode());
    assertEquals(400,internal("execute_safe_query",Map.of("startDate",start,"endDate",end,"templateId","arbitrary"),ticket,true).statusCode());
    assertEquals(200,internal("execute_safe_query",Map.of("startDate",start,"endDate",end,"templateId","sales_trend"),ticket,true).statusCode());
    for(int i=0;i<5;i++)assertEquals(200,internal("get_sales_summary",args,ticket,true).statusCode());
    assertEquals(429,internal("get_sales_summary",args,ticket,true).statusCode());gateway.revoke(ticket);
    assertEquals(403,internal("get_sales_summary",args,ticket,true).statusCode());conversations.finish(Long.parseLong(id),run,"COMPLETED","工具边界验证完成",null);
  }
  @Test void sseFirstFrameArrivesBeforeUpstreamCompletesAndCancelPersists()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of(),201).path("id").asText();
    var response=admin.http.send(HttpRequest.newBuilder(uri("/api/v1/ai/conversations/"+id+"/messages")).header("Content-Type","application/json").header("X-CSRF-TOKEN",admin.csrf()).POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"最近30天销售\"}")).build(),HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200,response.statusCode());try(var body=response.body()){var reader=new BufferedReader(new InputStreamReader(body,StandardCharsets.UTF_8));assertEquals("event:stage",reader.readLine().replace(" ",""));admin.expect("POST","/api/v1/ai/conversations/"+id+"/cancel",null,204);var detail=admin.expect("GET","/api/v1/ai/conversations/"+id,null,200);assertEquals("CANCELLED",detail.path("status").asText());}
    release.countDown();
  }
  @Test void dimensionToolsKeepCapabilitiesPermissionAndArgumentBoundaries()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of(),201).path("id").asText();
    var principal=system.authenticate("admin",System.getenv("MFG_ADMIN_PASSWORD")).principal();
    String ticket,run;
    try{SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));var started=conversations.begin(Long.parseLong(id),principal.id(),"客户维度工具权限测试");run=started.id();ticket=gateway.issue(principal,Long.parseLong(id),run);}finally{SecurityContextHolder.clearContext();}
    var period=Map.of("startDate","2501-01-01","endDate","2501-01-03");
    assertEquals(401,internal("get_customer_sales_ranking",period,ticket,false).statusCode());
    assertEquals(403,internal("get_product_sales_ranking",period,"forged",true).statusCode());
    assertEquals(200,internal("get_customer_sales_ranking",period,ticket,true).statusCode());
    assertEquals(200,internal("get_product_sales_ranking",period,ticket,true).statusCode());
    assertEquals(400,internal("get_sales_summary",Map.of("startDate","2501-01-01","endDate","2501-01-03","entityId","1"),ticket,true).statusCode());
    assertEquals(400,internal("get_customer_sales_ranking",Map.of("startDate","2501-01-01","endDate","2501-01-03","limit",21),ticket,true).statusCode());
    var lookup=internal("resolve_sales_entity",Map.of("startDate","2501-01-01","endDate","2501-01-03","dimension","customer","query","nonexistent-"+UUID.randomUUID()),ticket,true);
    assertEquals(200,lookup.statusCode());assertTrue(json.readTree(lookup.body()).path("candidates").isEmpty());
    assertEquals(400,internal("get_entity_sales",Map.of("startDate","2501-01-01","endDate","2501-01-03","dimension","product","entityId","0"),ticket,true).statusCode());
    gateway.revoke(ticket);assertEquals(403,internal("resolve_sales_entity",period,ticket,true).statusCode());
    conversations.finish(Long.parseLong(id),run,"COMPLETED","维度工具权限测试完成",null);
    String username="dim-"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    admin.expect("POST","/api/v1/system/users",Map.of("username",username,"displayName","维度无权限","initialPassword",password,"enabled",true,"roleIds",List.of()),201);
    var denied=system.authenticate(username,password).principal();
    try{SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(denied,null,List.of()));var conversation=conversations.create(denied.id(),"维度拒绝");var started=conversations.begin(Long.parseLong(conversation.id()),denied.id(),"无权限");var capability=gateway.issue(denied,Long.parseLong(conversation.id()),started.id());assertEquals(403,internal("get_customer_sales_ranking",period,capability,true).statusCode());gateway.revoke(capability);conversations.finish(Long.parseLong(conversation.id()),started.id(),"ERROR","明确拒绝",null);}finally{SecurityContextHolder.clearContext();}
  }
  @Test void operationsToolsCheckSeparatePermissionsAndRetainIdentityBoundary()throws Exception{
    for(var permissions:List.of(List.of("erp:sales:read"),List.of("wms:stock:read"),List.of("mes:production:read"),List.of("wms:document:read"),List.of("mes:production:read","wms:stock:read"))){
      String code="AI4_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
      var role=admin.expect("POST","/api/v1/system/roles",Map.of("code",code,"name","AI只读分权","description","测试"),201);
      admin.expect("PUT","/api/v1/system/roles/"+role.path("id").asText()+"/permissions",Map.of("version",role.path("version").asLong(),"permissionCodes",permissions),200);
      String username="ops-"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
      admin.expect("POST","/api/v1/system/users",Map.of("username",username,"displayName","AI工具权限测试","initialPassword",password,"enabled",true,"roleIds",List.of(role.path("id").asText())),201);
      Client user=new Client();user.login(username,password);var principal=system.authenticate(username,password).principal();
      // Stock-only and document-only readers can enter AI without sales authority.
      String id=user.expect("POST","/api/v1/ai/conversations",Map.of("title","库存分权"),201).path("id").asText();
      String ticket,run;
      try{SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));var started=conversations.begin(Long.parseLong(id),principal.id(),"只读分权");run=started.id();ticket=gateway.issue(principal,Long.parseLong(id),run);}finally{SecurityContextHolder.clearContext();}
      try{
        assertEquals(permissions.contains("wms:stock:read")?200:403,internal("get_low_stock_alerts",Map.of(),ticket,true).statusCode());
        assertEquals(permissions.contains("wms:document:read")?200:403,internal("get_pending_production_receipts",Map.of(),ticket,true).statusCode());
        // A nonexistent ID yields 404 only after BOTH production and stock permissions pass.
        assertEquals(permissions.containsAll(List.of("mes:production:read","wms:stock:read"))?404:403,internal("get_production_shortage",Map.of("productionOrderId","9223372036854775807"),ticket,true).statusCode());
        assertEquals(permissions.contains("erp:sales:read")?200:403,internal("get_sales_summary",Map.of("startDate","2501-01-01","endDate","2501-01-02"),ticket,true).statusCode());
        assertEquals(401,internal("get_current_stock",Map.of(),ticket,false).statusCode());
        assertEquals(400,internal("get_current_stock",Map.of("userId","1"),ticket,true).statusCode());
        if(permissions.contains("wms:stock:read"))assertEquals(400,internal("get_current_stock",Map.of("startDate","2020-01-01"),ticket,true).statusCode());
      }finally{gateway.revoke(ticket);conversations.finish(Long.parseLong(id),run,"COMPLETED","权限测试结束",null);}
      assertEquals(403,internal("get_current_stock",Map.of(),ticket,true).statusCode());
    }
  }
  @Test void stageContextPersistsAcrossFailureRefreshAndFreshSessionRetry()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of("title","结构化恢复测试"),201).path("id").asText();
    admin.sendAnalysis(id,"stage6-resume-probe");
    Client refreshed=new Client();refreshed.login("admin",System.getenv("MFG_ADMIN_PASSWORD"));
    var detail=refreshed.expect("GET","/api/v1/ai/conversations/"+id,null,200);assertEquals("ERROR",detail.path("status").asText());
    assertEquals("customer",detail.path("context").path("dimension").asText());assertEquals("重名公司",detail.path("context").path("entityQuery").asText());
    String stored=jdbc.queryForObject("select context_json from ai_conversation where id=?",String.class,id);assertFalse(stored.contains("must-not-persist"));assertFalse(stored.contains("gatewayToken"));
    refreshed.sendAnalysis(id,"stage6-retry-probe");var payload=retryPayload.get();assertNotNull(payload);
    assertEquals("2026-10-01",payload.path("resume").path("startDate").asText());assertFalse(payload.has("messages"));assertFalse(payload.has("history"));
    assertTrue(payload.path("resume").toString().getBytes(StandardCharsets.UTF_8).length<=4096);
    assertEquals("CLARIFICATION",refreshed.expect("GET","/api/v1/ai/conversations/"+id,null,200).path("status").asText());
    Client other=new Client();other.login("viewer",System.getenv("MFG_VIEWER_PASSWORD"));other.expect("GET","/api/v1/ai/conversations/"+id,null,404);other.expect("POST","/api/v1/ai/conversations/"+id+"/messages",Map.of("message","重试"),404);
  }
  @Test void storedConditionsCannotBypassCurrentPermissionsDisabledUserOrOwner()throws Exception{
    String code="CTX_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
    var role=admin.expect("POST","/api/v1/system/roles",Map.of("code",code,"name","上下文读权限","description","测试"),201);
    role=admin.expect("PUT","/api/v1/system/roles/"+role.path("id").asText()+"/permissions",Map.of("version",role.path("version").asLong(),"permissionCodes",List.of("erp:sales:read")),200);
    String username="ctx-"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    var user=admin.expect("POST","/api/v1/system/users",Map.of("username",username,"displayName","上下文权限测试","initialPassword",password,"enabled",true,"roleIds",List.of(role.path("id").asText())),201);
    Client client=new Client();client.login(username,password);String id=client.expect("POST","/api/v1/ai/conversations",Map.of(),201).path("id").asText();
    var principal=system.authenticate(username,password).principal();String run,ticket;
    try{SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));run=conversations.begin(Long.parseLong(id),principal.id(),"恢复查询").id();ticket=gateway.issue(principal,Long.parseLong(id),run);}finally{SecurityContextHolder.clearContext();}
    try{
      conversations.saveContext(Long.parseLong(id),run,json.readTree("{\"version\":1,\"metric\":\"sales\",\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-03\",\"dimension\":\"customer\",\"entityId\":\"11\"}"));
      var args=Map.of("startDate","2026-10-01","endDate","2026-10-03");assertEquals(200,internal("get_sales_summary",args,ticket,true).statusCode());
      var other=system.authenticate("viewer",System.getenv("MFG_VIEWER_PASSWORD")).principal();String foreign=gateway.issue(other,Long.parseLong(id),run);try{assertEquals(403,internal("get_sales_summary",args,foreign,true).statusCode());}finally{gateway.revoke(foreign);}
      role=admin.expect("PUT","/api/v1/system/roles/"+role.path("id").asText()+"/permissions",Map.of("version",role.path("version").asLong(),"permissionCodes",List.of()),200);
      assertEquals(403,internal("get_sales_summary",args,ticket,true).statusCode());client.expect("GET","/api/v1/ai/conversations/"+id,null,403);
      admin.expect("PUT","/api/v1/system/users/"+user.path("id").asText(),Map.of("version",user.path("version").asLong(),"displayName","上下文权限测试","enabled",false),200);
      assertEquals(401,internal("get_sales_summary",args,ticket,true).statusCode());client.expect("GET","/api/v1/ai/conversations/"+id,null,401);
    }finally{gateway.revoke(ticket);conversations.finish(Long.parseLong(id),run,"ERROR","权限变更停止",null);}
  }
  @Test void historyLimitStopsBeforeCallingUpstreamAndContextIsBounded()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of(),201).path("id").asText();
    for(int i=0;i<199;i++)jdbc.update("insert into ai_message(conversation_id,role,content,created_at) values(?,'user','固定历史长度边界',UTC_TIMESTAMP(3))",id);
    admin.expect("POST","/api/v1/ai/conversations/"+id+"/messages",Map.of("message","不应调用上游"),409);
    assertEquals(199,jdbc.queryForObject("select count(*) from ai_message where conversation_id=?",Integer.class,id));
  }
  @Test void usageFromResultEventIsPersistedWhenDoneHasNoStatisticsAndRemainsOwnerScoped()throws Exception{
    String id=admin.expect("POST","/api/v1/ai/conversations",Map.of(),201).path("id").asText();
    admin.sendAnalysis(id,"stage9-usage-probe");
    var rows=admin.expect("GET","/api/v1/ai/conversations/calls",null,200);
    JsonNode found=null;for(var row:rows)if(row.path("conversationId").asText().equals(id))found=row;
    assertNotNull(found);assertEquals("COMPLETED",found.path("status").asText());
    assertEquals(3,found.path("modelCalls").asInt());assertEquals(100,found.path("inputTokens").asInt());assertEquals(20,found.path("outputTokens").asInt());
    assertEquals("Asia/Shanghai",admin.expect("GET","/api/v1/ai/conversations/quota",null,200).path("timezone").asText());
    new Client().expect("GET","/api/v1/ai/conversations/calls",null,401);
    Client viewer=new Client();viewer.login("viewer",System.getenv("MFG_VIEWER_PASSWORD"));
    for(var row:viewer.expect("GET","/api/v1/ai/conversations/calls",null,200))assertNotEquals(id,row.path("conversationId").asText());
  }
  private URI uri(String path){return URI.create("http://localhost:"+port+path);}
  private HttpResponse<String> internal(String name,Object body,String ticket,boolean auth)throws Exception{var request=HttpRequest.newBuilder(uri("/internal/ai/tools/"+name)).header("Content-Type","application/json").header("X-AI-Context",ticket);if(auth)request.header("Authorization","Bearer "+SERVICE);return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
  class Client{
    HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token;
    String csrf()throws Exception{if(token==null)token=expect("GET","/api/v1/auth/csrf",null,200).path("token").asText();return token;}
    JsonNode expect(String method,String path,Object body,int code)throws Exception{var req=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");if(!method.equals("GET"))req.header("X-CSRF-TOKEN",csrf());var response=http.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(code,response.statusCode(),response.body());return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());}
    void login(String username,String password)throws Exception{expect("POST","/api/v1/auth/login",Map.of("username",username,"password",password),200);token=null;csrf();}
    void sendAnalysis(String id,String message)throws Exception{var response=http.send(HttpRequest.newBuilder(uri("/api/v1/ai/conversations/"+id+"/messages")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("message",message)))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertTrue(response.body().contains("event:done"));}
  }
}
