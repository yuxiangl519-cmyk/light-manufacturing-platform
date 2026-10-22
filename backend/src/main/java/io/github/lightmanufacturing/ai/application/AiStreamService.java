package io.github.lightmanufacturing.ai.application;

import com.fasterxml.jackson.databind.*;
import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.system.security.SessionPrincipal;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded blocking HTTP relay: each SSE frame is sent immediately, never collected as a response. */
@Service
public class AiStreamService {
  private static final Logger log=LoggerFactory.getLogger(AiStreamService.class);
  private final AiSettings settings;private final AiConversationService conversations;private final AiToolGateway gateway;private final ObjectMapper json;
  private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
  private final ExecutorService workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
  private final Semaphore permits=new Semaphore(4);
  private final ConcurrentHashMap<Long,Handle> active=new ConcurrentHashMap<>();
  private static class Handle {volatile InputStream body;volatile Future<?> task;volatile CompletableFuture<?> upstream;final AtomicBoolean cancelled=new AtomicBoolean();void cancel(){cancelled.set(true);if(upstream!=null)upstream.cancel(true);try{if(body!=null)body.close();}catch(IOException ignored){}}}
  public AiStreamService(AiSettings settings,AiConversationService conversations,AiToolGateway gateway,ObjectMapper json){this.settings=settings;this.conversations=conversations;this.gateway=gateway;this.json=json;}
  public SseEmitter start(long id,SessionPrincipal principal,String message){
    settings.available();if(!permits.tryAcquire())throw new ApiException(429,"AI_CAPACITY","同时分析任务达到上限，请稍后再试");
    AiConversationService.Run run;String ticket;
    try{run=conversations.begin(id,principal.id(),message);}catch(RuntimeException e){permits.release();throw e;}
    try{ticket=gateway.issue(principal,id,run.id());}catch(RuntimeException e){conversations.finish(id,run.id(),"ERROR","分析授权创建失败，请稍后重试。",null);permits.release();throw e;}
    var emitter=new SseEmitter(190_000L);var handle=new Handle();active.put(id,handle);
    var deadline=timer.schedule(()->{conversations.finish(id,run.id(),"ERROR","分析超时，请缩小范围后重试。",null);handle.cancel();emitter.complete();},180,TimeUnit.SECONDS);
    Runnable disconnected=()->{try{conversations.finish(id,run.id(),"ERROR","连接已终止，请重新发起分析。",null);}finally{handle.cancel();}};
    emitter.onTimeout(disconnected);emitter.onError(e->disconnected.run());
    handle.task=workers.submit(()->{try{if(!handle.cancelled.get())relay(id,run,message,ticket,handle,emitter);}finally{deadline.cancel(false);gateway.revoke(ticket);active.remove(id,handle);permits.release();emitter.complete();}});
    return emitter;
  }
  private void relay(long id,AiConversationService.Run run,String message,String ticket,Handle handle,SseEmitter emitter){
    String result=null,status="ERROR";JsonNode context=null;boolean terminal=false,done=false;
    try{
      emitter.send(SseEmitter.event().name("stage").data(Map.of("node","Gateway","message","身份已验证，正在连接分析服务")));
      var payload=json.createObjectNode().put("requestId",run.id()).put("conversationId",Long.toString(id)).put("message",message);
      payload.putObject("context").put("gatewayToken",ticket);if(run.resume()!=null)payload.set("resume",run.resume());
      var request=HttpRequest.newBuilder(settings.agent().resolve("/v1/analyze")).timeout(Duration.ofSeconds(180)).header("Authorization","Bearer "+settings.token()).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
      var pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());handle.upstream=pending;if(handle.cancelled.get())pending.cancel(true);var response=pending.get();handle.body=response.body();
      try(var input=response.body()){
        if(response.statusCode()!=200||!response.headers().firstValue("content-type").orElse("").contains("text/event-stream"))throw new IOException("Agent unavailable");
        var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));String line,event="message";var data=new StringBuilder();int events=0;
        while((line=boundedLine(reader))!=null){
          if(handle.cancelled.get()||!conversations.running(id,run.id()))return;
          if(line.length()>32000)throw new IOException("Frame too large");
          if(line.startsWith("event:"))event=line.substring(6).trim();else if(line.startsWith("data:")){data.append(line.substring(5).stripLeading()).append('\n');if(data.length()>32000)throw new IOException("Frame too large");}
          else if(line.isEmpty()&&!data.isEmpty()){
            if(++events>100)throw new IOException("Too many events");
            gateway.validate(ticket);
            if(!Set.of("stage","result","clarification","error","done").contains(event))throw new IOException("Unknown event");
            JsonNode value=json.readTree(data.toString());if(!value.isObject())throw new IOException("Invalid event");
            value=AiPresentation.event(event,value);
            if("stage".equals(event)&&value.has("context")){context=AiContext.clean(value.get("context"));conversations.saveContext(id,run.id(),context);((com.fasterxml.jackson.databind.node.ObjectNode)value).set("context",context);}
            if(Set.of("result","clarification","error").contains(event)){
              if(terminal)throw new IOException("Duplicate terminal event");terminal=true;
              result=value.path("content").asText(value.path("message").asText("分析失败，请稍后重试"));
              if(result.length()>30000)throw new IOException("Report too large");
              status=switch(event){case "result"->"COMPLETED";case "clarification"->"CLARIFICATION";default->"ERROR";};context=value.get("context");if(context!=null){context=AiContext.clean(context);((com.fasterxml.jackson.databind.node.ObjectNode)value).set("context",context);}
            }
            if("done".equals(event)){if(!terminal)throw new IOException("Missing terminal event");conversations.finish(id,run.id(),status,result,context);((com.fasterxml.jackson.databind.node.ObjectNode)value).put("status",status.toLowerCase(Locale.ROOT));done=true;}
            emitter.send(SseEmitter.event().name(event).data(value));data.setLength(0);if(done)break;event="message";
          }
        }
        if(!terminal||!done)throw new IOException("Incomplete stream");
      }
    }catch(Exception failure){
      if(handle.cancelled.get())return;
      log.warn("AI request {} failed: {}",run.id(),failure.getClass().getSimpleName());
      conversations.finish(id,run.id(),"ERROR","分析服务暂不可用，请稍后重试。",null);
      try{emitter.send(SseEmitter.event().name("error").data(Map.of("code","AI_UPSTREAM_ERROR","message","分析服务暂不可用，请稍后重试","requestId",run.id())));emitter.send(SseEmitter.event().name("done").data(Map.of("status","ERROR")));}catch(IOException ignored){}
    }
  }
  private static String boundedLine(Reader reader)throws IOException {var line=new StringBuilder();int c;while((c=reader.read())!=-1){if(c=='\n')break;if(line.length()>=32000)throw new IOException("Frame too large");line.append((char)c);}if(c==-1&&line.isEmpty())return null;int n=line.length();if(n>0&&line.charAt(n-1)=='\r')line.setLength(n-1);return line.toString();}
  public void cancel(long id,long user){conversations.cancel(id,user);var handle=active.get(id);if(handle!=null)handle.cancel();}
  @PreDestroy public void close(){active.values().forEach(Handle::cancel);workers.shutdownNow();timer.shutdownNow();}
}
