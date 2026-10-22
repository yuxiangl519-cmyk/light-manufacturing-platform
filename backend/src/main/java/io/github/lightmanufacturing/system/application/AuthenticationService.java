package io.github.lightmanufacturing.system.application;

import static io.github.lightmanufacturing.system.dto.ApiDtos.*;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.system.domain.*;
import io.github.lightmanufacturing.system.repository.*;
import io.github.lightmanufacturing.system.security.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@org.springframework.stereotype.Service
public class AuthenticationService {
  private final SystemService service;
  private final LoginLimiter limiter;
  private final HttpSessionSecurityContextRepository contexts;
  private final HttpSessionCsrfTokenRepository tokens;

  public AuthenticationService(
      SystemService service,
      LoginLimiter limiter,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository tokens) {
    this.service = service;
    this.limiter = limiter;
    this.contexts = contexts;
    this.tokens = tokens;
  }

  public Map<String, String> csrf(CsrfToken csrf) {
    return Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken());
  }

  public CurrentUser login(Login body, HttpServletRequest request, HttpServletResponse response) {
    String ip = request.getRemoteAddr();
    limiter.check(body.username(), ip);
    SystemService.Identity identity;
    try {
      identity = service.authenticate(body.username(), body.password());
    } catch (ApiException e) {
      if (e.status == 401) limiter.failure(body.username(), ip);
      throw e;
    }
    limiter.success(body.username(), ip);
    request.getSession(true);
    request.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(identity.principal(), null, List.of()));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    tokens.saveToken(null, request, response);
    service.loginAudit();
    return identity.user();
  }

  public CurrentUser me() {
    return service.current(principal()).user();
  }

  public Map<String, List<String>> permissions() {
    return Map.of("permissions", service.current(principal()).user().permissions());
  }

  public void logout(HttpServletRequest request, HttpServletResponse response) {
    service.logoutAudit();
    endSession(request, response);
  }

  public void password(
      PasswordChange body, HttpServletRequest request, HttpServletResponse response) {
    service.changePassword(principal(), body);
    endSession(request, response);
  }

  private SessionPrincipal principal() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof SessionPrincipal p))
      throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
    return p;
  }

  private void endSession(HttpServletRequest request, HttpServletResponse response) {
    tokens.saveToken(null, request, response);
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("LIGHT_MFG_SESSION", "")
            .path("/")
            .httpOnly(true)
            .sameSite("Lax")
            .secure(request.isSecure())
            .maxAge(0)
            .build()
            .toString());
  }
}
