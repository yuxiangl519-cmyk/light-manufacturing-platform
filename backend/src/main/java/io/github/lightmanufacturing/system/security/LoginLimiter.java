package io.github.lightmanufacturing.system.security;

import io.github.lightmanufacturing.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LoginLimiter {
  private final StringRedisTemplate redis;
  private final String prefix;
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          "local a=tonumber(redis.call('GET',KEYS[1]) or '0'); local"
              + " i=tonumber(redis.call('GET',KEYS[2]) or '0'); if ARGV[1]=='check' then if a>=5 or"
              + " i>=30 then return 1 else return 0 end end; if ARGV[1]=='success' then"
              + " redis.call('DEL',KEYS[1]);return 0 end; for _,k in ipairs(KEYS) do local"
              + " n=redis.call('INCR',k);if n==1 then redis.call('EXPIRE',k,900) end end; return 0",
          Long.class);

  public LoginLimiter(StringRedisTemplate redis, @Value("${mfg.redis-prefix}") String prefix) {
    this.redis = redis;
    this.prefix = prefix;
  }

  public void check(String account, String ip) {
    if (run("check", account, ip) == 1L)
      throw new ApiException(429, "LOGIN_RATE_LIMITED", "登录失败次数过多，请稍后重试");
  }

  public void failure(String account, String ip) {
    run("failure", account, ip);
  }

  public void success(String account, String ip) {
    run("success", account, ip);
  }

  private long run(String action, String account, String ip) {
    try {
      Long result =
          redis.execute(
              SCRIPT,
              List.of(
                  prefix + "login-limit:account:" + hash(account.trim().toLowerCase(Locale.ROOT)),
                  prefix + "login-limit:ip:" + hash(ip)),
              action);
      if (result == null) throw new IllegalStateException();
      return result;
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "依赖服务暂不可用");
    }
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
