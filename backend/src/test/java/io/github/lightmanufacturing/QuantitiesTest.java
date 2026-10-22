package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lightmanufacturing.shared.api.Quantities;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantitiesTest {
  @Test
  void rejectsFractionsAndExponentsAndOverflow() {
    for (String v : new String[] {"0", "-1", "1.0", "1e2", "1000000000000", " 1"})
      assertThrows(ApiException.class, () -> Quantities.positive(v));
  }

  @Test
  void multiplicationCannotOverflowSqlPrecision() {
    assertThrows(
        ApiException.class,
        () -> Quantities.positive(new BigDecimal("999999999999").multiply(BigDecimal.TEN)));
    assertEquals(new BigDecimal("0"), Quantities.nonNegative("0"));
  }
}
