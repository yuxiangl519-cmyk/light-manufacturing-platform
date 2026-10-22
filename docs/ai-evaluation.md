# AI完善第1阶段：可重复评测基线

当前生产决策策略为[model-loop-v1](ai-model-loop.md)。默认Python套件含旧RuleWorkflow组件回归和test_decision_loop新入口测试，全部为确定性替身，不算真实模型。Java/MySQL仍单独验证工具。显式Live下客户、商品、库存和澄清同样调用真实模型；旧报告中“维度不调用模型”仅适用于旧架构。新浏览器执行结果见[改造报告](ai-model-loop-report.md)。历史Calendar/Dimensions完整Live套件是否执行，以本次报告明确记录为准。

第1阶段固定案例版本 `ai-baseline-v1`，定义在 `agent/evaluation/cases.py`。覆盖销售汇总、每日趋势、等长环比、空数据、日期不明确、利润不支持、客户维度不支持、无权限、模型失败、工具失败、取消和日期澄清后继续。仅完善评测，沿用现有工具、依赖与业务口径。

## 三层入口

```powershell
# 默认：不访问模型。Python确定性测试 + mini_mfg_test工具/权限集成
./scripts/Test-AiEvaluation.ps1

# 显式真实模型：先分别启动两个独立终端，均不影响开发8081/8091
# 终端1（根目录）
. ./scripts/Import-LocalEnvironment.ps1
. ./scripts/Import-AiEnvironment.ps1
& "$env:JAVA_HOME/bin/java.exe" -jar "$PWD/backend/target/light-manufacturing-server-0.1.0.jar" --spring.profiles.active=test --server.port=18081 --server.address=127.0.0.1 --mfg.ai.agent-url=http://127.0.0.1:18091
# 终端2（根目录）
. ./scripts/Import-AiEnvironment.ps1
$env:AI_ERP_BASE_URL='http://127.0.0.1:18081'
./agent/.venv/Scripts/python.exe -m uvicorn app.main:app --app-dir agent --host 127.0.0.1 --port 18091 --no-access-log
# 终端3（根目录）
./scripts/Test-AiEvaluation.ps1 -Live
```

先运行默认命令生成当前JAR，再启动终端1。沿用本项目私密环境导入脚本；不打印环境。真实模型入口验证18081进程属于本项目、test profile和18091路由。18091须按上述命令使用本项目真实模型配置；不将健康检查或模型替身当成真实模型证据。MySQL客户端使用本项目已安装8.4.7，仅连接127.0.0.1:13306的mini_mfg_test。测试Redis沿用16380与light-mfg:test:。

1. **确定性单元**：pytest收集`agent/tests/test_*.py`；真实StateGraph配确定性替身，不发模型请求。相对日期由`SalesAgent(clock=...)`注入上海自然日，固定2026-10-02；`resolve_period(today=...)`有独立时钟测试。不是HTTP参数或生产历史时间开关。
2. **真实数据库**：Java `SalesAnalyticsIntegrationTest`验证汇总、每日金额/订单、缺日补零、等长上期、UTC边界、状态/软删除和元数据；新增基准直接读取明细，在Java中独立过滤分组，不复用聚合SQL。新增销售夹具在事务内回滚。`AiIntegrationTest`验证内部真实工具认证/模板/能力、无权限、CSRF及取消持久化；SSE上游是明确测试桩，不是模型。日期固定。
3. **显式真实模型**：`-Live`才运行`evaluation.live`。销售成功/空数据/澄清续问经过Java→独立FastAPI→LangGraph→真实配置模型→Java工具→MySQL；权限、澄清前置与取消用同一HTTP入口，前置拒绝本身无需调用模型。模型失败使用不存在模型名调用真实服务；工具失败使用真实模型规划后连接不可达本机端口。这两项是**Agent真实网络故障探针，不覆盖Java故障转发**，在scope中单独标记，不假称完整HTTP故障验收。

## 正确性与报告

固定显式日期为2026-10-01至2026-10-02，API区间为[2026-10-01,2026-10-03)，上期等长；空区间固定1990年。运行日不会改变问题。成功报告的金额、订单数、增长率、日期、来源与币种按字段解析比较，允许其他表达变化。逐日金额/订单由真实工具集成逐点核对；当前报告只展示日均与首末日方向，真实模型层核对这两项，不宣称报告包含逐日明细。

真实模型层通过mysql客户端执行独立只读明细SELECT，在Python中按上海日分组；不调用生产销售聚合方法作为答案。对本期与上期金额/订单、Decimal增长率、日均、方向、区间、来源/CNY核对。查询前后核对总额/订单，若评测期间源数据变化则失败，选择安静窗口重跑。数值检查器有错误金额、订单、增长率、日期和来源的反向测试。

`output/ai-evaluation/report.md`给出分层通过/失败/跳过与耗时；`report.json`记录逐项状态、秒数与失败原因。单元/集成的断言详情保留于本地JUnit XML，公开摘要只写错误类型，避免复制原始响应。上游失败使用固定错误码原因。返回非零表示失败或缺少必要层报告；默认运行明确记录真实模型未启用。报告每次只读取本次复制的XML，旧live.json不能使默认运行变成真实模型验证。

开发库不写评测会话或交易，不清库。HTTP集成/真实模型会在测试库留下评测会话、随机无权限用户；每次唯一命名，不删除原记录。没有Flyway、API DTO、业务工具、UI或依赖版本变更。模型别名不固定服务端权重，耗时和真实模型路线允许变化。

## 验证范围

本次运行结果见[ai-evaluation-verification.md](ai-evaluation-verification.md)。未覆盖生产部署、共享多租户、负载、浏览器全面回归、长期记忆；不扩展利润/客户维度。旧live_smoke.py及Test-Ai.ps1保留为历史冒烟，新基线使用上述入口。


## 第2阶段更新

当前入口输出ai-baseline-v2，保留v1固定问题；按新需求将0/0增长率改为“不可比”，补充报告时区/指标定义检查与日历边界/指标澄清测试。历史v1结果不改写。显式真实模型日历套件用`-Live -Calendar`，需要固定时钟测试入口；规则、启动与版本变更见[时间范围说明](ai-time-ranges.md)。


## 第3阶段更新

当前入口为ai-baseline-v3：客户排行问题由原“暂不支持”升级为数值正确的排行，不删除原问题；增加SalesDimensionsIntegrationTest、维度安全/名称/单位测试和独立明细oracle。显式`-Live -Calendar -Dimensions`同时运行旧真实模型回归与维度HTTP工具回归，后者单独标记dimension-http-real-tools，不能当作维度问题调用了模型。见[维度工具说明](ai-sales-dimensions.md)。


## 第4阶段更新

保留v3销售固定案例；默认评测额外收集库存/缺料StateGraph确定性测试、AiOperationsIntegrationTest真实数据库测试及AiIntegrationTest分权HTTP测试。新工具不调用模型，报告仍分为deterministic-no-model、mysql-test-tools、显式real-model，未启用模型必须记录not-run。库存数值直接与独立余额/快照SELECT比较，历史库存不伪造。详见[工具与边界](ai-inventory-production.md)和[本阶段结果](ai-inventory-report.md)。


## 第5阶段更新

沿用v3原固定业务问题，新增evidence-v1质量策略与固定失败/恢复评测；report.json记录analysisPolicy=evidence-v1。增加AiEvidenceIntegrationTest（真实Java工具→Python证据引擎，独立SQL数值基准，属于mysql-test-tools且不调用模型）。默认命令仍明确真实模型not-run。测试桩补全已有工具元数据与逐日日历，以验证新生产检查，原金额/订单断言不放宽。见[质量规则](ai-evidence-quality.md)与[本轮证据](ai-evidence-report.md)。


## AI第6阶段：多轮条件与澄清恢复

默认入口保留v3和evidence-v1，加入结构化条件覆盖/刷新协议/权限变更回归。Java SSE故障场景使用上游测试桩；隔离浏览器库存路径调用真实Java/MySQL但不调用模型。没有将模拟模型测试归入real-model。详见[上下文契约](ai-context.md)与[本阶段报告](ai-context-report.md)。


## 2026-10-21 AI第7阶段：页面与端到端验收

本轮沿用v3/evidence-v1，增加执行统计隔离及提供方usage接线单测、公开输出过滤和前端终态门控/超时回归。旧数值检查保留，公开Java来源接受固定业务别名，内部原始来源仍严格核对。新增真实浏览器入口与独立SQL基准，不用静态报告或替身冒充真实模型。详见[启动、能力与五分钟演示](ai-page-acceptance.md)、[实际评测结果](ai-page-report.md)。


第7阶段补验（2026-10-21）：用户随后显式授权真实模型。已实际运行隔离Live浏览器11项全过，含真实模型销售总览、同会话客户排行/客户追问、实际提供方拒绝不存在模型后的恢复。Python全量213项通过；5次模型请求，4份返回usage合计2925 token（拒绝请求未返回，不是完整总量），不估算成本。原无模型结果保留，历史Live全套仍未重跑。见[更新后的实际报告](ai-page-report.md)。
