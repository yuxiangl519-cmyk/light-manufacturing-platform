package io.github.lightmanufacturing.ai.application;

import io.github.lightmanufacturing.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiSettings {
  private final Environment env;
  public AiSettings(Environment env){this.env=env;}
  public String tenant(){return env.getProperty("mfg.ai.tenant","local-factory");}
  public int perMinute(){return positive("mfg.ai.quota.per-minute",5);}
  public int perDay(){return positive("mfg.ai.quota.per-day",50);}
  private int positive(String key,int fallback){int value=env.getProperty(key,Integer.class,fallback);if(value<1)throw new IllegalStateException("AI quota must be positive: "+key);return value;}
  public String token(){return env.getProperty("mfg.ai.service-token","");}
  public URI agent(){return URI.create(env.getProperty("mfg.ai.agent-url","http://127.0.0.1:8091"));}
  public void available(){
    URI uri=agent();
    if(token().length()<32)throw new ApiException(503,"AI_NOT_CONFIGURED","AI服务尚未配置");
    if(!"http".equals(uri.getScheme())||!java.util.Set.of("127.0.0.1","localhost").contains(uri.getHost())||uri.getUserInfo()!=null)
      throw new ApiException(503,"AI_CONFIG_INVALID","MVP仅允许本机Agent服务");
  }
  public boolean authentic(String header){
    return token().length()>=32 && header!=null && MessageDigest.isEqual(("Bearer "+token()).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8));
  }
}
