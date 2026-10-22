package io.github.lightmanufacturing.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DemoPlanTest {
  @Test
  void exactPeriodsCountsAndQuantities() {
    var plan = DemoPlan.orders();
    assertEquals(106, plan.size());
    assertEquals(
        90, plan.stream().filter(x -> !x.date().isBefore(LocalDate.parse("2026-09-21"))).count());
    assertEquals(
        200,
        plan.stream()
            .filter(x -> x.type().equals("PURCHASE") && x.status().equals("RECEIVED"))
            .mapToInt(DemoPlan.Order::quantity)
            .sum());
    assertEquals(
        180,
        plan.stream()
            .filter(x -> x.type().equals("PRODUCTION") && x.status().equals("COMPLETED"))
            .mapToInt(DemoPlan.Order::quantity)
            .sum());
    assertEquals(
        166,
        plan.stream()
            .filter(x -> x.status().equals("SHIPPED"))
            .mapToInt(DemoPlan.Order::quantity)
            .sum());
    assertEquals(plan.size(), plan.stream().map(DemoPlan.Order::key).distinct().count());
    assertEquals(plan, DemoPlan.orders());
  }

  @Test
  void chronologyNeverOverdrawsAndLeavesUsefulTodos() {
    int raw = 0, finished = 0;
    for (var x : DemoPlan.orders()) {
      if (x.status().equals("RECEIVED")) raw += x.quantity();
      if (x.type().equals("PRODUCTION") && !x.status().equals("READY_TO_ISSUE"))
        raw -= x.quantity();
      if (x.status().equals("COMPLETED")) finished += x.quantity();
      if (x.status().equals("SHIPPED")) finished -= x.quantity();
      assertTrue(raw >= 0 && finished >= 0, x.key());
      assertTrue(
          !x.date().isBefore(LocalDate.parse("2026-08-22"))
              && !x.date().isAfter(LocalDate.parse("2026-10-20")));
    }
    assertEquals(10, raw);
    assertEquals(14, finished);
    assertEquals(
        1, DemoPlan.orders().stream().filter(x -> x.status().equals("IN_PRODUCTION")).count());
    assertEquals(
        1, DemoPlan.orders().stream().filter(x -> x.status().equals("PENDING_INBOUND")).count());
  }
}
