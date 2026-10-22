package io.github.lightmanufacturing.ai;

import io.github.lightmanufacturing.ErrorSupport;
import io.github.lightmanufacturing.ai.application.AiSettings;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class AiSecurityConfiguration {
  @Bean @Order(1)
  SecurityFilterChain aiInternalSecurity(HttpSecurity http,AiSettings settings,ErrorSupport errors)throws Exception {
    http.securityMatcher("/internal/ai/**").csrf(c->c.disable()).requestCache(c->c.disable())
      .sessionManagement(c->c.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(c->c.anyRequest().authenticated())
      .exceptionHandling(c->c.authenticationEntryPoint((req,res,ex)->errors.write(res,401,"UNAUTHENTICATED","服务认证失败")));
    http.addFilterAfter(new OncePerRequestFilter(){
      protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        if(!settings.authentic(req.getHeader("Authorization"))){errors.write(res,401,"UNAUTHENTICATED","服务认证失败");return;}
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ai-service",null,java.util.List.of()));SecurityContextHolder.setContext(context);chain.doFilter(req,res);
      }
    },SecurityContextHolderFilter.class);
    return http.build();
  }
}
