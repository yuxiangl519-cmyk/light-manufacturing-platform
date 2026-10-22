# 第4阶段实际验证记录

日期：2026-10-17。范围为采购、生产、仓储、销售最简后端闭环；场景沿用用户已确认的标准办公台式机。参考项目未修改、启动或访问其业务数据库；未开始前端。

## 实际命令和结果

根目录PowerShell7，先导入`. ./scripts/Import-LocalEnvironment.ps1`使用项目独立凭据与Java17，不输出内容。后端使用既有Boot3.5.16/Maven3.9.12、MySQL8.4.7及Redis7.4.2，没有升级依赖。

| 命令/实际操作 | 结果 |
| --- | --- |
| `.\scripts\Test-Backend.ps1`（实施前） | BUILD SUCCESS，原10单元+31集成通过；stage4-baseline.log |
| `cd backend; .\mvnw.cmd -B -P integration '-Dit.test=Stage4BusinessIntegrationTest' verify`（先写测试） | 预期失败，业务接口尚未存在；stage4-red.log |
| `mvnw.cmd -B test-compile` | 编译通过；stage4-compile.log |
| 实施子任务`mvnw.cmd test` | 13单元0失败；金额测试先缺失实现而失败，再实现通过。未并行执行集成测试 |
| `mvnw.cmd -B -P integration verify`首轮 | 13单元通过；40集成中39通过，1个权限用例提交空表单导致400，修正为合法表单检查403；stage4-first.log |
| 同命令第二轮（补足并发生产/外层回滚/草稿测试） | 13单元+44集成全部通过；stage4-second.log |
| Java17运行现有google-java-format-1.24.0.jar `--replace` | 全部Java格式化成功，没有新依赖 |
| 核验旧8081进程classpath归属当前backend并停止，`mvnw.cmd -B clean -P integration verify` | 最终BUILD SUCCESS，13单元+44集成，失败/错误/跳过0，耗时53.517s；stage4-final.log |
| `.\scripts\Start-Backend.ps1` | local启动成功，开发库V6/V7迁移并JPA validate；stage4-local-start.log |
| Invoke-RestMethod health、环境变量登录、新业务列表、OpenAPI、退出 | health=UP；采购/工单/销售/库存单/余额/流水列表200/total="0"；OpenAPI含新动作路径，退出204 |
| 读取Surefire/Failsafe XML计数、扫描公开文件中的本地凭据值、检查Markdown链接 | 57测试/0失败/0错误/0跳过；凭据字面值扫描与文档链接均PASS，未输出凭据 |

所有集成测试只连接mini_mfg_test、Redis16380和light-mfg:test:。开发库mini_mfg_dev只启动迁移和只读冒烟，不灌入交易或直接设置库存。V1～V5未改写，V6增加command_record/production_report，V7增加采购和销售4表；开发和测试均至V7，24张应用表。记录保留并使用新编码，不清库、不删除已有数据。

报告在backend/target/surefire-reports与failsafe-reports；过程日志被Git忽略，不含公开凭据。

## 最终测试分布

| 测试 | 数量 | 范围 |
| --- | --- | --- |
| 原Policy/StrictJson/Quantities/AuditService单测 | 10 | 密码/固定权限/严格JSON/整数范围/审计基础 |
| ErpMoneyTest | 3 | 含税价格式/两位金额、非法输入、乘法与汇总范围 |
| Stage2IntegrationTest | 12 | 真实登录/退出、401/403、CSRF、即时权限/停用、重复初始化/限流等 |
| Stage3InventoryIntegrationTest | 10 | 库存底座真实并发、来源幂等、共享事务及余额流水对账 |
| Stage3MasterIntegrationTest | 9 | 资料/BOM约束、停用/冻结、确认快照和HTTP安全 |
| Stage4BusinessIntegrationTest | 9 | 完整电脑组装闭环及8动作重放、非法状态/缺料/确认不可修改、销售多行整单失败、并发收货/竞争发货、权限/CSRF/非法字段、并发生产4动作、预览非预留、外层异常回滚、生产草稿生命周期及入库来源限制 |
| Stage4ErpIntegrationTest | 4 | 金额/类型/重复料/非法字段、采购销售草稿版本/删除取消重放、停用及确认快照、权限/CSRF |

总计57，全部成功，无跳过。没有用H2、Mock库存或测试外层自动回滚冒充MySQL并发。

## 电脑组装验收实际结果

固定BOM：CPU1、主板1、内存条2、SSD1、电源1、机箱1。使用真实HTTP创建资料/BOM、业务头和动作，测试准备也只操作测试库。

| 步骤 | 断言通过的结果 |
| --- | --- |
| 采购并全量收货 | CPU/主板/SSD/电源/机箱各20，内存40；含税单价10.00，总额1400.00 |
| 确认10台工单，随后修改BOM内存单耗到3 | 已确认工单仍用冻结单耗2，需求20；旧BOM变动不影响工单 |
| 领料 | 五类各余10，内存余20；工单IN_PRODUCTION |
| 按计划报工10 | 工单PENDING_INBOUND、唯一报工事实及PENDING成品入库，整机仍0 |
| 执行成品入库 | 整机10，工单COMPLETED |
| 销售3并发货 | 单价1200.50，总额3601.50，整机余7 |
| 重放确认/收货/工单确认/领料/报工/入库/销售确认/发货 | 原operationId、replayed=true，成功命令仍8条，库存仍7，流水14条 |

每个测试维度均校验余额=SUM(change_quantity)，库存基础测试还逐条校验before+change=after>=0。正式领料使用冻结需求并重新检查余额，完全不依赖之前预览。

## 并发与回滚证据

- 并发客户端独立会话、真实HTTP请求，CountDownLatch同时放行；服务端各自事务提交后Future返回，再独立查MySQL断言。没有只模拟方法调用。
- 同采购单两次收货均200，只有一笔入库；五类仍20、内存40。
- 同工单两次确认/领料/报工/入库，各动作均200且只生效一次；快照6行、报工1行、采购/领料/生产入库流水合计13行，成品10。
- 两个已确认销售单各发7争用库存10：一个200、一个409，成品3，无负库存。
- 销售两明细中后一成品不足：整单失败，前一成品10不变，来源仍CONFIRMED，不留下出库或成功命令。
- 10台工单预览充足后，另一11台工单先领料；原工单正式领料重新检查并失败，未多扣。
- 独立TransactionTemplate调用真实采购收货服务，全部收货副作用完成后让外层抛异常：来源状态/version、库存单/余额/流水、成功命令与成功操作日志全部回滚；随后正常重试可收货。不是模拟数据库失败。
- 非法状态、确认后PUT/取消/删除、非生产库存单execute、数量/金额/状态字段注入、无权限和CSRF均拒绝。

## 审查、限制和停止点

独立规格与代码质量审查未发现可确认阻断问题，实际正确性以最终MySQL测试为证。已收紧动作version为非负JSON安全整数，错误沿用统一DTO/traceId，不暴露SQL、堆栈或敏感配置。成功审计与业务同事务，重放不新增成功事件。

单号采用上海日期+UUID并有唯一键，极小概率碰撞返回409/回滚，由客户端重试创建；未实现自动碰撞重试，已同步实际API契约。跨仓库同料锁仍提前串行，未做性能压测。

未验证/未实施：Vue前端、工作台统计、浏览器案例、Docker实际部署及生产压测；本轮未重跑第2阶段Redis中断故障脚本，认证与限流测试已回归。未新增分批、审批、退货、质检、序列号、财务等功能。

开发后端保留在localhost:8081，按startup.md启动/停止。本阶段验证完成后停止，等待下一阶段指令。
