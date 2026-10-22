# AI完善第3阶段：客户与商品销售分析工具

2026-10-21后续[模型循环改造](ai-model-loop.md)将客户/商品工具交给模型选择，替代本阶段的确定性意图路由。ID必须来自用户明确指定或经后端解析核验，日期与数值仍由代码控制；本页Java工具、权限及金额口径保持有效。

本阶段沿用实际销售单/明细和已确认快照，不增加库存、利润或收入核算。请求仍经Vue→Java会话/CSRF→FastAPI/LangGraph→ToolRegistry→Java能力网关。维度分支由确定性代码识别范围、检索身份并计算/呈现证据，不把名称选择或ID交给模型。全厂分析保留原真实模型工作流。

## 核对过的业务事实

- `sales_order`实际字段：customer_id、partner_name、total_amount、status、created_at、deleted_at；`sales_order_item`为sales_order_id、material_id、material_code、material_name、unit、quantity、unit_price、line_amount。
- SalesOrderService确认时冻结客户/物料名称、物料编码和单位；发货进入SHIPPED，不能确认后编辑/反冲。OrderDraftValidator用Money逐行计算line_amount，再相加得到total_amount；不在分析中重新用现价计算。
- uq_sales_item_material限制同一销售单同一物料只有一行；已引用物料编码和单位不能修改，名称可改。候选查当前资料（含停用）并匹配所选期间SHIPPED快照的历史名称/编码；报告名称采用期间内按created_at、订单ID最近的销售快照。
- 跨模块仅调用公开应用服务：AI网关调用ERP的SalesDimensionsService；ERP通过Master的SalesEntityLookupService获得最小候选，不访问Master Repository或联系方式。

## 口径与上限

总销售与维度查询共用SalesAnalyticsService.ELIGIBLE，过滤SHIPPED、deleted_at为空及销售单created_at区间。客户金额只从单头total_amount取值，不连接明细放大金额或订单数；商品金额只累计该物料的line_amount。两者都是既定人民币含税金额，不称收入或利润。商品订单数COUNT(DISTINCT sales_order_id)，不能把不同商品的订单数相加当全厂订单数。

日期沿用Asia/Shanghai、左闭右开、1～366天及相邻等长上期；日界复用既有UTC转换，含历史夏令时。上个月=上月（完整自然月）；最近一个月明确解释为最近30天（含今天），报告展示实际日期。指定客户的“采购情况”表示本工厂向该客户的已发货销售，不查询本工厂采购单。

每个SQL语句5秒超时；排行默认10名、最多20名、销售额降序且ID升序打破并列。候选最多10个，额外探测第11个后标truncated；需要用户缩小条件。名称/编码是1～120字符的字面子串，转义LIKE通配符并绑定参数。单对象趋势最多366天，数量最多10个单位组；排行内部最多探测201行、趋势3661行，超过上限失败，不返回静默截断的统计。Python工具HTTP超时仍为15秒，单任务150秒；5秒指每条SQL，不宣称多语句工具总耗时必在5秒内。

quantity只返回`quantitiesByUnit={"台":"4","套":"2"}`，不提供跨单位总quantity。客户排行/汇总的数量组为空；商品排行不提供跨商品数量总计。即便遇到异常历史单位混用也分组或受上限拒绝，不相加。商品统计按物料ID合并金额、数量按快照单位分组；利用上述唯一约束，各单位组内去重订单可合为同一商品的去重订单数。销量/数量/订单数排行暂不支持，必须明确改问销售额排行，不静默换指标。

## Java工具契约

统一内部入口`POST /internal/ai/tools/{name}`，Bearer服务令牌+X-AI-Context短期能力必需。每次调用重查用户启用/身份版本、erp:sales:read、固定工厂、会话run状态；结束/取消撤销能力，累计最多8次。额外tenant/user/sql字段仍拒绝。所有新工具仍要求startDate/endDate；其他参数不得混用。

| 工具 | 额外参数 | 响应 |
|---|---|---|
| get_customer_sales_ranking | limit?（JSON整数，1～20） | metadata + dimension=customer、limit、rows |
| get_product_sales_ranking | limit?（JSON整数，1～20） | metadata + dimension=product、limit、rows |
| resolve_sales_entity | dimension=customer/product，query | dimension、candidates、truncated |
| get_entity_sales | dimension=customer/product，entityId（正整数字符串） | metadata + entity、summary、trend |

metadata={period,currency,definition,source,asOf,dimension}。排行row={id,name,code,salesAmount,orderCount,quantitiesByUnit}，按ID区分同名对象；客户code为空（单头没有客户编码快照），商品code取销售明细快照。候选={id,code,name,unit,enabled}，来自当前资料，停用也能选择；name可能与用于匹配的历史快照名称不同。没有contactName、phone、address、单号、单据明细或单价。

entity={id,code,name,unit,enabled,nameSource}：本期有成交时name/code采用最近销售快照（客户只有name快照），nameSource=latestSalesSnapshot；本期无成交则名称标为masterNoCurrentSales，不假称成交快照。summary沿用本期/上期金额和订单数结构，额外分单位数量；当前聊天报告保留日均/首末方向，完整逐日序列由工具响应提供，没有新增图表。trend每点={date,salesAmount,orderCount,quantitiesByUnit}，缺日补0。所有ID、金额、订单数和数量仍为字符串；控制参数limit为整数。

旧三个全厂工具参数保持兼容；新可选DTO字段仅对应新工具。给旧工具夹带entityId/dimension等参数会400，防止看似筛选却返回全厂值。SQL模板仍只有sales_summary/sales_trend，不开放自由SQL。

## Python身份与澄清

ToolRegistry注册全部工具。模型schemas仅保留原三个全厂工具；默认validate_call拒绝维度工具，只有确定性分支显式绑定dimension/query/entityId后才允许调用。维度分支没有ModelClient.plan/reflect调用，返回值和名称也不送模型，不把这一路径称为真实模型推理。

名称匹配一个且未截断时，使用后端实际返回的ID；同名/多匹配时列出ID、编码、名称、单位及停用状态，用户明确回复`选择客户ID 123`或`选择商品ID 456`。不接受“模型认为第一个最好”，也不根据过往缓存候选直接执行：续问会重新检索，ID不在当前候选中就再次澄清。用户可明确提供已知后端ID（回复以客户ID/商品ID开头并留空格）；Java仍核对对象存在及权限。未提供名称的“某个客户”只问对象，不查询全厂。

日期澄清后保留原客户/商品意图；指标/日期不明仍沿用第2阶段逐项澄清。同一问题同时筛客户和商品暂不支持，需先选一个维度。复杂名称建议使用中文引号，名称里的ID/排行/全厂/商品等词不会改变查询范围。全厂/总体的明确切换才回到全厂分析。非法候选、ID、日期、金额/订单/数量不一致会失败，不输出假报告。

示例：

- 上个月销售额最高的客户有哪些？
- 上个月商品销售额排行前10名。
- 标准办公台式机最近30天卖了多少？（重名时补充后端ID）
- 某个客户最近一个月采购情况如何？（随后补充名称/编码或客户ID）
- 客户“某公司”2026-09-01至2026-09-30销售汇总和趋势。

## 重跑评测

```powershell
# 不调用模型：Python确定性回归 + 真实mini_mfg_test定向集成
./scripts/Test-AiEvaluation.ps1
# 按ai-time-ranges.md在18081/18091启动隔离后端和固定2026-10-20测试Agent
# 显式原真实模型回归 + 新增维度真实HTTP工具案例
./scripts/Test-AiEvaluation.ps1 -Live -Calendar -Dimensions
```

版本为ai-baseline-v3：保留原客户问题，将其“暂不支持”预期明确更新为真实排行；不删除权限、CSRF、金额精度、日期及模型失败检查。新dimension-http-real-tools层与real-model层分开统计。独立oracle直接读单头/明细，在Python按ID分组、去重订单、按单位计算，不复用生产聚合SQL；金额/订单/分单位数量逐字段比较，反向测试确保“2台”不能错误匹配“12台”。

数据库用例销售夹具事务回滚；HTTP维度评测只追加测试库会话、两条同名停用客户资料及随机无权限用户，不追加销售交易、不清库，不写开发库。实际结果和未验证范围见[第3阶段验证](ai-sales-dimensions-verification.md)。
