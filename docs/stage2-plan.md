# 第2阶段执行计划

本次用户授权在当前项目实现后端基础、认证授权、系统管理并实际验证。数据库按最新指令改为mini_mfg_dev/mini_mfg_test。仅本阶段，不实现基础资料和ERP/MES/WMS业务。

- [x] 独立MySQL8.4.7/Redis7.4.2，检查同名库归属，凭据置于被忽略的本地文件。
- [x] 后端Spring Boot、官方Maven Wrapper、Flyway系统表、profile与测试数据库保护。
- [x] 测试驱动实现统一错误、traceId、会话、CSRF、原子限流、即时权限刷新。
- [x] 用户/角色/固定权限/审计接口、环境变量演示初始化及管理员保底。
- [x] 真实MySQL8/Redis测试及HTTP启动验证，包括失败场景、重复初始化和依赖故障。
- [x] 规格审查后代码审查，修正发现，再验证。
- [x] 更新接口、环境、进度与启动说明，记录实测和未验证事项，然后停止。

分工：backend_implementation代理负责backend主代码和单元测试；主代理负责独立环境、真实集成测试及文档，避免修改同一文件。实施和审查遵循subagent-driven-development技能。工作在用户指定目录，无参考项目修改。
