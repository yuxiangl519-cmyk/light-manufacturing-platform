# 智能经营分析 Agent MVP

本次开发基于当前“轻量制造管理平台”，不是jshERP。新增只读销售分析，不改写采购、生产、销售或库存业务；不支持可靠利润核算。运行链路为Vue → Java Spring Boot → Python FastAPI → LangGraph → Java受控销售查询 → MySQL。

当前生产决策已按用户要求改为[模型驱动循环](ai-model-loop.md)：所有问题先模型决策，原生工具结果交回模型继续，数值与权限仍由代码/Java控制。下文MVP节点及前7阶段记录为演进历史；本次[实际验证报告](ai-model-loop-report.md)单独记录，不复用旧Live结果。

## 启动

项目根目录分别开启终端，先启动项目已有依赖：

```powershell
./scripts/Start-LocalServices.ps1
# 终端一：Java，默认8081；首次启动自动执行Flyway V8
./scripts/Start-Backend.ps1
# 终端二：Python，默认8091
./scripts/Start-Agent.ps1
# 终端三：Vue，默认5173
./scripts/Start-Frontend.ps1
```

访问 http://localhost:5173/ai-analysis ，用已有账号登录。当前入口允许销售/库存/生产/库存单据任一读权限（各工具另行鉴权，见[第4阶段](ai-inventory-production.md)），销售工具需要`erp:sales:read`，不新增公共注册或绕过登录。首次安装Python依赖：

```powershell
py -3.13 -m venv agent/.venv
./agent/.venv/Scripts/python.exe -m pip install -r agent/requirements.lock.txt
```

本机已安装。Java直接使用JDK17自带HTTP Client，无新增Maven框架；前端无新增npm依赖。不要运行两个占用8091的Agent实例。开发Agent必须指向开发Java8081，测试Agent使用`Start-Agent.ps1 -Test`指向18081；两个模式不能共用一个实例同时演示。

## 配置与密钥

根`.env.example`及`agent/.env.example`仅说明变量，不会自动加载。可以自行设置进程环境变量；本机便利脚本读取被Git忽略的`.local/ai-secrets.json`，只在进程内映射变量，不输出内容。已有ERP凭据保持原文件与导入脚本。`Start-Backend`自动导入AI配置，`Start-Agent`只导入AI配置。

| 变量 | 用途/默认值 |
| --- | --- |
| MFG_AI_SERVICE_TOKEN / AI_SERVICE_TOKEN | Java与Python共用的随机服务令牌，至少32字符；不得传给浏览器或模型 |
| MFG_AI_AGENT_URL | Java连接Agent，默认http://127.0.0.1:8091；MVP限制本机HTTP |
| MFG_AI_TENANT | 当前部署工厂标识，默认local-factory，绝不接受客户端传入 |
| AI_ERP_BASE_URL | Python回调Java，开发8081/测试18081 |
| AI_LLM_API_KEY | 模型Key，仅Python使用 |
| AI_LLM_BASE_URL | OpenAI兼容接口前缀，程序追加/chat/completions |
| AI_LLM_MODEL | 本次实测deepseek-v4-flash |
| AI_PLANNER_MODEL / AI_REFLECTION_MODEL | 可选节点模型覆盖，默认继承主模型 |
| AI_MAX_TOOL_CALLS | 每请求累计调用默认4，允许1～8；Java另有8次硬上限 |
| AI_MAX_REFLECTIONS | 默认2，允许1～3，表示包含首次规划在内的总尝试次数 |
| AI_LLM_THINKING_ENABLED | DeepSeek默认false，显式关闭思考模式以使用受控工具调用 |

DeepSeek官方地址https://api.deepseek.com；官方文档说明`deepseek-v4-flash`兼容名仍被接受、可能路由至Flash后续版本，不能将模型别名理解为固定权重版本。[官方说明](https://api-docs.deepseek.com/guides/web_search)。密钥缺失不会伪造模型成功；服务令牌缺失时Java拒绝启动分析请求，原ERP仍可运行。

## 工作流与指标

真实StateGraph包含Planner、Executor、Analyzer、Reflection、Reporter、Clarification。Planner通过真实模型生成结构化工具调用；模板计划经独立TextToSQL节点校验再执行。缺少证据/反思要求补查时条件路由回Planner，受累计工具次数与总尝试次数限制。FastAPI通过SSE实时返回节点进度，Java逐帧转发，Vue使用Fetch POST流式读取。

工具为get_sales_summary、get_sales_trend和execute_safe_query。最后一个仅接受sales_summary/sales_trend预审核模板，不接受SQL文本。所有SQL由Java执行，参数化、只读事务、每条查询5秒超时、最多366天/366趋势点；汇总和数量返回字符串。Python不持有数据库凭据，不执行SQL。

指标为当前SHIPPED销售单按创建时间筛选的含税订单金额、订单数；草稿、已确认未发货、取消、删除不计入，不称收入或利润。日期按上海自然日，API结束日不包含当天；用户“最近30天”包含今天，明确日期区间包含首尾日。比较相邻等长上期。Decimal计算增长、日均、首末日变化及规则异常；汇总与趋势合计必须一致。上期为0时标不可比。异常只提示需核查，不能编造原因。Reporter使用确定性模板，模型反思文本不能修改指标。

## 认证、隔离与记忆边界

当前ERP没有共享多租户模型。本MVP采用**单工厂/独立数据库部署**：工厂来自服务端固定配置，销售查询只针对该部署数据源，不能通过输入切换工厂。它不是共享数据库多租户产品，不得将不同工厂混放同一业务库后宣称隔离。AI会话按工厂+用户双重归属校验，其他用户/工厂记录返回404；JSON中伪造tenant/user字段直接拒绝。后续真正共享多租户需先改造ERP业务模型。

Java签发180秒单任务随机能力令牌，绑定当前用户身份版本、工厂、会话与run；Python每次工具回调同时提交服务令牌和该能力令牌。Java重新检查用户启用状态、身份版本、销售权限及任务状态；取消/结束后撤销能力。浏览器仍使用原Cookie、CSRF和后端权限，不能直接凭会话调用内部接口。工具仅输出聚合值，不发送客户姓名、联系方式、单据明细给模型；规划前对问题中常见手机号、邮箱和Key格式做有限脱敏，并非完整DLP系统。日志记录run及错误类型，不记录Key、能力令牌、完整问题或业务结果。

MySQL保存会话、消息、澄清状态及成功日期上下文。Working/Episodic Memory仅请求内；短期恢复由Java保存的受控context支持；LongTermMemory只有扩展协议，未实现自动长期提取；SemanticMemory实际读取已审核Markdown。没有向量库、自动记忆提取、完整LangGraph持久化Checkpoint或跨进程调度。并发分析上限4、会话上限100轮、列表最多50个、报告与SSE帧限长，单任务150秒（Java外层180秒）。

## 演示问题

- 分析最近30天的销售情况，并给出经营建议。
- 分析最近7天的销售额与订单数量。
- 分析2026-10-01至2026-10-19的销售情况。
- 帮我分析最近的利润情况。随后答“可以，改为最近30天全厂销售分析”。
- 分析销售情况。随后补充“最近7天”。
- 分析1990-01-01至1990-01-07的销售情况（空数据）。

第3阶段已增加客户/商品销售额排行与按后端ID查询的汇总/趋势；同名先列候选澄清，不退化为全厂报告，见[维度工具](ai-sales-dimensions.md)。时间范围现支持今天/昨天、本周/上周、本月/上月和指定年月，具体边界及澄清规则见[时间范围说明](ai-time-ranges.md)；不宣称支持任意同比预测。

## 测试与交付记录

实际结果见[ai-agent-verification.md](ai-agent-verification.md)。测试使用mini_mfg_test、独立Redis和18081/5174，模型调用为真实DeepSeek，API Key不写入报告。开发库不灌入测试交易。

```powershell
./scripts/Test-Backend.ps1
./agent/.venv/Scripts/python.exe -m pytest agent/tests -q
cd frontend
npm run typecheck
npm run test:unit -- --run
npm run build
cd ..
# 三终端依次启动隔离后端、测试Agent、测试前端后再执行测试
./scripts/Start-E2eBackend.ps1
./scripts/Start-Agent.ps1 -Test
./scripts/Start-Frontend.ps1 -Port 5174 -BackendTarget http://127.0.0.1:18081
./scripts/Test-Ai.ps1
./scripts/Test-Frontend.ps1 -Ai
```

尚未实现：共享库多租户、自由SQL、利润成本核算、客户与商品同时交叉筛选、库存工具、完整Checkpoint、长期自动记忆、任务调度、多实例能力令牌、生产TLS/部署与容量测试。既有前端依赖审计风险仍保留在阶段5记录，未擅自升级。下一阶段优先处理依赖安全和权限/租户产品化，后续方向需要另行授权；本阶段不继续扩展。

## 2026-10-20评测增量

建立三层固定评测入口、独立数值基准与逐项计时报告；见[ai-evaluation.md](ai-evaluation.md)。未增加业务工具、HTTP参数或依赖；生产时钟默认行为不变，评测相对日期可注入时钟。


## 第4阶段增量

增加确定性Operations分支、只读当前库存/预警/确认工单需求/待执行成品入库工具；复用公开应用服务，不让模型计算缺料或选ID。销售路径原权限和日期规则保留。库存与生产权限、候选澄清、查询时刻及历史库存限制见[第4阶段工具说明](ai-inventory-production.md)。


## 第5阶段增量

新增EvidenceEngine与集中阈值配置，销售/客户集中度/库存/工单缺料结论按事实、待核实解释、具体行动组织证据。Reflection先做确定性门控，再运行模型建议；部分失败按预算补缺，不能用无关载荷或失败零值生成成功报告。未增加业务工具、预测或依赖，详见[第5阶段说明](ai-evidence-quality.md)。


## AI第6阶段：多轮条件与澄清恢复

结构化会话条件与未完成澄清由MySQL持久化；页面刷新按服务器会话恢复，覆盖条件后重新鉴权与查询。仅发送当前问题和有界白名单，不重复全部历史。没有向量数据库、完整长期记忆或LangGraph Checkpoint。详见[上下文契约](ai-context.md)与[本阶段报告](ai-context-report.md)。


## 2026-10-21 AI第7阶段：页面与端到端验收

当前页面与验收入口见本阶段说明。公开SSE阶段不再转发上游任意文字，来源用业务名称；现有业务工具和内部确定性证据校验不变。生产仍用app.main:app，evaluation.acceptance_app仅用于独立测试资源。真实模型依旧显式开关，健康检查不算调用验证。详见[启动、能力与五分钟演示](ai-page-acceptance.md)、[实际评测结果](ai-page-report.md)。
