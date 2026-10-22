# 第2阶段实际验证记录

日期：2026-10-17（Asia/Shanghai）。范围仅后端基础、认证授权与系统管理；不包含ERP/MES/WMS业务或前端。以下结果来自实际执行，不以设计或静态审查替代运行测试。

## 最终构建

先在根目录导入本项目私密环境，再执行：

```powershell
. .\scripts\Import-LocalEnvironment.ps1
cd backend
.\mvnw.cmd -v
.\mvnw.cmd -B clean -P integration verify
```

结果：Maven3.9.12、Java17.0.20.1；最终clean构建BUILD SUCCESS，退出码0。28个主源码文件编译、生成`light-manufacturing-server-0.1.0.jar`。本地完整构建输出保存在被忽略的`.local/logs/delivery-verification.log`。

| 测试套件 | 数量 | 失败/错误/跳过 |
| --- | --- | --- |
| PolicyTest | 4 | 0/0/0 |
| StrictJsonTest | 3 | 0/0/0 |
| AuditServiceTest | 1 | 0/0/0 |
| Stage2IntegrationTest | 12 | 0/0/0 |
| 合计 | 20 | 0/0/0 |

JUnit XML位于`backend/target/surefire-reports`与`failsafe-reports`，均已读取确认计数。集成测试真实使用MySQL8.4.7的mini_mfg_test和Redis7.4.2的16380，没有H2、模拟登录或连接开发库执行测试。

## 真实HTTP覆盖

12项集成测试覆盖：

- 实例版本及test库确认，未登录401、traceId、health200且无详细组件信息。
- 登录成功、失败、当前用户与当前34权限；响应没有password/hash/authVersion。
- 退出后当前会话及旧Cookie重放均401。
- 登录和系统写接口缺CSRF均403 CSRF_INVALID。
- VIEWER直接访问系统读/写接口403；失败审计保留已认证操作者。
- 自定义角色撤销/恢复权限立即影响同一旧会话，不要求重新登录。
- 用户停用使旧会话及再次登录失败；替换用户角色使原授权立即失效。
- 管理员重置密码递增authVersion，旧会话失效，新密码可登录。
- 重复调用初始化两次，账号数、已有密码哈希、权限分配不变，admin仅一条，固定权限34条。
- 同账号5次失败后第6次返回429和Retry-After，Redis计数有正TTL且不超过900秒。
- 字段校验、未知字段拒绝和错误脱敏；内置ADMIN不可减权、不可停用最后管理员、引用中的角色不可删。
- 分页DTO的ID/total为字符串，操作日志记录成功动作且不含密码。

单元测试还覆盖BCrypt字节限制、动作依赖read权限、测试资源拒绝规则、严格JSON数字/字符串/布尔转换和失败审计无需请求上下文。

## 环境及独立进程验证

| 实际操作/命令 | 结果 |
| --- | --- |
| 官方MySQL8.4.7/Redis7.4.2下载、校验、源码编译及Initialize/Start脚本 | 新专用实例启动；同名库不存在才创建；无既有服务配置修改 |
| `scripts/Prepare-LocalEnvironment.ps1`再次运行 | 复用项目实例和标记，不覆盖数据库或账号数据 |
| 各项目账号`SELECT VERSION(),DATABASE(),CURRENT_USER()` | 分别得到8.4.7、mini_mfg_dev/mini_mfg_test及对应账号 |
| 测试账号连接mini_mfg_dev | 预期拒绝，MySQL1044；无开发库权限 |
| `mvnw.cmd -B dependency:tree` | BUILD SUCCESS；实际Framework6.2.19/Security6.5.11/Session3.5.7/Hibernate6.6.53/Flyway11.7.2/Connector9.7/Logback1.5.34 |
| 独立`java -jar ... --spring.profiles.active=local`，之后通过`Start-Backend.ps1`启动最终版本 | 8081监听，health200；Flyway V1和Hibernate validate成功 |
| local `/v3/api-docs` | ADMIN登录后200，匿名401；非local配置未开放 |
| 故意以test profile传开发库URL启动JAR | 预期退出1；Unsafe test resource configuration，Hikari/Flyway启动前即拒绝 |
| `scripts/Verify-RedisFailure.ps1` | 对已登录会话真实停止开发Redis，接口返回503 DEPENDENCY_UNAVAILABLE；finally恢复，health恢复200 |
| `scripts/Test-ServiceOwnership.ps1` | 将开发PID文件临时指向测试Redis PID后，停止动作被拒绝；两个进程均存活，finally恢复PID文件 |
| PowerShell脚本语法解析、Git忽略检查 | 通过；.local、.env、日志和target不进入Git |

运行故障测试只停止本项目16379开发Redis；测试Redis16380和既有6379/6380不受影响。已验证的应用进程现通过前台启动脚本运行，可按startup说明停止；未注册替换既有Windows服务。

## 修复与审查

测试与检查中发现并修复：新实体version初始值导致merge后原对象无ID的500、失败审计在请求结束后丢失上下文、Controller承载认证编排、分层不符合设计，以及Redis停止脚本仅校验二进制造成的旧PID复用风险。各项均有真实失败记录或静态审查证据，修复后完整回归；旧PID复用还进行了真实脚本回归。

独立规格审查复审通过；独立代码质量审查修复PID问题后复审无剩余重要发现。审查代理只读，不将其意见当作运行测试。Redis二次故障测试曾出现重启未通过健康检查，启动脚本已补充认证/版本就绪检查、停止完成等待，之后完整中断/恢复验证通过；没有把进程返回0当成服务就绪。

## 未验证和边界

- 没有前端工程或浏览器登录页面，Vite代理/浏览器联调未执行。
- container profile仅有隔离配置，Docker Compose及容器启动未执行。
- 多管理员并发互停未做运行压力测试；本阶段已验证单管理员保底，代码使用ADMIN行锁和READ_COMMITTED，独立审查通过。
- 30分钟自然会话过期与15分钟完整限流时间窗未按墙钟等待；已核实配置和Redis计数TTL。
- 用户自行改密接口已实现；重置密码和会话失效有实测，自行改密专门端到端用例未单独执行。
- 后续电脑组装库存闭环、库存事务/并发/回滚测试尚未实现，本报告不声称通过。

本阶段结束，后续功能等待用户下一阶段指令。
