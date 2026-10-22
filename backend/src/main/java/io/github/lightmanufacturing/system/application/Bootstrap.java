package io.github.lightmanufacturing.system.application;

import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.system.domain.*;
import io.github.lightmanufacturing.system.repository.*;
import io.github.lightmanufacturing.system.security.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements ApplicationRunner {
  private final UserRepository users;
  private final RoleRepository roles;
  private final Environment env;
  private final PasswordEncoder encoder;

  public Bootstrap(
      UserRepository users, RoleRepository roles, Environment env, PasswordEncoder encoder) {
    this.users = users;
    this.roles = roles;
    this.env = env;
    this.encoder = encoder;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!env.getProperty("mfg.bootstrap-enabled", Boolean.class, false)) return;
    roles.lockAdmin();
    create("admin", "ADMIN", "MFG_ADMIN_PASSWORD");
    create("operator", "OPERATOR", "MFG_OPERATOR_PASSWORD");
    create("viewer", "VIEWER", "MFG_VIEWER_PASSWORD");
  }

  private void create(String username, String role, String passwordVariable) {
    if (users.existsByUsername(username)) return;
    String password = env.getProperty(passwordVariable);
    Policy.password(password);
    SystemUser user = new SystemUser();
    user.setUsername(username);
    user.setDisplayName(username);
    user.setPasswordHash(encoder.encode(password));
    user.getRoles().add(roles.findByCode(role).orElseThrow());
    users.save(user);
  }
}
