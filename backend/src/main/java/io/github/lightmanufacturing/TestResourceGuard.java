package io.github.lightmanufacturing;

import java.net.URI;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class TestResourceGuard implements EnvironmentPostProcessor, Ordered {
  public static void validate(String url, String namespace, int port) {
    try {
      URI uri = URI.create(url.substring(5));
      if (!"/mini_mfg_test".equals(uri.getPath())
          || !"light-mfg:test:".equals(namespace)
          || port != 16380)
        throw new IllegalStateException(
            "Test resources must use mini_mfg_test and isolated Redis test namespace/port");
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Unsafe test resource configuration", ex);
    }
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    if (Arrays.asList(env.getActiveProfiles()).contains("test")) {
      if (env.getActiveProfiles().length != 1)
        throw new IllegalStateException("test profile cannot be combined with another profile");
      validate(
          env.getProperty("spring.datasource.url", ""),
          env.getProperty("mfg.redis-prefix", ""),
          env.getProperty("spring.data.redis.port", Integer.class, 0));
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
