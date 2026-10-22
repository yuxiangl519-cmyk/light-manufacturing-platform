package io.github.lightmanufacturing.erp.domain;

import java.math.BigDecimal;

public record OrderLine(
    long id,
    int lineNo,
    long materialId,
    String materialCode,
    String materialName,
    String unit,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal lineAmount) {}
