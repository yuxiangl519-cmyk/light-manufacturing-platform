package io.github.lightmanufacturing.erp.domain;

import io.github.lightmanufacturing.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Renminbi tax-inclusive amounts; no tax or accounting calculations. */
public final class Money {
  private Money() {}

  private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");

  public static BigDecimal price(String value) {
    if (value == null || !value.matches("(0|[1-9][0-9]{0,15})(\\.[0-9]{1,2})?"))
      throw ApiException.invalid("含税单价须为非负金额，最多16位整数和两位小数");
    return amount(new BigDecimal(value));
  }

  public static BigDecimal amount(BigDecimal value) {
    if (value == null || value.signum() < 0 || value.compareTo(MAX) > 0)
      throw ApiException.invalid("金额超出DECIMAL(18,2)范围");
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
