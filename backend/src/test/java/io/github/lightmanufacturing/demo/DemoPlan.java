package io.github.lightmanufacturing.demo;

import java.time.*;
import java.util.*;

/** Fixed, reproducible synthetic transactions; no claim of real factory transactions. */
public final class DemoPlan {
  public static final String BATCH = "DEMO-PC-20261020";

  public record Order(
      String key,
      String type,
      LocalDate date,
      int minute,
      int quantity,
      String status,
      int partner) {
    public Instant instant() {
      return date.atTime(minute / 60, minute % 60).toInstant(ZoneOffset.ofHours(8));
    }

    public String remark() {
      return BATCH + ":" + key + " | 模拟演示交易；硬件公开资料仅作参考，非真实企业经营记录";
    }
  }

  public static List<Order> orders() {
    List<Order> all = new ArrayList<>();
    LocalDate previous = LocalDate.parse("2026-08-22"), current = LocalDate.parse("2026-09-21");
    for (int i = 0; i < 3; i++)
      all.add(
          new Order(
              "P-P" + i,
              "PURCHASE",
              previous.plusDays(new int[] {0, 8, 17}[i]),
              420,
              i == 0 ? 20 : 10,
              "RECEIVED",
              i % 3));
    for (int i = 0; i < 4; i++)
      all.add(
          new Order(
              "P-M" + i,
              "PRODUCTION",
              previous.plusDays(new int[] {1, 9, 18, 26}[i]),
              540,
              i % 2 == 0 ? 8 : 7,
              "COMPLETED",
              0));
    int[] qty = {2, 3, 2, 3, 2, 3, 2, 4, 3}, days = {2, 4, 7, 10, 14, 19, 23, 27, 29};
    for (int i = 0; i < 9; i++)
      all.add(
          new Order("P-S" + i, "SALES", previous.plusDays(days[i]), 900, qty[i], "SHIPPED", i % 8));
    for (int i = 0; i < 10; i++)
      all.add(
          new Order("C-P" + i, "PURCHASE", current.plusDays(i * 3), 420, 16, "RECEIVED", i % 3));
    all.add(new Order("C-P10", "PURCHASE", current.plusDays(28), 450, 12, "CONFIRMED", 1));
    all.add(new Order("C-P11", "PURCHASE", current.plusDays(29), 450, 8, "DRAFT", 2));
    for (int i = 0; i < 21; i++)
      all.add(
          new Order(
              "C-M" + i,
              "PRODUCTION",
              current.plusDays(1 + i * 27 / 20),
              540,
              i < 3 ? 8 : 7,
              "COMPLETED",
              0));
    all.add(new Order("C-M21", "PRODUCTION", current.plusDays(29), 540, 5, "IN_PRODUCTION", 0));
    all.add(new Order("C-M22", "PRODUCTION", current.plusDays(29), 600, 5, "PENDING_INBOUND", 0));
    all.add(new Order("C-M23", "PRODUCTION", current.plusDays(29), 660, 5, "READY_TO_ISSUE", 0));
    for (int i = 0; i < 50; i++)
      all.add(
          new Order(
              "C-S" + i,
              "SALES",
              current.plusDays(1 + i * 28 / 49),
              900 + (i % 2) * 40,
              i < 8 ? 2 : 3,
              "SHIPPED",
              i % 8));
    for (int i = 0; i < 4; i++)
      all.add(
          new Order(
              "C-S" + (50 + i),
              "SALES",
              current.plusDays(29),
              1020 + i * 20,
              2,
              i == 3 ? "DRAFT" : "CONFIRMED",
              i));
    all.sort(Comparator.comparing(Order::instant).thenComparing(Order::key));
    return List.copyOf(all);
  }

  private DemoPlan() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Supply a CSV preview output path");
    var lines = new ArrayList<String>();
    lines.add("batch,key,type,date,localTime,quantity,status,partnerIndex,synthetic");
    for (var x : orders())
      lines.add(
          String.join(
              ",",
              BATCH,
              x.key(),
              x.type(),
              x.date().toString(),
              String.format("%02d:%02d", x.minute() / 60, x.minute() % 60),
              Integer.toString(x.quantity()),
              x.status(),
              Integer.toString(x.partner()),
              "true"));
    java.nio.file.Files.write(
        java.nio.file.Path.of(args[0]), lines, java.nio.charset.StandardCharsets.UTF_8);
  }
}
