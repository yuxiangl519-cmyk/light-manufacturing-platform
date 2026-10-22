# 独立本地环境

2026-10-17：本方案已在第2阶段实际实施；不再是等待安装的预案。用户不需要查找或借用参考项目密码。

| 资源 | 当前安排 |
| --- | --- |
| JDK | Temurin17.0.20.1+1，仅项目进程设置JAVA_HOME |
| MySQL | 独立8.4.7，127.0.0.1:13306，用户专属运行目录 |
| 开发库/账号 | mini_mfg_dev / mini_mfg_dev，仅该库权限 |
| 测试库/账号 | mini_mfg_test / mini_mfg_test，仅该库权限 |
| Redis开发/测试 | 独立7.4.2，16379/16380，各自认证与数据目录 |
| 会话及限流 | light-mfg:local: / light-mfg:test: |
| 凭据 | .local/secrets.json，随机生成、当前用户权限、Git忽略 |

Prepare/Initialize/Start/Stop脚本均检查归属；相同名称库在没有项目完成标记时拒绝复用，不覆盖数据；停止Redis还核对目标端口、PID和二进制，避免旧PID误停测试实例。已完成真实回归。

目前每个数据库使用独立的库级DDL/DML账号以支持Flyway，没有全局权限。更细的生产迁移账号与运行账号分离留给后续部署，不宣称已配置。没有修改3306/3307、6379/6380或8080的既有服务。

详见[startup.md](startup.md)、[environment.md](environment.md)与[stage2-verification.md](stage2-verification.md)。
