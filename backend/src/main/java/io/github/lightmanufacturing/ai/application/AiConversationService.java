package io.github.lightmanufacturing.ai.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.ai.dto.AiDtos.*;
import io.github.lightmanufacturing.system.application.AuditService;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class AiConversationService {
  private final AiQuotaService quota;private final JdbcTemplate jdbc;private final AiSettings settings;private final ObjectMapper json;private final AuditService audit;
  public AiConversationService(JdbcTemplate jdbc,AiSettings settings,ObjectMapper json,AuditService audit,AiQuotaService quota){this.quota=quota;this.jdbc=jdbc;this.settings=settings;this.json=json;this.audit=audit;}
  private Map<String,Object> owned(long id,long user,boolean lock){
    var rows=jdbc.queryForList("select * from ai_conversation where id=? and user_id=? and tenant_key=?"+(lock?" for update":""),id,user,settings.tenant());
    if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
  }
  public List<Conversation> list(long user){return jdbc.query("select id,title,status,updated_at from ai_conversation where tenant_key=? and user_id=? order by updated_at desc,id desc limit 50",(r,n)->new Conversation(r.getString("id"),AiPresentation.text(r.getString("title")),r.getString("status"),r.getTimestamp("updated_at").toInstant()),settings.tenant(),user);}
  @Transactional
  public Conversation create(long user,String title){
    var key=new GeneratedKeyHolder();String value=title==null||title.isBlank()?"新的经营分析":title.trim();
    jdbc.update(c->{var p=c.prepareStatement("insert into ai_conversation(tenant_key,user_id,title,status,created_at,updated_at) values(?,?,?,'IDLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",Statement.RETURN_GENERATED_KEYS);p.setString(1,settings.tenant());p.setLong(2,user);p.setString(3,value);return p;},key);
    long id=Objects.requireNonNull(key.getKey()).longValue();audit.success("CREATE","ai_conversation",id);return new Conversation(Long.toString(id),value,"IDLE",Instant.now());
  }
  private JsonNode context(Object value){try{return AiContext.clean(value==null?null:json.readTree(value.toString()));}catch(java.io.IOException e){throw new ApiException(500,"AI_CONTEXT_INVALID","会话条件读取失败，请新建会话");}}
  public Detail detail(long id,long user){var row=owned(id,user,false);var messages=jdbc.query("select id,role,content,created_at from ai_message where conversation_id=? order by id limit 200",(r,n)->new Message(r.getString("id"),r.getString("role"),AiPresentation.text(r.getString("content")),r.getTimestamp("created_at").toInstant()),id);return new Detail(Long.toString(id),AiPresentation.text((String)row.get("title")),(String)row.get("status"),messages,"CLARIFICATION".equals(row.get("status"))&&!messages.isEmpty()?messages.get(messages.size()-1).content():null,context(row.get("context_json")));}
  public record Run(String id,JsonNode resume) {}
  @Transactional
  public Run begin(long id,long user,String message){
    return begin(id,user,message,UUID.randomUUID().toString());
  }
  @Transactional
  public Run begin(long id,long user,String message,String run){
    var row=owned(id,user,true);
    if("RUNNING".equals(row.get("status"))&&((Timestamp)row.get("updated_at")).toInstant().isAfter(Instant.now().minusSeconds(210)))throw ApiException.conflict("AI_BUSY","该会话仍在分析，请先取消或稍后再试");
    Integer count=jdbc.queryForObject("select count(*) from ai_message where conversation_id=?",Integer.class,id);if(count!=null&&count>=199)throw ApiException.conflict("AI_HISTORY_LIMIT","会话剩余容量不足一轮（最多200条消息），请新建会话");
    if("RUNNING".equals(row.get("status")))quota.finish((String)row.get("run_id"),"ERROR");
    quota.admit(id,user,run);JsonNode resume=context(row.get("context_json"));
    jdbc.update("insert into ai_message(conversation_id,role,content,created_at) values(?,'user',?,UTC_TIMESTAMP(3))",id,message);
    jdbc.update("update ai_conversation set status='RUNNING',run_id=?,context_json=?,updated_at=UTC_TIMESTAMP(3) where id=?",run,resume.toString(),id);
    audit.success("ANALYZE","ai_conversation",id);return new Run(run,resume);
  }
  public boolean running(long id,String run){return Boolean.TRUE.equals(jdbc.queryForObject("select count(*)=1 from ai_conversation where id=? and run_id=? and tenant_key=? and status='RUNNING'",Boolean.class,id,run,settings.tenant()));}
  public boolean running(long id,String run,long user){return Boolean.TRUE.equals(jdbc.queryForObject("select count(*)=1 from ai_conversation where id=? and run_id=? and tenant_key=? and user_id=? and status='RUNNING'",Boolean.class,id,run,settings.tenant(),user));}
  @Transactional public void saveContext(long id,String run,JsonNode value){jdbc.update("update ai_conversation set context_json=? where id=? and run_id=? and tenant_key=? and status='RUNNING'",AiContext.clean(value).toString(),id,run,settings.tenant());}
  @Transactional
  public void finish(long id,String run,String status,String content,JsonNode context){
    var rows=jdbc.queryForList("select status,run_id from ai_conversation where id=? and tenant_key=? for update",id,settings.tenant());
    if(rows.isEmpty()||!"RUNNING".equals(rows.get(0).get("status"))||!run.equals(rows.get(0).get("run_id")))return;
    quota.finish(run,status);
    String serialized=context==null||context.isNull()?null:AiContext.clean(context).toString();
    if(content!=null&&!content.isBlank()){if(content.length()>30000)throw ApiException.invalid("AI响应过大");jdbc.update("insert into ai_message(conversation_id,role,content,created_at) values(?,'assistant',?,UTC_TIMESTAMP(3))",id,content);}
    jdbc.update("update ai_conversation set status=?,context_json=coalesce(?,context_json),updated_at=UTC_TIMESTAMP(3) where id=?",status,serialized,id);
  }
  @Transactional
  public void expireAbandoned(){
    var rows=jdbc.queryForList("select id,run_id from ai_conversation where tenant_key=? and status='RUNNING' and updated_at<UTC_TIMESTAMP(3)-interval 210 second for update",settings.tenant());
    for(var row:rows)finish(((Number)row.get("id")).longValue(),(String)row.get("run_id"),"ERROR","分析已超时或服务已重启，请重新发起。",null);
  }
  @Transactional
  public void cancel(long id,long user){var row=owned(id,user,true);if("RUNNING".equals(row.get("status")))quota.finish((String)row.get("run_id"),"CANCELLED");jdbc.update("update ai_conversation set status='CANCELLED',updated_at=UTC_TIMESTAMP(3) where id=? and status='RUNNING'",id);audit.success("CANCEL","ai_conversation",id);}
}
