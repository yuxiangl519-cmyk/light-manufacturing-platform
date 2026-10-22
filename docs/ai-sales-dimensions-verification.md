# AI完善第3阶段验证记录

日期：2026-10-20。实现客户/商品销售额排行、按后端ID的汇总与趋势、候选身份澄清。口径及重跑步骤见[工具说明](ai-sales-dimensions.md)，[简洁报告](ai-sales-dimensions-report.md)与[逐项结果/首轮失败](ai-sales-dimensions-results.json)。**本阶段工具检查通过，但含旧真实模型回归的整套评测未全绿，退出1。**

| 实际运行 | 结果 |
|---|---|
| 修改前 `Test-AiEvaluation.ps1` | 原81项Python、20后端单元、7项真实MySQL定向集成通过；未启用模型 |
| 新Java用例首跑 | 先因新服务缺失编译失败；实现后真实MySQL发现指定商品日界少一天，独立明细oracle期望40.00但得到20.00。定位Timestamp→JVM本地日期转换，复用既有UTC日界后修复；不修改金额预期 |
| 新Python用例与回归 | 先复现未注册工具/范围绑定、日期澄清后丢失维度、引用名称中的ID/排行/全厂词被误识别，以及数量/订单排行误当销售额排行；修复后通过 |
| `python -m pytest agent/tests -q --junitxml=output/ai-evaluation/unit.xml` | 最终105通过、1.41秒；保留1条既有Starlette/AnyIO弃用警告 |
| `mvnw.cmd -B -P integration -Dit.test=SalesAnalyticsIntegrationTest,AiIntegrationTest,SalesDimensionsIntegrationTest verify` | 23单元+13真实MySQL8.4.7定向集成通过，无失败/错误/跳过；BUILD SUCCESS，30.000秒 |
| `Test-AiEvaluation.ps1 -Live -Calendar -Dimensions` | 最终非零退出，保留真实模型失败，不删断言/自动重试到成功 |
| dimension-http-real-tools层 | 7/7通过，5.105秒；真实Java→FastAPI/LangGraph→Java工具→mini_mfg_test，维度分支不调用模型 |
| real-model层 | 最终22/23通过，153.696秒；calendar_yesterday失败，安全错误码MODEL_REQUEST_FAILED |
| 前端 `npm run test:unit -- --run` | 10/10通过；原SSE、Markdown/安全表格测试继续兼容 |
| `pip check` / `compileall -q agent/app agent/evaluation` | 无依赖冲突，编译通过；Java/Vue/Python依赖版本和锁文件未升级 |

维度真实HTTP覆盖：原固定客户排行、上个月销售额最高客户、上个月商品排行、标准办公台式机最近30天（重名时评测模拟用户明确选ID）、某客户最近一个月补充ID、真实同名停用客户→拒绝无效ID→重新选择、含LIKE/SQL特殊字符的未知名称。HTTP只追加测试库会话、两条同名停用客户及无权限测试用户，不追加销售交易；独立查询结果取已有测试库数据。

真实MySQL覆盖单头金额不被明细放大、商品只计自身line_amount、同单多商品去重、不同单位不相加、草稿/确认/取消/删除排除、结束日排除、等长上期、缺日补零、停用/改名/历史快照、多候选及截断、未知/非法ID、范围与排行上限、参数化LIKE、权限/服务认证/能力失效/CSRF和旧工具夹带维度参数拒绝。销售夹具及其资料事务回滚。JDBC单元测试核对每条语句5秒、排行/候选maxRows及绑定参数，不把它称为慢SQL压测。

独立oracle直接读单头/明细，在Python按ID分组、去重订单、按快照单位累计；逐字段核对ID顺序、快照名称、金额、订单、日期、来源、币种和分单位数量。反向测试先复现“2台”被“12台”子串误判通过，再改成解析单位数量映射的数值比较。未把“回答含关键词”当数值正确证据。JSON公开快照只保存状态、耗时与安全失败原因，不保存原报告、联系方式或凭据。

第1轮旧真实模型有两项失败：calendar_last_week为MODEL_REQUEST_FAILED；tool_failure未进入期望工具错误（当时记录Wrong real fault outcome，未记录实际错误码，不伪造供应商根因）。补上安全错误码诊断，并修复名称范围和排序指标解析后重启隔离Agent，执行最终整套：维度7项再次通过；旧模型calendar_yesterday仍MODEL_REQUEST_FAILED，其他22项通过。失败保持在报告中，模型供应商侧原因未确认，没有继续重跑直到全绿。该失败未生成假业务报告。

基线版本v3保留原问题，明确将“客户维度暂不支持”预期升级为排行；保留不静默转全厂、日期/金额/权限等检查。原前两阶段历史报告未覆盖。real-model层含模型前置拒绝/澄清和取消用例，并非每个案例都发模型请求；两项故障探针仍仅Agent真实网络范围。维度分支不调模型，候选名称、ID、数量也不送模型，不能把7项工具HTTP通过称为7项真实模型推理通过。

未验证/未实现：供应商失败根因、完整库存/并发及其余后端集成回归、前端构建/真实浏览器交互、真实Java转发故障注入、生产容量/TLS/跨实例、任意名称自然语言解析、客户×商品同时筛选、销量/订单数排行、利润/收入确认。报告保留原日均/首末方向，完整日趋势由工具提供，未新增图表。

无Flyway/业务表/库存状态规则变更；不清库、不写开发库评测会话或交易、不运行参考项目。没有覆盖开发服务配置或重启开发服务；本次额外测试18081/18091停止后结束本阶段。没有Git提交/推送，不继续下一阶段。
