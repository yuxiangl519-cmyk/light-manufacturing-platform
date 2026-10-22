package io.github.lightmanufacturing.system.repository;

import io.github.lightmanufacturing.system.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface PermissionRepository extends JpaRepository<Permission, String> {
  List<Permission> findAllByOrderBySortOrderAsc();
}
