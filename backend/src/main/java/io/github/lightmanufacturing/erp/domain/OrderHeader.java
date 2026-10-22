package io.github.lightmanufacturing.erp.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderHeader(
    long id,
    long version,
    String orderNo,
    long partnerId,
    String partnerName,
    long warehouseId,
    String warehouseName,
    String status,
    BigDecimal totalAmount,
    String remark,
    Instant createdAt,
    Instant updatedAt,
    String createdByName,
    Instant confirmedAt,
    Instant doneAt,
    Instant cancelledAt) {}
