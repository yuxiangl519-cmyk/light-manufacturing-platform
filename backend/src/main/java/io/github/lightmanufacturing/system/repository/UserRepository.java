package io.github.lightmanufacturing.system.repository;

import io.github.lightmanufacturing.system.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserRepository
    extends JpaRepository<SystemUser, Long>, JpaSpecificationExecutor<SystemUser> {
  Optional<SystemUser> findByUsername(String username);

  boolean existsByUsername(String username);

  @Query("select count(u) from SystemUser u join u.roles r where r.id=:roleId and u.enabled=true")
  long enabledAdmins(@Param("roleId") Long roleId);

  @Query("select count(u) from SystemUser u join u.roles r where r.id=:roleId")
  long assigned(@Param("roleId") Long roleId);
}
