package io.github.lightmanufacturing.system.application;

import static io.github.lightmanufacturing.system.dto.ApiDtos.*;

import io.github.lightmanufacturing.*;
import io.github.lightmanufacturing.shared.api.PageResponse;
import io.github.lightmanufacturing.shared.persistence.AuditedEntity;
import io.github.lightmanufacturing.system.domain.*;
import io.github.lightmanufacturing.system.repository.*;
import io.github.lightmanufacturing.system.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SystemService {
  private final UserRepository users;
  private final RoleRepository roles;
  private final PermissionRepository permissions;
  private final LogRepository logs;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final String dummyPasswordHash;

  public SystemService(
      UserRepository users,
      RoleRepository roles,
      PermissionRepository permissions,
      LogRepository logs,
      PasswordEncoder encoder,
      AuditService audit) {
    this.users = users;
    this.roles = roles;
    this.permissions = permissions;
    this.logs = logs;
    this.encoder = encoder;
    this.audit = audit;
    this.dummyPasswordHash = encoder.encode(UUID.randomUUID().toString());
  }

  public record Identity(SessionPrincipal principal, CurrentUser user) {}

  public Identity current(SessionPrincipal p) {
    SystemUser u = user(p.id());
    if (!u.getEnabled() || u.getAuthVersion() != p.authVersion())
      throw new ApiException(401, "UNAUTHENTICATED", "请重新登录");
    return identity(u);
  }

  public Identity authenticate(String username, String password) {
    String normalized = Policy.username(username);
    SystemUser u = users.findByUsername(normalized).orElse(null);
    String hash = u == null ? dummyPasswordHash : u.getPasswordHash();
    boolean matches =
        password != null
            && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
            && encoder.matches(password, hash);
    if (u == null || !u.getEnabled() || !matches)
      throw new ApiException(401, "UNAUTHENTICATED", "用户名或密码错误");
    return identity(u);
  }

  private Identity identity(SystemUser u) {
    List<String> grants =
        u.getRoles().stream()
            .flatMap(r -> r.getPermissions().stream())
            .distinct()
            .sorted()
            .toList();
    return new Identity(
        new SessionPrincipal(u.getId(), u.getAuthVersion()),
        new CurrentUser(
            u.getId().toString(),
            u.getUsername(),
            u.getDisplayName(),
            u.getRoles().stream().map(r -> r.getCode()).sorted().toList(),
            grants));
  }

  public UserDto userDto(String id) {
    return dto(user(Policy.id(id)));
  }

  public RoleDto roleDto(String id) {
    return dto(role(Policy.id(id)));
  }

  public PageResponse<UserDto> users(String q, Boolean enabled, int page, int size, String sort) {
    var spec =
        (Specification<SystemUser>)
            (r, query, b) ->
                b.and(
                    q == null
                        ? b.conjunction()
                        : b.or(
                            b.like(b.lower(r.get("username")), like(q), '\\'),
                            b.like(b.lower(r.get("displayName")), like(q), '\\')),
                    enabled == null ? b.conjunction() : b.equal(r.get("enabled"), enabled));
    var p = users.findAll(spec, page(page, size, sort));
    return new PageResponse<>(
        p.getContent().stream().map(this::dto).toList(),
        page,
        size,
        Long.toString(p.getTotalElements()));
  }

  public PageResponse<RoleDto> roles(String q, int page, int size, String sort) {
    var spec =
        (Specification<SystemRole>)
            (r, query, b) ->
                q == null
                    ? b.conjunction()
                    : b.or(
                        b.like(b.lower(r.get("code")), like(q), '\\'),
                        b.like(b.lower(r.get("name")), like(q), '\\'));
    var p = roles.findAll(spec, page(page, size, sort));
    return new PageResponse<>(
        p.getContent().stream().map(this::dto).toList(),
        page,
        size,
        Long.toString(p.getTotalElements()));
  }

  public List<PermissionDto> permissions() {
    return permissions.findAllByOrderBySortOrderAsc().stream()
        .map(p -> new PermissionDto(p.getCode(), p.getName(), p.getModule()))
        .toList();
  }

  @Transactional
  public UserDto createUser(UserCreate body) {
    roles.lockAdmin();
    if (!body.roleIds().isEmpty()) require("system:role:read");
    String username = Policy.username(body.username());
    if (users.existsByUsername(username)) throw ApiException.conflict("DUPLICATE_CODE", "用户名已存在");
    Policy.password(body.initialPassword());
    SystemUser u = new SystemUser();
    u.setUsername(username);
    u.setDisplayName(Policy.text(body.displayName(), 80, "姓名"));
    u.setPasswordHash(encoder.encode(body.initialPassword()));
    u.setEnabled(body.enabled());
    u.setRoles(roleSet(body.roleIds()));
    users.saveAndFlush(u);
    audit.success("USER_CREATE", "USER", u.getId());
    return dto(u);
  }

  @Transactional
  public UserDto updateUser(String id, UserUpdate body) {
    SystemRole admin = roles.lockAdmin();
    SystemUser u = user(Policy.id(id));
    version(u, body.version());
    u.setDisplayName(Policy.text(body.displayName(), 80, "姓名"));
    u.setEnabled(body.enabled());
    preserveAdmin(admin, u);
    users.flush();
    audit.success("USER_UPDATE", "USER", u.getId());
    return dto(u);
  }

  @Transactional
  public UserDto userRoles(String id, UserRoles body) {
    SystemRole admin = roles.lockAdmin();
    SystemUser u = user(Policy.id(id));
    version(u, body.version());
    u.setRoles(roleSet(body.roleIds()));
    u.setUpdatedAt(Instant.now());
    preserveAdmin(admin, u);
    users.flush();
    audit.success("USER_ROLES", "USER", u.getId());
    return dto(u);
  }

  @Transactional
  public void resetPassword(String id, PasswordReset body) {
    roles.lockAdmin();
    SystemUser u = user(Policy.id(id));
    version(u, body.version());
    Policy.password(body.newPassword());
    u.setPasswordHash(encoder.encode(body.newPassword()));
    u.setAuthVersion(u.getAuthVersion() + 1);
    audit.success("PASSWORD_RESET", "USER", u.getId());
  }

  @Transactional
  public void changePassword(SessionPrincipal p, PasswordChange body) {
    roles.lockAdmin();
    SystemUser u = user(p.id());
    if (!encoder.matches(body.currentPassword(), u.getPasswordHash()))
      throw ApiException.invalid("当前密码错误");
    Policy.password(body.newPassword());
    u.setPasswordHash(encoder.encode(body.newPassword()));
    u.setAuthVersion(u.getAuthVersion() + 1);
    audit.success("PASSWORD_CHANGE", "USER", u.getId());
  }

  @Transactional
  public RoleDto createRole(RoleWrite body) {
    String code = Policy.text(body.code(), 40, "角色编码");
    if (!code.matches("[A-Z][A-Z0-9_]{0,39}")) throw ApiException.invalid("角色编码须为大写字母数字或下划线");
    if (roles.existsByCode(code)) throw ApiException.conflict("DUPLICATE_CODE", "角色编码已存在");
    SystemRole r = new SystemRole();
    r.setCode(code);
    r.setName(Policy.text(body.name(), 80, "角色名"));
    r.setDescription(body.description().trim());
    roles.saveAndFlush(r);
    audit.success("ROLE_CREATE", "ROLE", r.getId());
    return dto(r);
  }

  @Transactional
  public RoleDto updateRole(String id, RoleWrite body) {
    roles.lockAdmin();
    SystemRole r = role(Policy.id(id));
    version(r, body.version());
    if (!r.getCode().equals(body.code())) throw ApiException.invalid("角色编码不可修改");
    r.setName(Policy.text(body.name(), 80, "角色名"));
    r.setDescription(body.description().trim());
    roles.flush();
    audit.success("ROLE_UPDATE", "ROLE", r.getId());
    return dto(r);
  }

  @Transactional
  public RoleDto rolePermissions(String id, RolePermissions body) {
    roles.lockAdmin();
    SystemRole r = role(Policy.id(id));
    version(r, body.version());
    if (r.getCode().equals("ADMIN") && !body.permissionCodes().equals(Policy.permissions()))
      throw ApiException.conflict("INVALID_STATE", "ADMIN权限全集固定");
    Policy.validatePermissions(body.permissionCodes());
    r.setPermissions(new HashSet<>(body.permissionCodes()));
    r.setUpdatedAt(Instant.now());
    roles.flush();
    audit.success("ROLE_PERMISSIONS", "ROLE", r.getId());
    return dto(r);
  }

  @Transactional
  public void deleteRole(String id, long version) {
    roles.lockAdmin();
    SystemRole r = role(Policy.id(id));
    version(r, version);
    if (r.getBuiltin()) throw ApiException.conflict("INVALID_STATE", "内置角色不可删除");
    if (users.assigned(r.getId()) > 0) throw ApiException.conflict("REFERENCE_IN_USE", "角色仍被用户使用");
    roles.delete(r);
    roles.flush();
    audit.success("ROLE_DELETE", "ROLE", r.getId());
  }

  public PageResponse<LogDto> logs(
      String actorId,
      String module,
      Boolean success,
      Instant from,
      Instant to,
      int page,
      int size) {
    Long actor = actorId == null ? null : Policy.id(actorId);
    if (module != null && module.length() > 30) throw ApiException.invalid("模块名过长");
    if (from != null && to != null && !from.isBefore(to)) throw ApiException.invalid("时间范围无效");
    Specification<OperationLog> spec =
        (r, q, b) ->
            b.and(
                actor == null ? b.conjunction() : b.equal(r.get("actorId"), actor),
                module == null ? b.conjunction() : b.equal(r.get("module"), module),
                success == null ? b.conjunction() : b.equal(r.get("success"), success),
                from == null ? b.conjunction() : b.greaterThanOrEqualTo(r.get("occurredAt"), from),
                to == null ? b.conjunction() : b.lessThan(r.get("occurredAt"), to));
    var p = logs.findAll(spec, page(page, size, "id,desc"));
    return new PageResponse<>(
        p.getContent().stream()
            .map(
                l ->
                    new LogDto(
                        l.getId().toString(),
                        l.getActorId() == null ? null : l.getActorId().toString(),
                        l.getActorName(),
                        l.getModule(),
                        l.getAction(),
                        l.getResourceType(),
                        l.getResourceId() == null ? null : l.getResourceId().toString(),
                        l.getResourceNo(),
                        l.getSuccess(),
                        l.getErrorCode(),
                        l.getSummary(),
                        l.getTraceId(),
                        l.getClientIp(),
                        l.getOccurredAt()))
            .toList(),
        page,
        size,
        Long.toString(p.getTotalElements()));
  }

  @Transactional
  public void loginAudit() {
    audit.success("LOGIN", "USER", AuditedEntity.actor());
  }

  @Transactional
  public void logoutAudit() {
    audit.success("LOGOUT", "USER", AuditedEntity.actor());
  }

  private void preserveAdmin(SystemRole admin, SystemUser candidate) {
    users.flush();
    if (users.enabledAdmins(admin.getId()) < 1)
      throw ApiException.conflict("INVALID_STATE", "必须保留至少一个启用的ADMIN用户");
  }

  private Set<SystemRole> roleSet(List<String> ids) {
    if (new HashSet<>(ids).size() != ids.size()) throw ApiException.invalid("角色不能重复");
    Set<SystemRole> set = new HashSet<>();
    for (String id : ids) set.add(role(Policy.id(id)));
    return set;
  }

  private SystemUser user(long id) {
    return users.findById(id).orElseThrow(ApiException::missing);
  }

  private SystemRole role(long id) {
    return roles.findById(id).orElseThrow(ApiException::missing);
  }

  private void version(AuditedEntity entity, Long expected) {
    if (expected == null || expected < 0) throw ApiException.invalid("version不能为空且须非负");
    if (!Objects.equals(entity.getVersion(), expected))
      throw ApiException.conflict("VERSION_CONFLICT", "记录已变更，请刷新");
  }

  private void require(String grant) {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || a.getAuthorities().stream().noneMatch(g -> g.getAuthority().equals(grant)))
      throw new ApiException(403, "FORBIDDEN", "没有操作权限");
  }

  private UserDto dto(SystemUser u) {
    return new UserDto(
        u.getId().toString(),
        u.getVersion(),
        u.getUsername(),
        u.getDisplayName(),
        u.getEnabled(),
        u.getRoles().stream().map(r -> r.getId().toString()).sorted().toList(),
        u.getCreatedAt(),
        u.getUpdatedAt());
  }

  private RoleDto dto(SystemRole r) {
    return new RoleDto(
        r.getId().toString(),
        r.getVersion(),
        r.getCode(),
        r.getName(),
        r.getDescription(),
        r.getBuiltin(),
        r.getPermissions().stream().sorted().toList());
  }

  private Pageable page(int page, int size, String sort) {
    if (page < 1 || size < 1 || size > 100) throw ApiException.invalid("分页范围无效");
    if (!Set.of("createdAt,desc", "id,desc").contains(sort)) throw ApiException.invalid("排序字段不支持");
    return PageRequest.of(
        page - 1,
        size,
        sort.startsWith("createdAt")
            ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
            : Sort.by(Sort.Order.desc("id")));
  }

  private String like(String value) {
    if (value.length() > 100) throw ApiException.invalid("搜索文字最多100字");
    return "%"
        + value
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        + "%";
  }
}
