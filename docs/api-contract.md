# API 契约

## 2026-10-21 模型决策循环

外部会话/SSE/业务工具HTTP契约保持兼容，新增允许阶段Decision（模型决策）与Tools（执行受控工具），不发送模型思考。Python生产入口改为模型原生工具循环；LLM不接收身份认证字段、SQL或自行计算日期，仍由ToolRegistry和Java逐次校验。澄清conditions只保存既有白名单条件；context_json版本1增加可选retryRequest（最多400字符、脱敏的最近失败问题），显式重试使用、成功删除。部分工具失败可返回evidence澄清并提供重试；完整结论只引用本轮核验报告。详见[协议与边界](ai-model-loop.md)、[实测](ai-model-loop-report.md)。

基址 `/api/v1`，JSON UTF-8，camelCase，中文错误提示。禁止直接暴露JPA Entity。第4阶段已实现会话/系统、基础资料/BOM、采购/生产/销售最简闭环及WMS查询/生产入库执行。工作台统计及前端仍未实施。

## 公共类型

- ID/数量/金额均 JSON string；ID 为正整数字符串，quantity 为1～12位十进制正整数，余额/阈值允许"0"。不接受 number、小数、指数、负数、空串。金额输入规范 `0` 或非负十进制且最多两位小数，最多16位整数，输出如"1200.00"。
- enum 为英文稳定字符串，中文标签由前端固定映射；时间为 UTC ISO8601，例如 `2026-10-17T08:00:00.000Z`，显示上海时间。
- version 为非负安全整数 number；明细 lineNo 为正整数 number；分页 page 从1起，size 默认20/最大100，sort 白名单 `createdAt,desc` 或 `id,desc`，并加 id 次排序。普通字符串 trim，名称不为空。
- 列表 `Page<T>={items:T[],page:number,size:number,total:string}`。列表过滤 q（最多100字）、status、from/to（ISO 时间，左闭右开）、warehouseId；各资源另有下列专用过滤。关联选择器复用资料列表，size<=100，不返回敏感字段。
- 通用可变DTO返回id、version、createdAt、updatedAt；业务头还返回createdByName/status。PO/SO/MO单号服务端按上海日期+UUID生成，最长64，数据库唯一；库存单保持ST-UUID。极小概率碰撞由唯一键拒绝并回滚，客户端重试创建；当前不承诺自动重试，不使用共享编码服务。
- 草稿创建 POST 返回201及完整 Detail；读取/更新/动作返回200；草稿 DELETE 返回204。未找到或已逻辑删除返回404。PUT 为完整替换可写字段，不接受 status、金额合计、来源、审计字段等未知字段。
- 写草稿 PUT/DELETE 都需要 version（PUT body；DELETE query），动作 body 为 `{version:number}`。先验权，再锁头；已成功动作优先按命令记录返回，未执行动作才检查 version 和状态。动作响应 `{operationId:string,replayed:boolean,document:Detail,relatedDocuments:StockDocumentSummary[]}`，其中 document 为当前视图，并非初次响应永久快照。

完整枚举：

| 类型 | 值 |
| --- | --- |
| MaterialType | RAW_MATERIAL / FINISHED_GOOD |
| PurchaseStatus | DRAFT / CONFIRMED / RECEIVED / CANCELLED |
| ProductionStatus | DRAFT / READY_TO_ISSUE / IN_PRODUCTION / PENDING_INBOUND / COMPLETED / CANCELLED |
| SalesStatus | DRAFT / CONFIRMED / SHIPPED / CANCELLED |
| StockKind | PURCHASE_RECEIPT / PRODUCTION_ISSUE / PRODUCTION_RECEIPT / SALES_SHIPMENT |
| StockStatus | PENDING / EXECUTED |
| StockDirection | IN / OUT |
| SourceType | PURCHASE_ORDER / PRODUCTION_ORDER / SALES_ORDER |

错误响应（包括 Security 和验证错误统一格式）：

```json
{"code":"INSUFFICIENT_STOCK","message":"内存条库存不足","traceId":"request-id","fieldErrors":[],"details":{"warehouseId":"1","materialId":"3","requiredQuantity":"20","availableQuantity":"19"}}
```

`fieldErrors=[{field:"items[0].quantity",message:"数量须为正整数"}]`。400 VALIDATION_FAILED/INVALID_JSON；401 UNAUTHENTICATED；403 FORBIDDEN/CSRF_INVALID；404 NOT_FOUND；409 INVALID_STATE/VERSION_CONFLICT/INSUFFICIENT_STOCK/REFERENCE_IN_USE/CONCURRENT_OPERATION/DUPLICATE_CODE；429 LOGIN_RATE_LIMITED（Retry-After 秒）；503 DEPENDENCY_UNAVAILABLE；500 INTERNAL_ERROR。错误不得返回堆栈、SQL、连接串、凭据。数量不足的 details 只在已具备对应动作权限时返回。

## 会话

| 方法/路径 | 请求 → 响应 | 访问 |
| --- | --- | --- |
| GET /auth/csrf | 无 → `{headerName:"X-CSRF-TOKEN",token:string}`，Cache-Control:no-store | 匿名可用 |
| POST /auth/login | `{username:string,password:string}` → `CurrentUser` 并设会话 Cookie | 匿名，需CSRF，限流 |
| POST /auth/logout | 无body →204；清会话/Cookie | 登录，需CSRF |
| GET /auth/me | 无 → `CurrentUser` | 登录 |
| GET /auth/permissions | 无 → `{permissions:string[]}` | 登录，读取当前有效权限 |
| PUT /auth/password | `{currentPassword,newPassword}` →204，使旧会话失效 | 登录，需CSRF |

CurrentUser=`{id,username,displayName,roles:string[],permissions:string[]}`。username 最大50、规范化小写；新密码12～64字符且 UTF-8 字节<=72（BCrypt 限制），不提供公共注册。登录失败统一“用户名或密码错误”，不泄露账号存在/停用信息；敏感字段不记录日志。Cookie `LIGHT_MFG_SESSION`，HttpOnly/Lax/path=/；登录轮换 ID，登录/退出后前端重新 GET csrf。会话只靠 Cookie，不用 localStorage JWT。

## 固定权限点与角色默认值

下表花括号为明确的多个固定点，例如 master:material:read 与 master:material:write；不支持运行时通配符。

| 固定权限 | ADMIN | OPERATOR | VIEWER |
| --- | --- | --- | --- |
| system:user:{read,write} | 全部 | 无 | 无 |
| system:role:{read,write} | 全部 | 无 | 无 |
| system:log:read、system:api-docs:read | 全部 | 无 | 无 |
| master:material:{read,write} | 全部 | 全部 | read |
| master:customer:{read,write} | 全部 | 全部 | read |
| master:supplier:{read,write} | 全部 | 全部 | read |
| master:warehouse:{read,write} | 全部 | 全部 | read |
| erp:purchase:{read,write,confirm,receive} | 全部 | 全部 | read |
| erp:sales:{read,write,confirm,ship} | 全部 | 全部 | read |
| mes:bom:{read,write} | 全部 | 全部 | read |
| mes:production:{read,write,confirm,issue,report} | 全部 | 全部 | read |
| wms:document:read、wms:stock:read、wms:ledger:read | 全部 | 全部 | 全部 |
| wms:receipt:execute | 全部 | 有 | 无 |
| workbench:read | 有 | 有 | 有 |

固定点共34个。角色管理页面可修改 OPERATOR/VIEWER 或自建角色的固定点集合；ADMIN 权限全集固定，内置角色不能删除。自建角色不能创建新权限点。分配可写动作点时同时要求对应 read 点，例如 receive→purchase:read，execute→wms:document:read；BOM/业务编辑还依赖相关资料 read 作为页面使用依赖，后端提供明确校验提示。无复杂表达式。

## 系统接口

| 方法/路径 | DTO/行为 | 权限 |
| --- | --- | --- |
| GET /system/users；GET /system/users/{id} | Page<User>/User | system:user:read |
| POST /system/users | UserCreate→User | system:user:write |
| PUT /system/users/{id} | UserUpdate→User | system:user:write |
| PUT /system/users/{id}/roles | `{version,roleIds:string[]}`→User | system:user:write |
| POST /system/users/{id}/reset-password | `{version,newPassword}`→204，递增authVersion | system:user:write |
| GET /system/roles；GET /system/roles/{id} | Page<Role>/Role | system:role:read |
| POST /system/roles；PUT /system/roles/{id} | RoleWrite→Role | system:role:write |
| DELETE /system/roles/{id}?version= | 仅未分配的自建角色 | system:role:write |
| GET /system/permissions | `{code,name,module}[]` 固定目录 | system:role:read |
| PUT /system/roles/{id}/permissions | `{version,permissionCodes:string[]}`→Role | system:role:write |
| GET /system/operation-logs | Page<OperationLog>，过滤actorId/module/success/from/to | system:log:read |

UserCreate={username,displayName,initialPassword,enabled,roleIds}；UserUpdate={version,displayName,enabled}；User={id,version,username,displayName,enabled,roleIds,createdAt,updatedAt}，不返回hash/authVersion。RoleWrite={code,name,description}（PUT加version且code不可改）；Role={id,version,code,name,description,builtin,permissionCodes}。OperationLog={id,actorId?,actorName,module,action,resourceType?,resourceId?,resourceNo?,success,errorCode?,summary,traceId,clientIp,occurredAt}。

已实现格式规则：username trim后转小写，仅允许`[a-z0-9_.-]+`、最多50字符；角色code为`[A-Z][A-Z0-9_]{0,39}`，大写且创建后不可改。角色授权最多34个固定点；ADMIN权限全集不可削减，合法JSON尝试改变该状态返回409 INVALID_STATE。ID请求必须为字符串，version必须为JSON整数，不进行宽松数值/字符串转换。操作失败审计errorCode记录HTTP状态（例如HTTP_403），不记录密码或原始请求体。

用户角色分配只传已存在 roleId；创建用户时涉及角色列表另需 role:read。用户不提供删除。最后一个启用 ADMIN、最后一个系统管理授权保障在事务内锁定 ADMIN 角色行再检查，避免两个管理员互停导致锁死。

## 基础资料接口

四类资源路径 `/materials`、`/customers`、`/suppliers`、`/warehouses`，均支持 GET集合、GET /{id}、POST、PUT /{id}；无 DELETE（404）。分别使用 `master:{material|customer|supplier|warehouse}:read/write`。通用过滤q（名称/编码，最多100字，按字面匹配）、enabled、page/size/sort，物料另有 type。sort仅`createdAt,desc`或`id,desc`。启停用通过带最新version的完整PUT实现，历史详情不按enabled隐藏。

- MaterialWrite={code(<=50),name(<=120),type,unit(<=10),specification(<=255),minStock,enabled}；PUT加version，受引用后code/unit不可改，type始终不可改。Material=Write+id/version/createdAt/updatedAt。
- PartnerWrite（客户/供应商）={code,name,contactName(<=80),phone(<=40),address(<=255),enabled}；WarehouseWrite={code,name,address,enabled}；PUT均加version，返回共有字段。编码建立后不可改。

## BOM 接口

GET /boms，GET /boms/{id} 需 mes:bom:read；POST /boms，PUT /boms/{id} 需 mes:bom:write。过滤 q/productMaterialId/enabled/page/size，固定createdAt和id倒序。每成品最多一份启用BOM，数据库条件唯一冲突返回409 DUPLICATE_CODE。允许多份停用BOM；无DELETE、无版本审批流程。

BomWrite={code,name,productMaterialId,enabled,items:[{materialId,quantityPer}]}；PUT加version。code<=50、name<=120，产品及code建立后不可改，组件原料去重1～100条。BomDetail={id,version,code,name,productMaterialId,productCode,productName,revision,enabled,items:[{id,lineNo,materialId,materialCode,materialName,unit,quantityPer}],createdAt,updatedAt}。每次更新revision+1，工单引用确认时版本。新引用及启用需启用资料；原引用停用后仍可停用已有BOM，不能借停用更新新增停用组件。

`ProductionService`对外提供下面工单接口，内部复用`ProductionConfirmationService`复制快照；来源工单锁定后，先检查唯一成功命令，再比较状态/version。内部旧确认服务保留单次语义，HTTP动作通过外层命令服务实现重放。

## 采购接口

| 方法/路径 | DTO/动作 | 权限 |
| --- | --- | --- |
| GET /purchase-orders；GET /purchase-orders/{id} | Page<PurchaseSummary>/PurchaseDetail；可过滤supplierId | erp:purchase:read |
| POST /purchase-orders | PurchaseWrite→PurchaseDetail | erp:purchase:write |
| PUT /purchase-orders/{id}；DELETE /purchase-orders/{id}?version= | 草稿整单替换/逻辑删除 | erp:purchase:write |
| POST /purchase-orders/{id}/cancel | DRAFT→CANCELLED | erp:purchase:write |
| POST /purchase-orders/{id}/confirm | DRAFT→CONFIRMED | erp:purchase:confirm |
| POST /purchase-orders/{id}/receive | CONFIRMED→RECEIVED，原料全量入库 | erp:purchase:receive |

PurchaseWrite={supplierId,warehouseId,remark,items:[{materialId,quantity,unitPrice}]}，PUT加version。PurchaseSummary={id,version,orderNo,supplierId,supplierName,warehouseId,warehouseName,status,totalAmount,createdAt,updatedAt,createdByName}。PurchaseDetail=Summary+{remark,confirmedAt?,receivedAt?,cancelledAt?,items:MoneyLine[],stockDocuments:StockDocumentSummary[]}。

unitPrice明确为人民币含税单价，不拆税、不做会计核算。输入非负String，最多16位整数/两位小数；后端以quantity×unitPrice计算lineAmount，再汇总totalAmount，DECIMAL(18,2)/HALF_UP并检查溢出。0单价允许；禁止number、小数数量、前导零/指数及超过两位小数金额。POST/PUT不得携带status、totalAmount或lineAmount（未知字段400）。确认再次检查启用资料并冻结显示快照；已确认后资料停用不妨碍执行原单据收发。

MoneyLine={lineNo,materialId,materialCode,materialName,unit,quantity,unitPrice,lineAmount}。receive body仅version，直接产生EXECUTED采购入库，不另设“采购入库执行”入口。

## 生产接口

| 方法/路径 | DTO/动作 | 权限 |
| --- | --- | --- |
| GET /production-orders；GET /production-orders/{id} | Page<ProductionSummary>/ProductionDetail；可过滤productMaterialId | mes:production:read |
| GET /production-orders/{id}/material-requirements | 冻结所需数量、当前库存与缺料；只读、不预留库存 | mes:production:read且wms:stock:read |
| POST /production-orders | ProductionWrite→ProductionDetail | mes:production:write |
| PUT /production-orders/{id}；DELETE /production-orders/{id}?version= | 草稿替换/逻辑删除 | mes:production:write |
| POST /production-orders/{id}/cancel | DRAFT→CANCELLED | mes:production:write |
| POST /production-orders/{id}/confirm | DRAFT→READY_TO_ISSUE，保存BOM快照 | mes:production:confirm |
| POST /production-orders/{id}/issue | READY_TO_ISSUE→IN_PRODUCTION，一次扣全部原料 | mes:production:issue |
| POST /production-orders/{id}/report | IN_PRODUCTION→PENDING_INBOUND，生成PENDING入库 | mes:production:report |

ProductionWrite={productMaterialId,bomId,plannedQuantity,issueWarehouseId,receiptWarehouseId,remark}，PUT加version。服务器校验 bomId 与成品匹配；不接受组件明细或自选配。

ProductionSummary={id,version,orderNo,productMaterialId,productCode,productName,unit,plannedQuantity,issueWarehouseId,issueWarehouseName,receiptWarehouseId,receiptWarehouseName,status,createdAt,updatedAt,createdByName}。

ProductionDetail=Summary+{bomId,bomCode,bomRevision?,remark,confirmedAt?,issuedAt?,reportedAt?,completedAt?,cancelledAt?,materialSnapshot:[{lineNo,materialId,materialCode,materialName,unit,quantityPer,requiredQuantity}],report:{id,reportedQuantity,reportedAt,reportedByName}?,stockDocuments:StockDocumentSummary[]}。草稿快照为空，页面从 BOM 详情单独展示“当前BOM预览”；确认后展示冻结快照。

materialSnapshot每行还返回字符串id，供来源追踪。`material-requirements`返回`{productionOrderId,status,hasShortage,items:[{lineNo,materialId,materialCode,materialName,unit,requiredQuantity,availableQuantity,shortageQuantity,shortage}]}`，数量均String，shortageQuantity=max(required-available,0)。草稿/取消工单不可查询该冻结快照预览（409）；确认后可查，页面仅在待领料阶段用其提示。此查询结果不是预留或领料凭证，issue在事务内重新检查真实余额。

生产POST/PUT校验启用且与成品匹配的bomId，不接收组件/实际领料数量/实际报工数量。report只收version，报工量固定等于计划量，返回PENDING生产入库摘要且整机库存不变。工单列表支持q（单号/成品名）、status、productMaterialId、warehouseId（领料或成品仓）、from/to/page/size/sort。

## 销售接口

与采购结构对称，路径 `/sales-orders`、`/{id}`，列表/详情需 erp:sales:read，创建/草稿PUT/DELETE/cancel需 erp:sales:write，confirm需 erp:sales:confirm，ship需 erp:sales:ship。取消/确认/发货均 POST，body仅version。状态 DRAFT→CONFIRMED→SHIPPED；DRAFT可CANCELLED。

SalesWrite={customerId,warehouseId,remark,items:[{materialId,quantity,unitPrice}]}。SalesSummary 将 PurchaseSummary 的supplierId/supplierName替换为customerId/customerName；SalesDetail将receivedAt替换为shippedAt，其余字段一致。只允许成品物料。发货产生EXECUTED销售出库。

## WMS 接口

| 方法/路径 | 响应/行为 | 权限 |
| --- | --- | --- |
| GET /stock-documents；GET /stock-documents/{id} | Page<StockDocumentSummary>/StockDocumentDetail，过滤q（库存/来源编号）、kind/status/warehouseId/sourceType/sourceId/from/to/page/size/sort | wms:document:read |
| POST /stock-documents/{id}/execute | body仅version；仅PENDING生产入库，关联工单完成 | wms:receipt:execute |
| GET /stock-balances | Page<StockBalance>，过滤warehouseId/materialId/type/lowStockOnly/enabled（默认true） | wms:stock:read |
| GET /stock-ledgers | Page<StockLedger>，过滤warehouseId/materialId/sourceType/sourceId/from/to | wms:ledger:read |

StockDocumentSummary={id,version,documentNo,kind,direction,status,warehouseId,warehouseName,sourceType,sourceId,sourceNo,createdAt,executedAt?}；sourceType=PURCHASE_ORDER/PRODUCTION_ORDER/SALES_ORDER，为已保存的逻辑来源类型。StockDocumentDetail=Summary+{executedByName?,items:[{id,lineNo,sourceLineId,materialId,materialCode,materialName,unit,quantity}]}。库存单号ST-UUID，最长64。单据列表sort使用createdAt,desc或id,desc。

execute响应`{operationId,replayed,document:StockDocumentDetail,relatedDocuments:[该库存单摘要],relatedProductionOrder:{id,version,orderNo,status}}`。应用层先校验不可变来源类型并锁真实工单，再校验报工/物料/仓库/数量关联；同事务执行库存并将工单COMPLETED。该入口不能执行采购、领料、销售库存单，也不能手工创建入库内容。已成功动作以命令记录返回重放，不重复入库。

StockBalance={warehouseId,warehouseName,materialId,materialCode,materialName,type,unit,quantity,minStock,lowStock}。库存默认以启用仓库×启用物料左连接，缺失余额quantity="0"；enabled=false时只查询仓库或物料已停用的实际余额行，enabled省略使用默认true，不为展示生成数据库零余额行。q按物料名称/编码字面匹配，sort白名单`materialCode,asc`（默认）/`warehouseId,asc`，不允许SQL字段透传。lowStock为quantity<minStock。

StockLedger={id,warehouseId,warehouseName,materialId,materialCode,materialName,unit,stockDocumentId,stockDocumentItemId,documentNo,kind,sourceType,sourceId,sourceNo,sourceLineId,changeQuantity,beforeQuantity,afterQuantity,operatorName,occurredAt}；changeQuantity可为负整数字符串，其余数量>=0。名称/单位使用库存单快照；sort默认id,desc，也支持createdAt,desc（按occurredAt及id倒序）。

内部库存契约：`StockPosting(kind,sourceId,sourceNo,warehouseId,operatorId,lines[{sourceLineId,materialId,quantity:BigDecimal}])`。来源类型由kind固定推导，物料明细去重，数量正规化整数。`post`直接整单执行，`prepareProductionReceipt`仅准备生产入库，`execute`执行既存单；均要求调用方已有事务。返回内部`Result(id:long,documentNo,status,version,replayed)`，不是HTTP DTO。库存错误新增409 SOURCE_CONFLICT；停用引用409 DISABLED_REFERENCE，冻结字段409 REFERENCED_DATA/IMMUTABLE_FIELD。库存不足当前details为空。

不提供通用库存单创建、编辑、删除、取消；库存余额/流水没有写接口。工单页“成品入库”跳到关联库存单再执行，不重复暴露第二个记账端点。

## 工作台（第5阶段实际契约）

GET /workbench?from=&to=，需 workbench:read。from/to为Instant，左闭右开；默认上海当月，正区间最多366天。

响应字段：
- period:{from,to}。
- cards:{purchaseCount,salesCount,receivedPurchaseAmount,shippedSalesAmount,confirmedPurchaseAmount,confirmedSalesAmount}。数量和金额均字符串，缺少对应采购/销售read权限的字段省略。
- todos:[{type,label,count,route}]，包括待收货、待领料、待报工、待成品入库、待发货；需对应read和动作权限。
- trend:[{date,purchaseCount?,salesCount?}]，固定最近7个上海自然日，缺数据补0，按订单创建日期计数，不受from/to影响。
- production?:{byStatus:[{status,count}],completedQuantities:[{unit,quantity}]}，需mes:production:read。状态为当前全量；完成量按completedAt区间、按单位分组，不跨单位累加。
- inventory?:{lowStockCount,items:StockBalance[]}，需wms:stock:read，当前启用资料库存预警，items最多10条。
- recentOrders:[{id,orderNo,kind,partnerName,status,totalAmount,createdAt}]，按权限过滤后合并最新5笔采购/销售。
- recentProduction:[{id,orderNo,productName,plannedQuantity,unit,status,createdAt}]，有生产read权限时最新4笔，否则空数组。

订单数量排除已取消/删除单；金额按createdAt区间及当前业务状态统计。receivedPurchaseAmount仅RECEIVED，shippedSalesAmount仅SHIPPED；confirmedPurchaseAmount为CONFIRMED/RECEIVED，confirmedSalesAmount为CONFIRMED/SHIPPED。金额不是收入或利润。待办、预警和状态分布均为当前状态。

库存单分页新增direction=IN/OUT可选过滤，非法值400；在服务器分页前过滤，不能在前端截取分页结果充当入库/出库列表。
## 契约验证

后续用 Springdoc 生成的 OpenAPI 对照本表；TypeScript DTO 与接口同名同字段，ID/金额/数量不转浮点进行结算。类型、权限、状态、可空字段及字段长度都有契约测试。未实现之前不生成假 API 返回结果。

系统用户/角色q搜索为字面子串匹配，百分号和下划线不作为通配符；第5阶段为此补充显式JPA LIKE转义及真实MySQL回归。

## AI 智能分析（2026-10-19增量）

现有/API统一错误、ID字符串、Cookie/CSRF规则继续生效。对外全部路径/api/v1/ai/conversations需要erp:sales:read；会话按固定部署工厂+当前用户归属过滤，越权详情404。GET列表最多50条；POST body={title?}返回201 Conversation{id,title,status,updatedAt}；GET /{id}返回{id,title,status,messages:[{id,role,content,createdAt}],pendingQuestion?}，消息按ID正序最多200条；POST /{id}/cancel无body返回204。status=IDLE/RUNNING/CLARIFICATION/COMPLETED/ERROR/CANCELLED。

POST /{id}/messages body={message}，非空最多4000字符，CSRF必须。返回text/event-stream，Cache-Control:no-cache,no-store，X-Accel-Buffering:no。事件stage{node,message}、result{content,context?}、clarification{content,context}、error{code,message}、done{status}。Vue使用Fetch流读取，不能用EventSource发POST；不自动重试写操作。流错误不生成假成功；MySQL保存最后状态与结构化context。

错误补充：AI_NOT_CONFIGURED/AI_CONFIG_INVALID(503)、AI_BUSY(409)、AI_CAPACITY/AI_TOOL_LIMIT(429)、AI_HISTORY_LIMIT(409)、AI_CONTEXT_INVALID(403)。流内业务错误以error事件报告并done，HTTP已经200时不能再改状态码。模型失败MODEL_REQUEST_FAILED、ERP失败ERP_TOOL_FAILED、未配置模型MODEL_NOT_CONFIGURED，不返回请求头或Key。

内部POST /internal/ai/tools/{get_sales_summary|get_sales_trend|execute_safe_query}采用独立无会话认证链，仅该前缀免CSRF。必须Bearer共享服务令牌和X-AI-Context短期能力令牌，不接受浏览器Cookie代替。Java签发上下文绑定当前用户版本、工厂、conversation和run，180秒有效，每调用重查用户与销售权限、运行状态；最多8次，结束/取消撤销。额外JSON身份/tenant/sql字段拒绝。

请求={startDate,endDate,templateId?}，ISO上海日期，endDate不包含当天，跨度1～366天。execute_safe_query仅templateId=sales_summary/sales_trend，不开放任意SQL。summary响应={period,previousPeriod,current:{salesAmount,orderCount},previous:{salesAmount,orderCount},currency,definition,source,asOf}；trend响应={period,points:[{date,salesAmount,orderCount}],currency,definition,source,asOf}，缺日补0；金额/count字符串。参数化查询限定SHIPPED、deleted_at为空和created_at区间，5秒SQL超时，最多366点。Python不连接MySQL。

Java POST Python /v1/analyze，Bearer同一服务令牌，body={requestId,conversationId,message,context:{gatewayToken},resume?}；不传Cookie、客户信息或数据库凭据。单工厂/独立数据库部署限制详见ai-agent-mvp.md，不能宣称现有ERP已支持共享库多租户。
## 2026-10-20演示数据导入

本次未增加或修改HTTP接口/DTO，不接受客户端createdAt、历史执行时间、状态或金额覆盖。历史导入仅为测试源中的显式本地维护命令，复用既有应用服务及权限身份，见[演示数据](demo-data/README.md)。普通会话、CSRF、后端权限及库存入口不变。单据备注标明模拟与批次；现有查询可搜索演示资料编码/名称，AI仍为全厂范围。

## AI评测基线（2026-10-20）

无HTTP接口、DTO或工具新增。可注入时钟仅Python构造参数，HTTP不接收评测日期/身份覆盖。固定区间、错误码与真实模型启用方式见[评测说明](ai-evaluation.md)。

## AI时间与指标澄清（2026-10-20）

HTTP路径、DTO、SSE事件和身份校验不变。日期与指标均由Agent确定性代码约束；clarification.context.question可为dates/profit/dimensions/metric/revenue，已解析范围按原startDate/endDate保存，成功context仍为日期+metric=sales。日期字符串左闭右开，UI结果文本展示Asia/Shanghai、实际本期/上期及含税已发货订单金额定义。上期零值（含0/0）显示不可比。测试固定时钟只存在于独立进程入口，不接受HTTP日期覆盖。详见[时间规则](ai-time-ranges.md)。

## AI客户/商品维度工具（2026-10-20）

内部工具新增get_customer_sales_ranking/get_product_sales_ranking（limit默认10，1～20）、resolve_sales_entity（dimension/query，最多10候选+truncated）、get_entity_sales（dimension/entityId，返回summary/trend及分单位数量）。均保留startDate/endDate、服务认证、用户/工厂/run能力和销售读权限校验。旧外部会话接口及SSE事件不变；旧工具收到维度参数拒绝400，不静默返回全厂值。新字段不得混用；ID/金额/订单/数量字符串。候选只含id/code/name/unit/enabled，模型不接收名称/ID或联系方式；名称选择由确定性分支和用户完成。完整请求/响应、来源与限制见[工具契约](ai-sales-dimensions.md)。


## AI第4阶段只读库存与缺料

新增4个只读业务工具和最小候选查询，详见[工具契约、参数及响应](ai-inventory-production.md)。当前工具拒绝历史日期；销售日期要求不变。库存读、生产+库存双读、单据读分别校验；AI会话入口为任一支持的业务读权限。会话所有权、CSRF、固定工厂、短期能力及SSE事件不变。返回查询时刻、来源、单位及明确范围，待入库不加库存、已领料不重复补料。

## 统一启动入口

本次仅新增本地运维入口，未增加HTTP接口或修改DTO、权限、业务状态机。健康检查使用既有Actuator、Agent health和Vite代理标识；模型配置就绪不代表已完成模型调用。

## AI批量回归修复（2026-10-20）

外部接口、SSE事件和工具参数不变。仓库候选在字面查询为空时支持有限别名词表，别名结果可附`matchMode=warehouseAlias`；候选仍只含id/code/name，最多10项，超限须缩小范围。不会用别名硬编码ID，多候选不会自动选择。支持仓库引号名称/编码、顺序解析仓库和物料、纠正失败查询以及库存完成后的换仓追问。上下文仅由Java持久化传递，新增operationsArgs与operation用于恢复已验证范围，不从前端或模型接受身份。详见[问题、命令与验证边界](ai-batch-testing.md)。


## AI第5阶段证据与异常解释

无新HTTP路径、业务工具或请求DTO。新增内部clarification.context.question=evidence、missingTools；既有事件名/会话归属/CSRF/短期能力及工具权限不变。成功内容增加逐条事实证据、待核实解释和关联行动；工具查询时刻/来源/实际日期等缺失则补查，无法补齐返回clarification而不是完整成功。客户集中度复用销售汇总和前4客户排行。详见[证据契约与Reflection](ai-evidence-quality.md)。


## AI第6阶段：多轮条件与澄清恢复

外部路径和发送请求不变，Send仍只有message。Detail新增过滤后的context，stage/result/clarification/error可携带同一白名单context。浏览器不得提交resume；Java按会话所有者生成内部resume及新短期授权。Context阶段即时保存条件，失败/取消保留最近条件；每次工具仍核验当前用户和权限。详见[上下文契约](ai-context.md)与[本阶段报告](ai-context-report.md)。


## 2026-10-21 AI第7阶段：页面与端到端验收

对外路径与Send.message不变。终态result/clarification/error可增加execution={toolCalls,modelCalls,elapsedMs,usageReports,usage?}，usage仅白名单非负整数prompt_tokens/completion_tokens/total_tokens，缺失为null，不接收模型随意扩展字段。Java对stage使用固定枚举与文字，过滤其他未知字段；内容和历史标题/消息用公开呈现边界隐藏内部SQL、认证与思考内容，数据来源改为业务名称。内部工具元数据原口径保持，数值/日期/身份未放宽。Vue等匹配done终态才发布报告，SSE错误不能再显示完整成功。详见[启动、能力与五分钟演示](ai-page-acceptance.md)、[实际评测结果](ai-page-report.md)。

## 2026-10-21 前端视觉与 AI 交互优化

本轮仅修改前端展示与局部交互；API 路由、请求/响应 DTO、SSE 事件及身份/权限契约均未变。历史搜索仅过滤已加载列表，输入快捷键调用原发送方法。详见 [本次界面验收](minimal-ui-report.md)。

## 2026-10-21 第二轮简约界面

第二轮仅调整展示模板/CSS；删除常驻字数显示但保留4000字限制，原接口、SSE与权限契约未变。详见 [第二轮报告](minimal-ui-chat-report.md)。

## 2026-10-23 安全与分析一致性补齐

新增当前用户GET /api/v1/ai/conversations/quota与/calls（沿用AI业务读权限，不接受任意用户或工厂ID）。返回字段见ai-quota.md；429使用AI_RATE_LIMIT / AI_DAILY_LIMIT / AI_CAPACITY。内部销售工具Query新增可选dateBasis=created_at/shipped_at，缺省保持原创建时间口径；库存工具不接受此字段。销售数值响应增加dateBasis、snapshotId、snapshotScope，保留原字段。事件execution实际返回时持久化，不要求done重复。详见 [交付与验证](hardening-report.md)、[额度](ai-quota.md)、[销售口径与快照](sales-basis-snapshot.md)。
