package io.github.lightmanufacturing.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightmanufacturing.erp.application.*;
import io.github.lightmanufacturing.erp.dto.ErpDtos.*;
import io.github.lightmanufacturing.master.application.MasterService;
import io.github.lightmanufacturing.master.dto.MasterDtos.*;
import io.github.lightmanufacturing.mes.application.*;
import io.github.lightmanufacturing.mes.dto.BomDtos.*;
import io.github.lightmanufacturing.mes.dto.ProductionDtos;
import io.github.lightmanufacturing.shared.time.BusinessTime;
import io.github.lightmanufacturing.system.application.SystemService;
import io.github.lightmanufacturing.system.security.SessionPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in local maintenance command, NOT a normal test or an HTTP endpoint. Never deletes data. */
@SpringBootTest(properties = {"mfg.bootstrap-enabled=false"})
@EnabledIfEnvironmentVariable(named = "MFG_DEMO_IMPORT", matches = "true")
public class HistoricalDemoImport {
  @Autowired JdbcTemplate jdbc;
  @Autowired MasterService master;
  @Autowired BomService boms;
  @Autowired PurchaseOrderService purchases;
  @Autowired SalesOrderService sales;
  @Autowired ProductionService production;
  @Autowired SystemService system;
  @Autowired PasswordEncoder passwords;
  @Autowired PlatformTransactionManager manager;
  @Autowired EntityManager em;
  @Autowired ObjectMapper json;
  TransactionTemplate tx;
  String rawWarehouse, finishedWarehouse, bom;
  final List<String> materialIds = new ArrayList<>(),
      suppliers = new ArrayList<>(),
      customers = new ArrayList<>();
  final Map<String, Object> report = new LinkedHashMap<>();
  static final String PREFIX = "DM261020-";
  static final String[] NAMES = {
    "CPU Intel Core i3-14100盒装（演示）",
    "主板 ASUS PRIME H610M-K D4（演示）",
    "内存 Kingston KVR32N22S8/8 8GB（演示）",
    "SSD Kingston NV3 SNV3S/500G（演示）",
    "电源 航嘉GS400 额定300W（演示）",
    "机箱 航嘉BU560小黑（演示）",
    "标准办公台式机（演示）"
  };
  static final String[] CODES = {"CPU", "MB", "RAM", "SSD", "PSU", "CASE", "PC"};
  static final String[] SPECS = {
    "LGA1700；4核8线程；UHD730；盒装含散热器",
    "LGA1700；Micro-ATX；DDR4；14代CPU需核对BIOS",
    "8GB DDR4-3200 CL22；每台2条，共16GB",
    "500GB M.2 2280 PCIe NVMe；平台可能限速",
    "GS400；额定300W，非额定400W",
    "BU560小黑；支持Micro-ATX与ATX电源",
    "i3-14100/16GB双通道/500GB；仅主机；模拟演示"
  };
  // Hypothetical tax-inclusive purchase prices, NOT historical quotes or confirmed discounts.
  static final String[] PRICES = {"1099.00", "589.00", "249.00", "399.00", "185.00", "129.00"};

  @Test
  void importAndVerify() throws Exception {
    assertTrue(Boolean.getBoolean("mfg.demo.import"), "Explicit JVM import flag required");
    String database = jdbc.queryForObject("select database()", String.class);
    assertTrue(Set.of("mini_mfg_test", "mini_mfg_dev").contains(database));
    assertEquals(database, System.getenv("MFG_DEMO_DATABASE"));
    if (database.equals("mini_mfg_dev")) {
      Path backup = Path.of(Objects.requireNonNull(System.getenv("MFG_DEMO_BACKUP")));
      assertTrue(
          Files.isRegularFile(backup) && Files.size(backup) > 1000,
          "Verified project backup required");
      assertTrue(
          backup
              .toAbsolutePath()
              .normalize()
              .startsWith(Path.of("../.local/backups").toAbsolutePath().normalize()));
    }
    tx = new TransactionTemplate(manager);
    String lock = "light-mfg:demo:" + database;
    try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      try (PreparedStatement p = connection.prepareStatement("select get_lock(?,0)")) {
        p.setString(1, lock);
        try (ResultSet r = p.executeQuery()) {
          r.next();
          assertEquals(1, r.getInt(1), "Import already running");
        }
      }
      try {
        authenticate();
        var before = snapshot();
        report.put("database", database);
        report.put("batch", DemoPlan.BATCH);
        report.put("actualImportedAt", Instant.now().toString());
        report.put("syntheticTransactions", true);
        initialize();
        int created = 0, skipped = 0;
        for (var order : DemoPlan.orders()) {
          boolean done = Boolean.TRUE.equals(tx.execute(status -> process(order)));
          if (done) created++;
          else skipped++;
        }
        verify();
        for (var entry : before.entrySet()) {
          var old = entry.getValue();
          assertEquals(
              old.digest(),
              digest(entry.getKey(), old.maxId()),
              "Existing rows changed in " + entry.getKey());
        }
        report.put("existingRowsUnchanged", true);
        report.put("createdOrders", created);
        report.put("skippedOrders", skipped);
        report.put(
            "orderManifest",
            jdbc.queryForList(
                "select 'PURCHASE' type,id,order_no,status,created_at,total_amount,remark from"
                    + " purchase_order where remark like ? union all select"
                    + " 'SALES',id,order_no,status,created_at,total_amount,remark from sales_order"
                    + " where remark like ? order by created_at,id",
                DemoPlan.BATCH + ":%",
                DemoPlan.BATCH + ":%"));
        report.put(
            "productionManifest",
            jdbc.queryForList(
                "select id,order_no,status,planned_quantity,created_at,remark from production_order"
                    + " where remark like ? order by created_at,id",
                DemoPlan.BATCH + ":%"));
        Path out = Path.of("../.local/demo-data/" + database + "-result.json");
        Files.createDirectories(out.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.out.println(
            "DEMO VERIFIED: "
                + database
                + ", created="
                + created
                + ", skipped="
                + skipped
                + ", existing rows unchanged; report="
                + out);
      } finally {
        SecurityContextHolder.clearContext();
        try (PreparedStatement p = connection.prepareStatement("select release_lock(?)")) {
          p.setString(1, lock);
          p.execute();
        }
      }
    }
  }

  void authenticate() {
    var rows =
        jdbc.queryForList(
            "select id,password_hash,auth_version from sys_user where username='operator' and"
                + " enabled=true");
    assertEquals(1, rows.size(), "Enabled operator account required");
    var row = rows.get(0);
    assertTrue(
        passwords.matches(
            Objects.requireNonNull(System.getenv("MFG_OPERATOR_PASSWORD")),
            row.get("password_hash").toString()),
        "Operator credential mismatch; no account reset performed");
    var principal =
        new SessionPrincipal(
            ((Number) row.get("id")).longValue(), ((Number) row.get("auth_version")).longValue());
    var identity = system.current(principal);
    assertTrue(
        identity
            .user()
            .permissions()
            .containsAll(
                Set.of(
                    "master:material:write",
                    "master:customer:write",
                    "master:supplier:write",
                    "master:warehouse:write",
                    "mes:bom:write",
                    "mes:production:write",
                    "mes:production:confirm",
                    "mes:production:issue",
                    "mes:production:report",
                    "erp:purchase:write",
                    "erp:purchase:confirm",
                    "erp:purchase:receive",
                    "erp:sales:write",
                    "erp:sales:confirm",
                    "erp:sales:ship",
                    "wms:receipt:execute")),
        "Required operator permissions missing");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                principal,
                null,
                identity.user().permissions().stream().map(SimpleGrantedAuthority::new).toList()));
  }

  void initialize() {
    tx.executeWithoutResult(
        status ->
            step(
                Instant.parse("2026-08-21T01:00:00Z"),
                () -> {
                  for (int i = 0; i < 7; i++) {
                    final int index = i;
                    materialIds.add(
                        existing(
                            "material",
                            PREFIX + CODES[i],
                            NAMES[i],
                            () ->
                                master
                                    .saveMaterial(
                                        null,
                                        new MaterialWrite(
                                            null,
                                            PREFIX + CODES[index],
                                            NAMES[index],
                                            index == 6 ? "FINISHED_GOOD" : "RAW_MATERIAL",
                                            index == 2 ? "条" : index == 6 ? "台" : "件",
                                            SPECS[index],
                                            index == 2 ? "30" : index == 6 ? "8" : "15",
                                            true))
                                    .id()));
                  }
                  rawWarehouse =
                      existing(
                          "warehouse",
                          PREFIX + "RAW",
                          "演示·电脑零部件仓",
                          () ->
                              master
                                  .saveWarehouse(
                                      null,
                                      new WarehouseWrite(
                                          null,
                                          PREFIX + "RAW",
                                          "演示·电脑零部件仓",
                                          "虚构演示地址，无实际收货功能",
                                          true))
                                  .id());
                  finishedWarehouse =
                      existing(
                          "warehouse",
                          PREFIX + "FG",
                          "演示·办公整机仓",
                          () ->
                              master
                                  .saveWarehouse(
                                      null,
                                      new WarehouseWrite(
                                          null, PREFIX + "FG", "演示·办公整机仓", "虚构演示地址，无实际发货功能", true))
                                  .id());
                  String[] ss = {"演示·星桥电脑配件供应商", "演示·启航电子供应商", "演示·云杉硬件供应商"};
                  String[] cs = {
                    "演示·晨曦办公服务",
                    "演示·远航教育工作室",
                    "演示·青禾设计工作室",
                    "演示·启明咨询服务",
                    "演示·星河商贸",
                    "演示·松柏科技服务",
                    "演示·海岚物流服务",
                    "演示·知行培训中心"
                  };
                  for (int i = 0; i < ss.length; i++) {
                    String code = PREFIX + "SUP" + i, name = ss[i];
                    suppliers.add(
                        existing(
                            "supplier",
                            code,
                            name,
                            () ->
                                master
                                    .saveSupplier(
                                        null,
                                        new PartnerWrite(
                                            null, code, name, "", "", "虚构演示企业，无真实联系方式", true))
                                    .id()));
                  }
                  for (int i = 0; i < cs.length; i++) {
                    String code = PREFIX + "CUS" + i, name = cs[i];
                    customers.add(
                        existing(
                            "customer",
                            code,
                            name,
                            () ->
                                master
                                    .saveCustomer(
                                        null,
                                        new PartnerWrite(
                                            null, code, name, "", "", "虚构演示企业，无真实联系方式", true))
                                    .id()));
                  }
                  bom =
                      existing(
                          "bom",
                          PREFIX + "BOM",
                          "标准办公台式机固定BOM（演示）",
                          () -> {
                            List<ItemWrite> items = new ArrayList<>();
                            for (int i = 0; i < 6; i++)
                              items.add(new ItemWrite(materialIds.get(i), i == 2 ? "2" : "1"));
                            return boms.save(
                                    null,
                                    new BomWrite(
                                        null,
                                        PREFIX + "BOM",
                                        "标准办公台式机固定BOM（演示）",
                                        materialIds.get(6),
                                        true,
                                        items))
                                .id();
                          });
                  assertEquals(materialIds.get(6), boms.get(bom).productMaterialId());
                  assertEquals(6, boms.get(bom).items().size());
                  return null;
                }));
  }

  String existing(String table, String code, String name, Supplier<String> create) {
    assertTrue(Set.of("material", "warehouse", "supplier", "customer", "bom").contains(table));
    var rows = jdbc.queryForList("select id,name,enabled from " + table + " where code=?", code);
    if (rows.isEmpty()) return create.get();
    assertEquals(name, rows.get(0).get("name"), "Demo code collision");
    assertEquals(true, rows.get(0).get("enabled"), "Demo reference disabled");
    return rows.get(0).get("id").toString();
  }

  boolean process(DemoPlan.Order order) {
    String table =
        switch (order.type()) {
          case "PURCHASE" -> "purchase_order";
          case "SALES" -> "sales_order";
          default -> "production_order";
        };
    var rows =
        jdbc.queryForList("select id,status from " + table + " where remark=?", order.remark());
    if (!rows.isEmpty()) {
      assertEquals(1, rows.size());
      assertEquals(
          order.status(),
          rows.get(0).get("status"),
          "Existing demo order was changed; refusing to overwrite");
      return false;
    }
    Instant at = order.instant();
    if (order.type().equals("PURCHASE")) {
      List<LineWrite> lines = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        lines.add(
            new LineWrite(
                materialIds.get(i),
                Integer.toString(order.quantity() * (i == 2 ? 2 : 1)),
                PRICES[i]));
      var dto =
          step(
              at,
              () ->
                  purchases.create(
                      new PurchaseWrite(
                          suppliers.get(order.partner()), rawWarehouse, order.remark(), lines)));
      long id = Long.parseLong(dto.id());
      if (!order.status().equals("DRAFT"))
        step(at.plusSeconds(600), () -> purchases.confirm(id, purchases.detail(id).version()));
      if (order.status().equals("RECEIVED"))
        step(at.plusSeconds(1200), () -> purchases.receive(id, purchases.detail(id).version()));
    } else if (order.type().equals("SALES")) {
      String price = (3299 + 50 * (order.partner() % 5)) + ".00";
      var dto =
          step(
              at,
              () ->
                  sales.create(
                      new SalesWrite(
                          customers.get(order.partner()),
                          finishedWarehouse,
                          order.remark(),
                          List.of(
                              new LineWrite(
                                  materialIds.get(6),
                                  Integer.toString(order.quantity()),
                                  price)))));
      long id = Long.parseLong(dto.id());
      if (!order.status().equals("DRAFT"))
        step(at.plusSeconds(600), () -> sales.confirm(id, sales.detail(id).version()));
      if (order.status().equals("SHIPPED"))
        step(at.plusSeconds(1200), () -> sales.ship(id, sales.detail(id).version()));
    } else {
      var dto =
          step(
              at,
              () ->
                  production.create(
                      new ProductionDtos.Create(
                          materialIds.get(6),
                          bom,
                          Integer.toString(order.quantity()),
                          rawWarehouse,
                          finishedWarehouse,
                          order.remark())));
      long id = Long.parseLong(dto.id());
      step(at.plusSeconds(600), () -> production.confirm(id, production.detail(id).version()));
      if (!order.status().equals("READY_TO_ISSUE"))
        step(at.plusSeconds(1200), () -> production.issue(id, production.detail(id).version()));
      if (Set.of("PENDING_INBOUND", "COMPLETED").contains(order.status()))
        step(at.plusSeconds(1800), () -> production.report(id, production.detail(id).version()));
      if (order.status().equals("COMPLETED"))
        step(
            at.plusSeconds(2400),
            () -> {
              var doc =
                  production.detail(id).stockDocuments().stream()
                      .filter(d -> d.kind().equals("PRODUCTION_RECEIPT"))
                      .findFirst()
                      .orElseThrow();
              return production.executeReceipt(Long.parseLong(doc.id()), doc.version());
            });
    }
    assertEquals(
        order.status(),
        jdbc.queryForObject(
            "select status from " + table + " where remark=?", String.class, order.remark()));
    return true;
  }

  <T> T step(Instant instant, Supplier<T> work) {
    try (var ignored = BusinessTime.at(instant)) {
      T result = work.get();
      em.flush();
      em.clear();
      return result;
    }
  }

  void verify() {
    List<Map<String, Object>> balances =
        jdbc.queryForList(
            "select m.code,b.quantity,w.code warehouse from stock_balance b join material m on"
                + " m.id=b.material_id join warehouse w on w.id=b.warehouse_id where m.code like ?"
                + " order by m.code",
            PREFIX + "%");
    assertEquals(7, balances.size());
    for (var row : balances) {
      String code = row.get("code").toString();
      int expected = code.endsWith("PC") ? 14 : code.endsWith("RAM") ? 20 : 10;
      assertEquals(
          0,
          new BigDecimal(row.get("quantity").toString()).compareTo(BigDecimal.valueOf(expected)),
          code);
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_balance b join material m on m.id=b.material_id where"
                + " m.code like ? and b.quantity<>(select coalesce(sum(l.change_quantity),0) from"
                + " stock_ledger l where l.balance_id=b.id)",
            Integer.class,
            PREFIX + "%"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from (select l.after_quantity,lead(l.before_quantity) over(partition"
                + " by l.balance_id order by l.id) next_before from stock_ledger l join material m"
                + " on m.id=l.material_id where m.code like ?) x where next_before is not null and"
                + " after_quantity<>next_before",
            Integer.class,
            PREFIX + "%"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_ledger l join material m on m.id=l.material_id join"
                + " stock_document d on d.id=l.stock_document_id where m.code like ? and"
                + " (l.occurred_at<>d.executed_at or l.after_quantity<0 or l.before_quantity<0 or"
                + " d.status<>'EXECUTED')",
            Integer.class,
            PREFIX + "%"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from stock_document where warehouse_id=? and kind='PRODUCTION_RECEIPT'"
                + " and status='PENDING'",
            Integer.class,
            finishedWarehouse));
    assertEquals(
        5,
        jdbc.queryForObject(
            "select sum(i.quantity) from stock_document_item i join stock_document d on"
                + " d.id=i.stock_document_id where d.warehouse_id=? and d.status='PENDING'",
            Integer.class,
            finishedWarehouse));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from stock_document d join stock_ledger l on l.stock_document_id=d.id"
                + " where d.warehouse_id=? and d.status='PENDING'",
            Integer.class,
            finishedWarehouse));
    for (var order : DemoPlan.orders()) {
      String table =
          switch (order.type()) {
            case "PURCHASE" -> "purchase_order";
            case "SALES" -> "sales_order";
            default -> "production_order";
          };
      assertEquals(
          Timestamp.from(order.instant()),
          jdbc.queryForObject(
              "select created_at from " + table + " where remark=?",
              Timestamp.class,
              order.remark()));
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from " + table + " where remark=? and updated_at<created_at",
              Integer.class,
              order.remark()));
    }
    report.put("balances", balances);
    report.put(
        "periodSales",
        jdbc.queryForList(
            "select case when created_at<'2026-09-20 16:00:00' then 'previous' else 'current' end"
                + " period,count(*) shippedOrders,sum(total_amount) salesAmount from sales_order"
                + " where remark like ? and status='SHIPPED' group by period",
            DemoPlan.BATCH + ":%"));
    report.put(
        "todos",
        jdbc.queryForList(
            "select status,count(*) orders from production_order where remark like ? group by"
                + " status",
            DemoPlan.BATCH + ":%"));
    report.put(
        "stockDocuments",
        jdbc.queryForObject(
            "select count(*) from stock_document where warehouse_id in (?,?)",
            Integer.class,
            rawWarehouse,
            finishedWarehouse));
    report.put(
        "ledgerRows",
        jdbc.queryForObject(
            "select count(*) from stock_ledger where warehouse_id in (?,?)",
            Integer.class,
            rawWarehouse,
            finishedWarehouse));
    report.put(
        "checks",
        List.of(
            "106 orders",
            "historical created dates",
            "stock balance equals ledger sum",
            "continuous ledger chain",
            "ledger time equals document execution time",
            "pending receipt has no ledger",
            "no existing rows changed"));
  }

  record Snapshot(Long maxId, String digest) {}

  Map<String, Snapshot> snapshot() throws Exception {
    Map<String, Snapshot> result = new LinkedHashMap<>();
    for (String table :
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema=database() and"
                + " table_name<>'flyway_schema_history' order by table_name",
            String.class)) {
      assertTrue(table.matches("[a-z][a-z0-9_]*"));
      boolean hasId =
          jdbc.queryForObject(
                  "select count(*) from information_schema.columns where table_schema=database()"
                      + " and table_name=? and column_name='id'",
                  Integer.class,
                  table)
              > 0;
      Long max =
          hasId
              ? jdbc.queryForObject("select coalesce(max(id),0) from " + table, Long.class)
              : null;
      result.put(table, new Snapshot(max, digest(table, max)));
    }
    return result;
  }

  String digest(String table, Long max) throws Exception {
    var rows =
        max == null
            ? jdbc.queryForList("select * from " + table + " order by 1,2")
            : jdbc.queryForList("select * from " + table + " where id<=? order by id", max);
    // Hashes only are compared; never write existing rows or account hashes to a report/log.
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(rows)));
  }
}
