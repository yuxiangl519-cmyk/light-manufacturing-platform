package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lightmanufacturing.erp.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ErpMoneyTest {
  @Test
  void acceptsExactRenminbiTaxInclusivePrices() throws Exception {
    assertEquals(new BigDecimal("12.30"), Money.price("12.3"));
    assertEquals(new BigDecimal("0.00"), Money.price("0"));
    assertEquals(new BigDecimal("9999999999999999.99"), Money.price("9999999999999999.99"));
  }

  @Test
  void rejectsFractionalCentsNegativeAndOverlargePrices() {
    for (String value : new String[] {"1.001", "-1", "1e2", "10000000000000000", " 1", "01", ""}) {
      assertThrows(ApiException.class, () -> Money.price(value));
    }
  }

  @Test
  void rejectsLineAndOrderOverflow() throws Exception {
    assertThrows(ApiException.class, () -> Money.amount(new BigDecimal("10000000000000000.00")));
  }
}
