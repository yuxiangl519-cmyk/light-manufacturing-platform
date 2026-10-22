package io.github.lightmanufacturing.master.application;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

final class MasterQuery {
  private MasterQuery() {}

  static Pageable page(int page, int size, String sort) {
    if (page < 1 || size < 1 || size > 100) throw ApiException.invalid("分页范围错误");
    String[] s = sort.split(",");
    if (s.length != 2
        || !java.util.Set.of("createdAt", "code", "name", "updatedAt").contains(s[0])
        || !java.util.Set.of("asc", "desc").contains(s[1])) throw ApiException.invalid("排序字段错误");
    return PageRequest.of(
        page - 1, size, Sort.by(Sort.Direction.fromString(s[1]), s[0]).and(Sort.by("id")));
  }

  static <T> Specification<T> filter(String q, Boolean enabled) {
    if (q != null && q.length() > 100) throw ApiException.invalid("搜索内容超长");
    return (r, x, b) -> {
      var active = enabled == null ? b.conjunction() : b.equal(r.get("enabled"), enabled);
      if (q == null || q.isBlank()) return active;
      String p =
          "%"
              + q.trim()
                  .toLowerCase(java.util.Locale.ROOT)
                  .replace("\\", "\\\\")
                  .replace("%", "\\%")
                  .replace("_", "\\_")
              + "%";
      return b.and(
          active,
          b.or(b.like(b.lower(r.get("code")), p, '\\'), b.like(b.lower(r.get("name")), p, '\\')));
    };
  }

  static void version(AuditedEntity e, Long v) {
    if (v == null || v < 0) throw ApiException.invalid("修改须提供非负version");
    if (!v.equals(e.getVersion())) throw ApiException.conflict("VERSION_CONFLICT", "记录已变更，请刷新");
  }

  static String optional(String v, int max) {
    if (v == null || v.length() > max) throw ApiException.invalid("可选文本不能为空值或超长");
    return v.trim();
  }
}
