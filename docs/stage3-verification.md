# 第3阶段实际验证记录

日期：2026-10-17。范围：四类基础资料、单层BOM、内部确认快照、库存服务及查询；没有提前实现ERP/完整工单接口或前端。沿用Java17、Boot3.5.16、MySQL8.4.7、Redis7.4.2、Maven3.9.12。参考项目未改动或运行。

## 实际命令与结果

在项目根目录执行；私密环境由Import-LocalEnvironment导入，不展示其内容。以下日志都在被Git忽略的.local/logs；报告不含密码、Cookie或CSRF Token。

| 实际命令/操作 | 结果 |
| --- | --- |
| `.\scripts\Test-Backend.ps1`（实现前基线） | BUILD SUCCESS；原8单元+12集成通过，stage3-baseline.log |
| 导入环境后`cd backend; .\mvnw.cmd -B test-compile`（先写库存测试） | 预期失败：库存服务/契约尚不存在，stage3-red.log |
| `.\mvnw.cmd -B -P integration '-Dit.test=Stage3InventoryIntegrationTest' verify` | 首轮库存8项真实测试通过，stage3-inventory.log；早期另修正PowerShell未引用-D参数及查询重载编译错误 |
| `.\mvnw.cmd -B -P integration verify` | 首轮10单元+30集成通过，stage3-full.log |
| `.\mvnw.cmd -q test`（实施子任务） | 单元测试退出0；未并行执行集成测试 |
| Java17执行本地google-java-format-1.24.0.jar `--replace` 所有src Java | 退出0，统一格式；没有改动依赖版本 |
| `.\mvnw.cmd -B clean -P integration verify`（最终） | BUILD SUCCESS，10单元+31集成全部通过，失败/错误/跳过0；stage3-final.log，耗时43.290s |
| 核验旧8081 Java进程的classpath argfile归属当前backend后停止；`.\scripts\Start-Backend.ps1` | 新local进程启动成功、Flyway V1～V5/JPA validate通过；stage3-local-start.log |
| `Invoke-RestMethod http://localhost:8081/actuator/health` | HTTP200，只有status=UP |
| 使用环境变量登录、逐个GET新增8类列表、GET /v3/api-docs、退出 | 全部200，退出204；开发资料/BOM/库存均total="0"，未灌入测试数据 |
| 读取Surefire/Failsafe XML报告计数 | 10+31，与控制台一致，全部0失败/错误 |
| 扫描公开源码/文档/配置是否包含已导入的本地凭据值；检查文档Markdown链接 | 均PASS，未输出凭据或令牌；未发现断链 |

最终测试库mini_mfg_test、测试Redis16380/light-mfg:test:；开发库mini_mfg_dev与开发Redis16379均未用于测试。迁移V2资料/BOM、V3工单/快照、V4库存、V5编码排序及索引已在开发/测试应用，未改写已执行迁移，未清库。

## 测试证据

| 测试类 | 数量 | 验证内容 |
| --- | --- | --- |
| PolicyTest | 4 | 用户名、密码与固定权限规则 |
| StrictJsonTest | 3 | 拒绝宽松字符串/数值转换及未知字段 |
| QuantitiesTest | 2 | 正整数/非负整数、范围及小数拒绝 |
| system.application.AuditServiceTest | 1 | 脱离请求上下文的失败审计身份 |
| Stage2IntegrationTest | 12 | 真实认证、401/403、CSRF、退出、即时权限、停用、重置密码、限流、重复初始化等回归 |
| Stage3MasterIntegrationTest | 9 | 编码重复/搜索/分页；客户供应商仓库生命周期/版本；非法BOM类型/重复料/非法数量；条件启用唯一；物料冻结/停用新引用拒绝；旧停用BOM可保留旧引用但不能加入停用料；已提交工单快照不随BOM/名称变化；溢出与停用仓库确认回滚；HTTP权限/CSRF/严格数量及无DELETE |
| Stage3InventoryIntegrationTest | 10 | 无外层事务拒绝；首次并发入库；并发出库不超扣；并发重复来源只记一次且换内容冲突；后续明细不足整单回滚；来源状态/JPA引用标记/余额/单据共享事务回滚；待执行入库不加库存且执行一次；非法数量/重复料/溢出；相反输入顺序多料并发；HTTP只读鉴权、零余额不落库、历史快照及来源行追踪 |

并发不是模拟方法调用：ExecutorService线程内分别执行TransactionTemplate，CountDownLatch同时放行，Future等待事务实际提交，最后由独立查询验证余额/流水/单据。所有测试均无测试框架外层回滚。测试记录使用唯一编码保留，不删除已有数据。

库存每维度验证`balance.quantity=SUM(ledger.change_quantity)`，每流水验证`before+change=after>=0`。首入两笔20/30余额50；出库两笔各7争用10，仅一笔成功、余3；重复两次入5仅余5；两物料各10并发各扣7后均余3。库存单来源行ID及明细ID可追踪。

外层来源状态测试使用仅mini_mfg_test中的stage3_test_source模拟未来ERP头，不代表已实现采购/销售业务。库存内部服务要求调用方锁定真实来源、校验状态/明细，并在同事务更新状态；此集成责任已写入架构/API。

## 审查与修复

独立规格审查发现停用BOM更新可新增停用组件，已缩小豁免到原有引用且目标仍停用，新增回归并通过。独立质量审查发现库存直接读sys_user，改为system公开OperatorDirectory；复审确认关闭。JPA不可变快照只读实体没有更新入口，库存无HTTP写接口；Hibernate SQL异常日志关闭，错误响应由统一异常处理脱敏。

## 未验证及未实现

- 完整采购→领料→报工→整机入库→销售应用闭环、来源实际业务状态机、command_record和报工表，留待后续阶段。
- Vue页面、Vite代理浏览器联调、前端构建与完整电脑组装浏览器案例，尚无前端工程。
- Docker Compose实际部署、生产负载与大规模性能压测未执行；同物料引用行锁会使跨仓库动作串行，属于当前明确取舍。
- Redis真实中断为第2阶段已有单独验证，本轮未重跑该故障脚本；认证测试已全量回归。

本阶段通过后停止。开发后端保留运行在localhost:8081，后续按startup.md停止或启动；无新增外部服务配置。
