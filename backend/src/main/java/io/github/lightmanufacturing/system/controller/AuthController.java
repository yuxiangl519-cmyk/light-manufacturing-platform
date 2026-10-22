package io.github.lightmanufacturing.system.controller;

import static io.github.lightmanufacturing.system.dto.ApiDtos.*;

import io.github.lightmanufacturing.system.application.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthenticationService authentication;

  public AuthController(AuthenticationService authentication) {
    this.authentication = authentication;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken csrf) {
    return authentication.csrf(csrf);
  }

  @PostMapping("/login")
  public CurrentUser login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    return authentication.login(body, request, response);
  }

  @GetMapping("/me")
  public CurrentUser me() {
    return authentication.me();
  }

  @GetMapping("/permissions")
  public Map<String, List<String>> permissions() {
    return authentication.permissions();
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    authentication.logout(request, response);
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(
      @Valid @RequestBody PasswordChange body,
      HttpServletRequest request,
      HttpServletResponse response) {
    authentication.password(body, request, response);
  }
}
