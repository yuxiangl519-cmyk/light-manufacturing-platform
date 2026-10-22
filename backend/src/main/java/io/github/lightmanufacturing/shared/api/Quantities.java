package io.github.lightmanufacturing.shared.api;

import io.github.lightmanufacturing.ApiException;
import java.math.BigDecimal;

public final class Quantities {
  private Quantities() {}

  public static BigDecimal positive(String v) {
    if (v == null || !v.matches("[1-9][0-9]{0,11}"))
      throw ApiException.invalid("数量须为不超过12位的正整数字符串");
    return new BigDecimal(v);
  }

  public static BigDecimal nonNegative(String v) {
    if ("0".equals(v)) return BigDecimal.ZERO;
    return positive(v);
  }

  public static BigDecimal positive(BigDecimal v) {
    if (v == null
        || v.signum() <= 0
        || v.stripTrailingZeros().scale() > 0
        || v.compareTo(new BigDecimal("999999999999")) > 0)
      throw ApiException.invalid("数量须为不超过12位的正整数");
    return v.setScale(0);
  }
}
