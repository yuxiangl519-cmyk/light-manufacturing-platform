package io.github.lightmanufacturing.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lightmanufacturing.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admission and call records share the conversation transaction. The owner row serializes users across instances. */
@Service
public class AiQuotaService {
  private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;private final AiSettings settings;private final Clock clock;
  @Autowired public AiQuotaService(JdbcTemplate jdbc,AiSettings settings){this(jdbc,settings,Clock.systemUTC());}
  public AiQuotaService(JdbcTemplate jdbc,AiSettings settings,Clock clock){this.jdbc=jdbc;this.settings=settings;this.clock=clock;}
  public static LocalDate day(Instant instant){return instant.atZone(ZONE).toLocalDate();}
  public record Quota(String day,String timezone,int dailyLimit,int dailyUsed,int dailyRemaining,int perMinuteLimit,int recentUsed,Instant resetsAt){}
  public record Call(String runId,String conversationId,String status,Instant startedAt,Instant finishedAt,Long durationMs,int toolCalls,Integer modelCalls,Long inputTokens,Long outputTokens){}
  @Transactional public void admit(long conversation,long user,String run){
    jdbc.update("insert into ai_quota_owner(tenant_key,user_id) values(?,?) on duplicate key update user_id=user_id",settings.tenant(),user);
    jdbc.queryForList("select user_id from ai_quota_owner where tenant_key=? and user_id=? for update",settings.tenant(),user);
    var now=clock.instant();var quota=quotaAt(user,now,true);
    if(quota.dailyRemaining()==0)throw new ApiException(429,"AI_DAILY_LIMIT","今日AI分析额度已用完，请明日再试");
    if(quota.recentUsed()>=settings.perMinute())throw new ApiException(429,"AI_RATE_LIMIT","AI分析请求过于频繁，请稍后再试");
    jdbc.update("insert into ai_call(run_id,tenant_key,user_id,conversation_id,quota_day,status,started_at) values(?,?,?,?,?,'RUNNING',?)",run,settings.tenant(),user,conversation,day(now),Timestamp.from(now));
  }
  @Transactional(readOnly=true) public Quota quota(long user){return quotaAt(user,clock.instant(),false);}
  private Quota quotaAt(long user,Instant now,boolean lock){
    int daily=jdbc.queryForList("select run_id from ai_call where tenant_key=? and user_id=? and quota_day=?"+(lock?" for update":""),settings.tenant(),user,day(now)).size();
    int recent=jdbc.queryForList("select run_id from ai_call where tenant_key=? and user_id=? and started_at>?"+(lock?" for update":""),settings.tenant(),user,Timestamp.from(now.minusSeconds(60))).size();
    return new Quota(day(now).toString(),ZONE.getId(),settings.perDay(),daily,Math.max(0,settings.perDay()-daily),settings.perMinute(),recent,day(now).plusDays(1).atStartOfDay(ZONE).toInstant());
  }
  @Transactional public void finish(String run,String status){
    var now=Timestamp.from(clock.instant());
    jdbc.update("update ai_call set status=?,finished_at=?,duration_ms=greatest(0,timestampdiff(microsecond,started_at,?)/1000) where run_id=? and tenant_key=? and status='RUNNING'",status,now,now,run,settings.tenant());
  }
  @Transactional public void recordToolCall(String run){jdbc.update("update ai_call set tool_calls=tool_calls+1 where run_id=? and tenant_key=? and status='RUNNING'",run,settings.tenant());}
  @Transactional public void recordUsage(String run,JsonNode usage){
    if(usage==null||!usage.isObject())return;
    jdbc.update("update ai_call set model_calls=?,input_tokens=?,output_tokens=? where run_id=? and tenant_key=? and status='RUNNING'",nonnegative(usage,"modelCalls",Integer.MAX_VALUE),nonnegative(usage.path("usage"),"prompt_tokens",Long.MAX_VALUE),nonnegative(usage.path("usage"),"completion_tokens",Long.MAX_VALUE),run,settings.tenant());
  }
  private Long nonnegative(JsonNode value,String key,long max){var n=value.get(key);return n!=null&&n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=0&&n.longValue()<=max?n.longValue():null;}
  @Transactional(readOnly=true) public List<Call> calls(long user){return jdbc.query("select * from ai_call where tenant_key=? and user_id=? order by started_at desc,run_id limit 50",(r,n)->new Call(r.getString("run_id"),r.getString("conversation_id"),r.getString("status"),r.getTimestamp("started_at").toInstant(),r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant(),r.getObject("duration_ms",Long.class),r.getInt("tool_calls"),r.getObject("model_calls",Integer.class),r.getObject("input_tokens",Long.class),r.getObject("output_tokens",Long.class)),settings.tenant(),user);}
}
