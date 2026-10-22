# AI第4阶段评测结果（2026-10-20）

实现范围：只读当前库存、低库存、确认工单缺料与待执行成品入库；未升级依赖。

| 验证层 | 通过 | 失败 | 未运行 | 用例耗时（秒） |
|---|---:|---:|---:|---:|
| deterministic-no-model | 127 | 0 | 0 | 1.573 |
| java-deterministic-no-model | 25 | 0 | 0 | 4.033 |
| mysql-test-tools | 19 | 0 | 0 | 18.124 |
| real-model | 0 | 0 | 1 | 0.000 |
| 前端单元 | 11 | 0 | 0 | 0.028 |

类型检查与前端构建通过。表内是测试用例耗时合计，不是进程启动/构建总墙钟；最终Maven verify墙钟59.972秒。逐项状态/耗时/失败类型保留在[JSON快照](ai-inventory-results.json)。

修改前基线：105 Python、23 Java单元、13真实MySQL集成，0失败，真实模型未启用。首次新增TDD：6失败/5通过；原因是库存问题进入旧销售日期澄清，新增独立路由后保留原测试并通过。最终运行已无失败。

真实MySQL 8.4.7，仅mini_mfg_test、Redis前缀light-mfg:test:。5项库存集成通过公开应用服务构造资料/BOM/工单，库存写入只走InventoryService，事务回滚；独立SQL核对余额和快照，并独立相减核对缺料。覆盖零库存、阈值相等不预警、无缺料、快照与当前BOM差异、待入库不增加余额/流水、多个工单不预留、已领料不重复补料、候选及参数约束。

AiIntegrationTest新增分权HTTP矩阵：销售、库存、生产、库存单据单独权限及生产+库存双权限；仅双权限可进入缺料数据查询，撤销能力/服务认证/未知身份字段/CSRF/会话所有权继续受验证。该类SSE上游为传输桩；Python库存测试运行真实LangGraph但工具是测试桩，均不算真实模型。

本阶段未验证：真实模型显式-Live、运行中的Python→Java全进程库存HTTP链路、浏览器端到端、本轮完整业务库存并发/整单回滚回归、生产负载/超时压力。当前新增路径确定性且不调用模型；第3阶段旧真实模型calendar_yesterday失败记录保留，不宣称已修复或本次已验证。

最终运行命令：`./scripts/Test-AiEvaluation.ps1`；随后补充年份历史库存用例并重跑全部Python及report.py（Java代码未变）；前端typecheck、test:unit -- --run、build。依赖版本不变。现有Starlette弃用和前端大包警告仍在，无检查失败。不写开发库交易、不清库、不连接参考项目。

API/数据库/UI/测试规则及边界见[工具说明](ai-inventory-production.md)。仅完成本阶段，到此停止。
