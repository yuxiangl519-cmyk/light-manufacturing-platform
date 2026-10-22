# AI第4阶段：只读库存与工单缺料

后续2026-10-21已切换[模型决策循环](ai-model-loop.md)。本阶段“不调用模型/确定性分支”描述保留为历史实现；当前库存与工单工具由模型选择，Java查询、权限和缺料确定性计算继续复用本契约。

在原有 AI 会话中增加 Operations 确定性分支；不调用模型。Java `AiOperationsService` 只编排 `StockQueryService`、`ProductionService`、`MasterService` 的公开方法，不查询其他模块表或 Repository。没有依赖、Flyway 或业务写接口变更。

## 工具契约与权限

入口仍为 `POST /internal/ai/tools/{name}`，必须同时具备服务认证和短期 `X-AI-Context` 能力。Java 每次重新验证用户启用/身份版本、工厂、会话运行状态和当前权限；取消/结束后能力失效，累计最多8次。Python仍执行配置中的本轮工具上限，HTTP超时15秒、任务150秒。只读编排事务超时10秒（数据库/驱动执行限制，不承诺整个HTTP请求必在10秒内返回）。

| 工具 | 参数 | 权限 |
|---|---|---|
| get_current_stock | warehouseId?、materialId?、limit? | wms:stock:read |
| get_low_stock_alerts | warehouseId?、materialId?、limit? | wms:stock:read |
| get_production_shortage | productionOrderId，必填 | mes:production:read **且** wms:stock:read |
| get_pending_production_receipts | warehouseId?、limit? | wms:document:read |
| resolve_operations_entity | dimension、query | warehouse/material：库存读；production：生产读且库存读；receiptWarehouse：单据读 |

ID均为正整数字符串，不能接受用户/工厂/SQL或其他未声明字段。limit是整数，默认20、范围1～20。当前工具拒绝startDate/endDate/templateId/entityId及其他工具的参数；销售工具仍须日期，拒绝库存/工单字段，旧请求兼容。库存聊天要求明确仓库；内部工具可读全局前20项，但必须返回total/truncated，不能静默冒充完整清单。

库存响应：asOf（UTC ISO时刻）、timezone=Asia/Shanghai、source、scope、total（字符串）、truncated、rows。row={warehouseId,warehouseName,materialId,materialCode,materialName,type,unit,quantity,minStock,lowStock}。预警沿用余额服务的严格quantity&lt;minStock，无新增阈值规则。未指定停用资料时沿用“启用仓库×启用物料”投影，无余额按零。显式指定停用仓库或物料时沿用现有停用资料余额查询，只展示已有余额记录，不推断不存在的记录。最低库存是物料阈值，在每个仓库单独比较；并非安全库存分配优化。

缺料响应：asOf/timezone/source、productionOrderId/orderNo/status、发料warehouseId/name、bomRevision、materialSnapshot、needsIssue、items。只接受确认且未取消的工单。READY_TO_ISSUE直接复用`ProductionService.requirements`：items含requiredQuantity/availableQuantity/shortageQuantity/unit/materialId/code/name；缺料=max(确认需求−当前发料仓余额,0)。需求来自确认时保存的`production_material_snapshot`，当前BOM修改不影响快照。已领料工单（生产中/待入库/完成）返回原始用料快照、currentStock（独立当前发料仓余额）、needsIssue=false、空items及说明，**不把已经消耗的用料再次算成缺料**。草稿/取消返回409；不存在返回404。需求快照最多100行。

待入库响应：asOf/timezone/source、total/truncated、rows。限定PENDING、PRODUCTION_RECEIPT、IN、PRODUCTION_ORDER；row仅含id/documentNo/warehouseId/name/sourceId/no/status/items；item仅含materialId/code/name/unit/quantity。不输出操作人、备注、联系方式或地址。最多20单、总明细最多100行，超出明细上限422要求缩小范围；不是静默截断明细。这里只列待入库量，**不执行入库、不向stock_balance加数**。

候选只返回id/code/name，最多10个；total超过10标记truncated并要求缩小条件。搜索沿用公开服务的绑定参数与LIKE字面转义，包含停用资料。名称重复必须选择后端候选ID；澄清后重新搜索，不信任旧候选列表。工单可使用完整单号匹配；“这张工单”缺少ID/单号时只问这一项。名称暂不支持任意自由语言匹配，无法解析的物料过滤要求提供ID或`物料「名称」`，不静默扩大为全仓。

## 提问和报告

- “原料仓哪些物料库存不足？”：先解析仓库候选，再查询低库存。重名返回候选。
- “仓库ID 11 物料ID 21 当前库存”：直接绑定用户提供的后端ID，不让模型选择ID。
- “这张工单还缺哪些零部件？”：询问工单ID或完整单号，补充后继续。
- “工单ID 123还缺哪些零部件？”：检查双权限，使用该工单确认快照和当前仓库余额。
- “目前有哪些成品等待入库？”：查待执行成品入库单；按行列数量和单位。

报告始终展示查询时刻（转换为Asia/Shanghai）、实际来源与范围，分别列当前库存、最低库存阈值、工单需求、现有数量、缺料参考、待入库数量。Python用Decimal验证数量契约、阈值布尔和缺料公式后排版；Java是缺料计算来源。负数、非整数、NaN/无穷等非法证据不能生成成功报告。

当前库存是数据库当前余额，未扣“其他工单未来需求”；工单需求没有预留，多工单可竞争相同余额。待入库量没有计入可用库存，不把不同物料/仓库/单位直接相加。仅供补料参考，不声称优化了采购计划、不创建采购单、不领料、不入库。历史库存还原尚未支持，昨天/上个月/截至某日期等问题先说明限制，不能以当前余额冒充历史余额。

AI页面入口允许销售读/库存读/生产读/库存单据读任一权限，具体工具仍按上表分别授权。原会话所有权、会话+CSRF、固定工厂、SSE事件、取消及历史保存保持不变。前端示例增加低库存和待入库查询，复用现有Markdown表格。单有生产读权限可以进入聊天，但无库存读权限不能查看缺料余额。

## 验证

根目录执行`./scripts/Test-AiEvaluation.ps1`，包含Python图/工具约束、Java单元及真实mini_mfg_test工具/HTTP权限集成，报告记录逐项通过/失败、耗时及失败类型。测试时间固定或注入，当前查询asOf提供Java Clock注入，不以运行当天的业务数据作为预期。

`AiOperationsIntegrationTest`只在mini_mfg_test中通过公开服务创建资料/BOM/工单，库存变更全部走InventoryService，事务回滚。独立SELECT核对stock_balance和工单需求快照，再独立相减核对缺料；不是仅检查回答关键词。覆盖零库存、严格阈值/无预警、无缺料、待入库不记库存流水、已领料不重复补料、BOM快照与当前BOM差异、多个工单不预留、错误日期/ID/参数、候选重名及注入。

`AiIntegrationTest`使用真实HTTP/MySQL/Redis验证销售单独权限、库存单独权限、生产单独权限、单据单独权限和生产+库存双权限；保留服务认证、过期/撤销能力、未知身份字段、CSRF、所有权及取消测试。其SSE上游是传输测试桩，不能算模型验证。`test_operations.py`运行真实StateGraph，但工具适配器是确定性测试桩；不会宣称真实数据库或真实模型验证。

本阶段不要求真实模型来决定库存事实；真实模型仍须显式`-Live`。实际通过数、耗时、失败记录与未验证项见[本阶段报告](ai-inventory-report.md)。

2026-10-20增量：支持有限仓库别名、包含“·”的名称、引号编码、仓库与物料连续解析、失败名称/ID纠正及成功后换仓追问。无匹配不扩大范围，多候选仍需选择；既有权限和工具限额不变。新增真实批量35项回归与独立SQL核对，见[批量报告](ai-batch-testing.md)。
