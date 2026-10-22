package io.github.lightmanufacturing.system.application;

import io.github.lightmanufacturing.ApiException;
import io.github.lightmanufacturing.system.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public identity projection for business modules; no credentials or persistence entities escape.
 */
@Service
@Transactional(readOnly = true)
public class OperatorDirectory {
  private final UserRepository users;

  public OperatorDirectory(UserRepository users) {
    this.users = users;
  }

  public String displayName(long id) {
    return users.findById(id).orElseThrow(() -> ApiException.invalid("库存操作人不存在")).getDisplayName();
  }
}
