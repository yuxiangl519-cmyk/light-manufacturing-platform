package io.github.lightmanufacturing.wms.domain;

import io.github.lightmanufacturing.ApiException;
import java.math.BigDecimal;

public final class StockQuantity {
  private static final BigDecimal MAX = new BigDecimal("999999999999");

  private StockQuantity() {}

  public static BigDecimal positive(BigDecimal value) {
    if (value == null || value.signum() <= 0) throw ApiException.invalid("库存数量须为正整数");
    return balance(value);
  }

  public static BigDecimal balance(BigDecimal value) {
    if (value == null
        || value.signum() < 0
        || value.compareTo(MAX) > 0
        || value.stripTrailingZeros().scale() > 0)
      throw ApiException.invalid("库存数量须为0至999999999999的整数");
    return value.setScale(0);
  }
}
