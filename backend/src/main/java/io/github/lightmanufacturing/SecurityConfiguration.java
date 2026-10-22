package io.github.lightmanufacturing;

import io.github.lightmanufacturing.system.application.*;
import io.github.lightmanufacturing.system.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.session.web.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  HttpSessionSecurityContextRepository securityContexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfTokens() {
    var repo = new HttpSessionCsrfTokenRepository();
    repo.setHeaderName("X-CSRF-TOKEN");
    return repo;
  }

  @Bean
  CookieSerializer cookieSerializer(Environment env) {
    var c = new DefaultCookieSerializer();
    c.setCookieName("LIGHT_MFG_SESSION");
    c.setCookiePath("/");
    c.setUseHttpOnlyCookie(true);
    c.setSameSite("Lax");
    c.setUseSecureCookie(
        env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
    return c;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf,
      SystemService system,
      ErrorSupport errors,
      Environment env)
      throws Exception {
    var handler = new CsrfTokenRequestAttributeHandler();
    http.securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true))
        .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
        .requestCache(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (req, res, ex) -> errors.write(res, 401, "UNAUTHENTICATED", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            errors.write(
                                res,
                                403,
                                ex instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                ex instanceof CsrfException ? "CSRF校验失败" : "没有操作权限")))
        .authorizeHttpRequests(
            c -> {
              c.requestMatchers(
                      org.springframework.http.HttpMethod.GET,
                      "/api/v1/auth/csrf",
                      "/actuator/health")
                  .permitAll();
              c.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/login")
                  .permitAll();
              c.requestMatchers(
                      "/api/v1/auth/me",
                      "/api/v1/auth/logout",
                      "/api/v1/auth/password",
                      "/api/v1/auth/permissions",
                      "/api/v1/system/**",
                      "/api/v1/materials",
                      "/api/v1/materials/**",
                      "/api/v1/customers",
                      "/api/v1/customers/**",
                      "/api/v1/suppliers",
                      "/api/v1/suppliers/**",
                      "/api/v1/warehouses",
                      "/api/v1/warehouses/**",
                      "/api/v1/boms",
                      "/api/v1/boms/**",
                      "/api/v1/purchase-orders",
                      "/api/v1/purchase-orders/**",
                      "/api/v1/sales-orders",
                      "/api/v1/sales-orders/**",
                      "/api/v1/production-orders",
                      "/api/v1/production-orders/**",
                      "/api/v1/stock-documents",
                      "/api/v1/stock-documents/**",
                      "/api/v1/stock-balances",
                      "/api/v1/workbench",
                      "/api/v1/ai/**",
                      "/api/v1/stock-ledgers")
                  .authenticated();
              if (Arrays.asList(env.getActiveProfiles()).contains("local")
                  || Arrays.asList(env.getDefaultProfiles()).contains("local")
                      && env.getActiveProfiles().length == 0)
                c.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .hasAuthority("system:api-docs:read");
              c.anyRequest().denyAll();
            });
    http.addFilterAfter(new FreshIdentityFilter(system, errors), SecurityContextHolderFilter.class);
    return http.build();
  }

  static final class FreshIdentityFilter extends OncePerRequestFilter {
    private final SystemService service;
    private final ErrorSupport errors;

    FreshIdentityFilter(SystemService service, ErrorSupport errors) {
      this.service = service;
      this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      var a = SecurityContextHolder.getContext().getAuthentication();
      if (a != null && a.getPrincipal() instanceof SessionPrincipal p) {
        try {
          var fresh = service.current(p);
          request.setAttribute(
              AuditIdentity.REQUEST_ATTRIBUTE,
              new AuditIdentity(fresh.principal().id(), fresh.user().displayName()));
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  fresh.principal(),
                  null,
                  fresh.user().permissions().stream().map(SimpleGrantedAuthority::new).toList()));
          SecurityContextHolder.setContext(context);
        } catch (ApiException e) {
          if (e.status != 401) throw e;
          SecurityContextHolder.clearContext();
          var session = request.getSession(false);
          if (session != null) session.invalidate();
          errors.write(response, 401, "UNAUTHENTICATED", "请重新登录");
          return;
        }
      }
      chain.doFilter(request, response);
    }
  }
}
