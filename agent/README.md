# 销售分析 Agent MVP

真实 FastAPI + LangGraph 服务，仅监听本机。Java 负责登录、权限、用户/工厂隔离、持久化会话与短期能力令牌；Python 不维护全局会话缓存，不接收用户自报身份。

## 安装与运行（Windows PowerShell，项目根目录）

```powershell
python -m venv agent/.venv
agent/.venv/Scripts/python.exe -m pip install -r agent/requirements.lock.txt
# 按 .env.example 在当前进程设置环境变量，或使用项目的私密环境导入脚本。
agent/.venv/Scripts/python.exe -m uvicorn app.main:app --app-dir agent --host 127.0.0.1 --port 8091
```

`AI_SERVICE_TOKEN` 必须与 Java `MFG_AI_SERVICE_TOKEN` 相同。`AI_ERP_BASE_URL` 默认 `http://127.0.0.1:8081`；隔离验收使用 `18081`。`AI_LLM_BASE_URL` 是 OpenAI 兼容 Chat Completions API 前缀，例如 `https://api.openai.com/v1` 或 `https://api.deepseek.com`；程序追加 `/chat/completions`。模型必须支持工具调用。密钥或模型缺失时返回 `MODEL_NOT_CONFIGURED`，不会伪造模型成功。模型与工具 HTTP 超时分别为45秒、15秒，整次分析150秒。

`AI_PLANNER_MODEL`、`AI_REFLECTION_MODEL` 可分别覆盖节点模型，默认继承 `AI_LLM_MODEL`。Reporter 使用确定性模板，AnalyticsEngine 使用 Decimal，无须另设模型。`AI_MAX_TOOL_CALLS` 每请求总次数默认4，上限8；`AI_MAX_REFLECTIONS` 默认2，上限3（包含首次规划/反思），不允许无限循环。DeepSeek 默认显式发送 `thinking.type=disabled`，可用 `AI_LLM_THINKING_ENABLED=true` 开启。

## 实际执行图

`Planner → Executor → Analyzer → Reflection → Reporter`。计划包含模板查询时先经独立 `TextToSQL` 节点与 TemplateTextSQL 模块验证。Planner 按输入进入 Clarification 分支；Analyzer 缺证据或 Reflection 要求补充时回到 Planner，默认最多规划两次。ToolRegistry 通过 ToolAdapter 协议调度 ErpTools。图使用 `StateGraph(TypedDict)`、真实条件边和请求内状态，没有假装实现持久化企业级 Checkpoint。

Planner 必须由真实模型返回结构化工具调用。Executor 校验工具白名单、参数精确字段、日期范围及预审核模板，再调用 Java。`execute_safe_query` 仅接受 `sales_summary` / `sales_trend` 模板，拒绝 SQL 文本。Python 不连接数据库。数量/金额来自 Java 已发货订单；不把销售额称为收入或利润。

Analyzer 使用 Decimal 计算本期与等长上期增长、日均销售额、首末日趋势和规则异常；校验汇总与逐日金额合计一致。上期为零且本期非零标记不可比。Reflection 调用模型检查证据是否充分；其文本不直接进入报告，模型不能编造数字或业务原因。Reporter 只使用确定性指标模板。

日期采用上海时区，API 为左闭右开。用户输入两个 ISO 日期按包含首尾日解释；“最近30天”包含今天。没有明确日期就澄清，超过366天或非法日期也要求澄清。利润/成本问题先解释口径限制并询问是否改为销售分析。澄清的 `context` 保存问题类型和原始问题；成功报告的 `context` 保存日期与 `metric=sales`，由 Java 保存在 MySQL，下轮作为 `resume` 回传；不是完整图状态。仅支持相邻等长上期比较，不宣称已实现任意日历月比较。WorkingMemory 和 EpisodicMemory 仅请求内生效，LongTermMemory 定义Java持久化适配端口；SemanticMemory 实际读取本地已审核的 `app/knowledge/sales.md` 销售口径，当前没有向量库/跨用户记忆。

## HTTP/SSE

`GET /health` 只返回健康与配置布尔状态，不返回密钥。

`POST /v1/analyze` 需要 `Authorization: Bearer <AI_SERVICE_TOKEN>`。

```json
{"requestId":"r1","conversationId":"c1","message":"最近30天销售分析","context":{"gatewayToken":"opaque-short-lived-capability"},"resume":{}}
```

请求拒绝额外顶层或 context 身份字段。Python 调 Java 时传服务令牌与 `X-AI-Context`，不把令牌发送给模型，也不放进 SSE。SSE 事件为 `stage`、`result`、`clarification`、`error`、`done`。流断开触发 ASGI 取消，取消传播到等待中的异步模型/工具调用。没有后台排队任务或跨进程取消注册表。

## 验证

```powershell
Push-Location agent
.venv/Scripts/python.exe -m pytest tests -q
.venv/Scripts/python.exe -m pip check
Pop-Location
```

测试中的 FakeModel/FakeTools 仅用于验证真实图控制流、确定性计算、HTTP认证、澄清恢复、有限反思、SQL/身份注入拒绝；不能代替真实模型或真实MySQL验收。依赖完整锁定见 `requirements.lock.txt`，直接依赖见 `requirements.txt`。

依赖与API依据：[LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)、[OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling)、[LangGraph 1.0.10](https://pypi.org/project/langgraph/1.0.10/)、[FastAPI 0.115.12](https://pypi.org/project/fastapi/0.115.12/)、[Uvicorn 0.34.2](https://pypi.org/project/uvicorn/0.34.2/)、[HTTPX 0.28.1](https://pypi.org/project/httpx/0.28.1/)。


AI评测统一入口：根目录 `./scripts/Test-AiEvaluation.ps1`；真实模型显式加`-Live`，先按[评测说明](../docs/ai-evaluation.md)启动隔离18081/18091服务。固定问题与独立数值基准位于evaluation/，报告在output/ai-evaluation/（忽略）。

第2阶段日期/指标澄清：见[时间范围规则](../docs/ai-time-ranges.md)。生产仍用app.main，固定时钟仅测试入口evaluation.calendar_app，真实日历评测显式`Test-AiEvaluation.ps1 -Live -Calendar`。依赖不升级，Shanghai TZif来源与许可证随agent/app/timezone_data保留。

第3阶段客户/商品分析已提供，真实工具HTTP评测显式`Test-AiEvaluation.ps1 -Live -Calendar -Dimensions`。新分支通过ToolRegistry确定性检索/澄清后端ID，不调用模型；请将dimension-http-real-tools与real-model分别解释。契约与数量/历史规则见[工具说明](../docs/ai-sales-dimensions.md)。
