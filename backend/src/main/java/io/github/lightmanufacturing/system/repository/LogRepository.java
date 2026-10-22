package io.github.lightmanufacturing.system.repository;

import io.github.lightmanufacturing.system.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface LogRepository
    extends JpaRepository<OperationLog, Long>, JpaSpecificationExecutor<OperationLog> {}
