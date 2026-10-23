package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lightmanufacturing.ai.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest @ActiveProfiles("test")
class AiQuotaIntegrationTest {
  @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
  String tenant;long user,conversation;AiSettings settings;AiQuotaService quota;TransactionTemplate tx;
  final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-22T15:59:00Z"));
  final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
  @BeforeEach void setup(){
    assertEquals("mini_mfg_test",jdbc.queryForObject("select database()",String.class));
    tenant="quota-"+UUID.randomUUID();user=jdbc.queryForObject("select id from sys_user where username='admin'",Long.class);
    jdbc.update("insert into ai_conversation(tenant_key,user_id,title,status,created_at,updated_at) values(?,?,'配额隔离测试','IDLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant,user);
    conversation=jdbc.queryForObject("select id from ai_conversation where tenant_key=?",Long.class,tenant);
    settings=new AiSettings(new MockEnvironment().withProperty("mfg.ai.tenant",tenant).withProperty("mfg.ai.quota.per-minute","5").withProperty("mfg.ai.quota.per-day","6"));
    quota=new AiQuotaService(jdbc,settings,clock);tx=new TransactionTemplate(manager);
  }
  @AfterEach void cleanup(){jdbc.update("delete from ai_call where tenant_key=?",tenant);jdbc.update("delete from ai_quota_owner where tenant_key=?",tenant);jdbc.update("delete from ai_conversation where tenant_key=?",tenant);}
  String admit(){String run=UUID.randomUUID().toString();tx.executeWithoutResult(s->quota.admit(conversation,user,run));return run;}
  @Test void realMysqlConcurrentAdmissionNeverExceedsRollingLimit()throws Exception{
    var pool=Executors.newFixedThreadPool(10);var ready=new CountDownLatch(10);var go=new CountDownLatch(1);
    try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<10;i++)tasks.add(pool.submit(()->{ready.countDown();go.await();try{admit();return true;}catch(ApiException expected){return false;}}));
      assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();int accepted=0;for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))accepted++;
      assertEquals(5,accepted);assertEquals(5,quota.quota(user).dailyUsed());
    }finally{pool.shutdownNow();}
  }
  @Test void dailyQuotaSurvivesServiceRecreationAndResetsAtShanghaiMidnight(){
    for(int i=0;i<5;i++)admit();assertThrows(ApiException.class,this::admit);
    now.set(Instant.parse("2026-10-22T15:59:59Z"));assertThrows(ApiException.class,this::admit);
    now.set(Instant.parse("2026-10-22T16:00:00Z"));assertEquals(0,quota.quota(user).dailyUsed());admit();
    quota=new AiQuotaService(jdbc,settings,clock);assertEquals(1,quota.quota(user).dailyUsed());
    for(int i=0;i<4;i++)admit();now.set(now.get().plusSeconds(60));admit();assertThrows(ApiException.class,this::admit);
    assertEquals(6,quota.quota(user).dailyUsed());
  }
  @Test void rollbackDoesNotChargeAndTerminalCompletionIsIdempotent()throws Exception{
    assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{quota.admit(conversation,user,UUID.randomUUID().toString());throw new IllegalStateException("rollback");}));
    assertEquals(0,quota.quota(user).dailyUsed());String run=admit();quota.recordToolCall(run);
    quota.recordUsage(run,new ObjectMapper().readTree("{\"modelCalls\":2,\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}"));
    now.set(now.get().plusMillis(250));quota.finish(run,"CANCELLED");quota.finish(run,"COMPLETED");quota.recordToolCall(run);
    var call=quota.calls(user).get(0);assertEquals("CANCELLED",call.status());assertEquals(250L,call.durationMs());assertEquals(1,call.toolCalls());assertEquals(2,call.modelCalls());assertEquals(100L,call.inputTokens());assertEquals(1,quota.quota(user).dailyUsed());
    String missing=admit();quota.finish(missing,"ERROR");assertNull(quota.calls(user).stream().filter(c->c.runId().equals(missing)).findFirst().orElseThrow().inputTokens());
    assertTrue(quota.calls(user+999999).isEmpty());
  }
}
