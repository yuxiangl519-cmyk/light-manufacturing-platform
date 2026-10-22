package io.github.lightmanufacturing;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Policy {
  private Policy() {}

  public static Set<String> permissions() {
    Set<String> p = new LinkedHashSet<>();
    add(p, "system:user", "read", "write");
    add(p, "system:role", "read", "write");
    add(p, "system:log", "read");
    add(p, "system:api-docs", "read");
    for (String resource : List.of("material", "customer", "supplier", "warehouse"))
      add(p, "master:" + resource, "read", "write");
    add(p, "erp:purchase", "read", "write", "confirm", "receive");
    add(p, "erp:sales", "read", "write", "confirm", "ship");
    add(p, "mes:bom", "read", "write");
    add(p, "mes:production", "read", "write", "confirm", "issue", "report");
    add(p, "wms:document", "read");
    add(p, "wms:stock", "read");
    add(p, "wms:ledger", "read");
    add(p, "wms:receipt", "execute");
    add(p, "workbench", "read");
    return Collections.unmodifiableSet(p);
  }

  private static void add(Set<String> p, String prefix, String... actions) {
    for (String a : actions) p.add(prefix + ":" + a);
  }

  public static void password(String value) {
    if (value == null
        || value.codePointCount(0, value.length()) < 12
        || value.codePointCount(0, value.length()) > 64
        || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiException.invalid("密码须为12至64字符且UTF-8不超过72字节");
  }

  public static String username(String value) {
    String v = text(value, 50, "用户名").toLowerCase(Locale.ROOT);
    if (!v.matches("[a-z0-9_.-]+")) throw ApiException.invalid("用户名仅允许字母、数字、点、横线和下划线");
    return v;
  }

  public static String text(String value, int max, String label) {
    if (value == null || value.trim().isEmpty() || value.trim().length() > max)
      throw ApiException.invalid(label + "不能为空或超长");
    return value.trim();
  }

  public static long id(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}"))
      throw ApiException.invalid("ID须为正整数字符串");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw ApiException.invalid("ID超出范围");
    }
  }

  public static void validatePermissions(Set<String> codes) {
    if (codes == null || !permissions().containsAll(codes)) throw ApiException.invalid("权限点不存在");
    for (String code : codes) {
      if (code.endsWith(":read")) continue;
      String read =
          code.equals("wms:receipt:execute")
              ? "wms:document:read"
              : code.substring(0, code.lastIndexOf(':')) + ":read";
      if (!codes.contains(read)) throw ApiException.invalid(code + "需要" + read);
    }
    Map<String, Set<String>> extra =
        Map.of(
            "mes:bom:write",
            Set.of("master:material:read"),
            "mes:production:write",
            Set.of("master:material:read", "master:warehouse:read", "mes:bom:read"),
            "erp:purchase:write",
            Set.of("master:material:read", "master:supplier:read", "master:warehouse:read"),
            "erp:sales:write",
            Set.of("master:material:read", "master:customer:read", "master:warehouse:read"));
    extra.forEach(
        (action, reads) -> {
          if (codes.contains(action) && !codes.containsAll(reads))
            throw ApiException.invalid(action + "缺少关联资料查看权限");
        });
  }
}
