package io.github.lightmanufacturing.system.repository;

import io.github.lightmanufacturing.system.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface RoleRepository
    extends JpaRepository<SystemRole, Long>, JpaSpecificationExecutor<SystemRole> {
  Optional<SystemRole> findByCode(String code);

  boolean existsByCode(String code);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from SystemRole r where r.code='ADMIN'")
  SystemRole lockAdmin();
}
