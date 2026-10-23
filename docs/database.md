# 数据库设计

## 2026-10-21 模型决策循环

无DDL、Flyway或业务交易变更。仍用ai_conversation.context_json版本1、最多4096字节，保存模型提出且经过代码白名单核验的必要澄清条件。新增可选retryRequest为最多400字符、脱敏的最近失败问题，仅供显式重试，新问题和成功后删除；不保存整段聊天、候选列表、工具结果或授权。历史所有权与每次工具鉴权保持。详见[上下文与执行边界](ai-model-loop.md)。

## 约定

目标MySQL8.4.7/InnoDB，库mini_mfg_dev、独立测试库mini_mfg_test；utf8mb4，普通文本utf8mb4_0900_ai_ci，编码/用户名/权限标识使用utf8mb4_bin。Flyway唯一管理业务结构。第4阶段共24张应用表：系统6、资料4、BOM2、ERP4、MES3、WMS4、成功命令1；另有Flyway历史表。环境脚本只管专用库和账号，不建业务表。

下文 `ID`=BIGINT 正数自增主键；`Q`=DECIMAL(12,0)；`M`=DECIMAL(18,2)；`T`=DATETIME(3) UTC；`Vn`=VARCHAR(n)；`?`=可空，其余列 NOT NULL。枚举均 VARCHAR + CHECK，不用序号或 MySQL ENUM。所有外键默认 ON DELETE RESTRICT/ON UPDATE RESTRICT，无跨聚合级联删除。

除特别列明外，主表/明细表共有 `id ID`。下列可变表另共有审计列 `created_at T, updated_at T, created_by BIGINT? → sys_user, updated_by BIGINT? → sys_user, version BIGINT DEFAULT 0`：sys_user、sys_role、material、customer、supplier、warehouse、bom、purchase_order、sales_order、production_order、stock_document。系统初始化操作者可空，其余业务操作必须有用户。

所有 Q 输入在应用校验整数，数据库 Q 列不负责阻止 MySQL 的小数自动舍入；必须在写库前拒绝。正数量 CHECK >0，余额/阈值 CHECK >=0，单价/金额 CHECK >=0；version>=0。总金额和用料乘法先精确计算再检查字段上限。

## 系统（6 表）

| 表 | 专有字段 | 键与索引 |
| --- | --- | --- |
| sys_user | username V50；password_hash V100；display_name V80；enabled BOOLEAN DEFAULT true；auth_version BIGINT DEFAULT 0 | UQ(username)；IX(enabled)；不删除，停用代替 |
| sys_role | code V40；name V80；description V255；builtin BOOLEAN DEFAULT false | UQ(code)；内置 ADMIN/OPERATOR/VIEWER 不删除、不改 code |
| sys_permission | code V80 PK（本表无 id）；name V80；module V30；sort_order INT | 迁移写入，固定代码清单；无通用审计列 |
| sys_user_role | user_id BIGINT → sys_user；role_id BIGINT → sys_role（无 id） | PK(user_id,role_id)；IX(role_id,user_id) |
| sys_role_permission | role_id BIGINT → sys_role；permission_code V80 → sys_permission.code（无 id） | PK(role_id,permission_code)；IX(permission_code,role_id) |
| operation_log | actor_id BIGINT? → sys_user；actor_name V80；module V30；action V40；resource_type V30?；resource_id BIGINT?；resource_no V64?；success BOOLEAN；error_code V64?；summary V500；trace_id V64；client_ip V64；occurred_at T | IX(occurred_at,id)；IX(actor_id,occurred_at)；IX(resource_type,resource_id)；无通用审计列，不可改删 |

operation_log 的 resource_id 是异类审计引用，不能建立单一 FK；资源可能为已逻辑删除草稿，故同时保存编号。actor_name 为当时显示名；未认证失败 actor_id 可空，不能存密码、Cookie、token、完整请求体。

## 基础资料（4 表）

| 表 | 专有字段 | 约束 |
| --- | --- | --- |
| material | code V50；name V120；type V20；unit V10；specification V255；min_stock Q DEFAULT 0；enabled BOOLEAN；referenced BOOLEAN DEFAULT false | UQ(code)；CHECK type∈RAW_MATERIAL/FINISHED_GOOD；IX(type,enabled,id) |
| customer | code V50；name V120；contact_name V80；phone V40；address V255；enabled BOOLEAN DEFAULT true | UQ(code)；IX(enabled,id) |
| supplier | code V50；name V120；contact_name V80；phone V40；address V255；enabled BOOLEAN DEFAULT true | UQ(code)；IX(enabled,id) |
| warehouse | code V50；name V120；address V255；enabled BOOLEAN DEFAULT true | UQ(code)；IX(enabled,id) |

文本字段允许空字符串时仍 NOT NULL，名称/编码禁止空白。基础资料不提供删除。编码长度统一50，业务单号长度64，避免接口与字段长度不一致。物料type始终不可改；referenced只能由公开资料引用服务置true，受引用后code/unit不可改。其他三类资料code建立后不可改。

## BOM（2 表）

| 表 | 专有字段 | 约束 |
| --- | --- | --- |
| bom | code V50；product_material_id BIGINT → material；name V120；revision INT DEFAULT 1；enabled BOOLEAN；enabled_product_id BIGINT生成列：启用时product_material_id，否则NULL | UQ(code)；UQ(enabled_product_id)；IX(product_material_id)；revision>0；只允许成品作为 product |
| bom_item | bom_id BIGINT → bom；line_no INT；material_id BIGINT → material；quantity_per Q | UQ(bom_id,material_id)；UQ(bom_id,line_no)；quantity_per>0；line_no>0 |

组件必须原料，不允许自身引用/成品组件，应用验证。BOM 整单替换明细时锁头并递增 revision/version，禁止并发确认读到混合版本。BOM 无硬删除；停用后不允许新工单确认。初始六种原料用量见 requirements。历史工单只读自身快照，不关联当前 BOM 明细。

## ERP（4表，V7已实现）

采购/销售头公共专有字段：`order_no V64, partner_name V120, warehouse_id BIGINT→warehouse, warehouse_name V120, created_by_name V80, status V24 DEFAULT DRAFT, total_amount M, remark V500 DEFAULT '', confirmed_at T?, cancelled_at T?, deleted_at T?`。created_by/updated_by非空→sys_user。单号UQ，全局不复用；IX(status,created_at,id)、IX(warehouse_id,created_at,id)。草稿逻辑删除后详情404，审计保留；CANCELLED不是删除。partner_name映射DTO的supplierName或customerName，不与主资料联动改名。

| 表 | 其余字段 | 约束/索引 |
| --- | --- | --- |
| purchase_order | supplier_id BIGINT → supplier；received_at T? | status∈DRAFT/CONFIRMED/RECEIVED/CANCELLED；IX(supplier_id,created_at,id) |
| purchase_order_item | purchase_order_id BIGINT → purchase_order；line_no INT；material_id BIGINT → material；material_code V50；material_name V120；unit V10；quantity Q；unit_price M；line_amount M | UQ(purchase_order_id,material_id)；UQ(purchase_order_id,line_no)；IX(material_id) |
| sales_order | customer_id BIGINT → customer；shipped_at T? | status∈DRAFT/CONFIRMED/SHIPPED/CANCELLED；IX(customer_id,created_at,id) |
| sales_order_item | sales_order_id BIGINT → sales_order；line_no INT；material_id BIGINT → material；material_code V50；material_name V120；unit V10；quantity Q；unit_price M；line_amount M | UQ(sales_order_id,material_id)；UQ(sales_order_id,line_no)；IX(material_id) |

明细 Q>0/M>=0，不接受客户端提供 line_amount/total_amount。每头1～100行、类型限制、行金额公式、头金额汇总、状态和时间对应由应用在同事务验证。确认时覆盖并冻结资料显示快照。不可在确认后编辑明细。草稿更新可删除并重建草稿明细，后续无对外稳定明细 ID 依赖。

## MES（3表，工单/快照V3，报工V6）

| 表 | 专有字段 | 约束/索引 |
| --- | --- | --- |
| production_order | order_no V64；product_material_id BIGINT → material；product_code V50；product_name V120；unit V10；planned_quantity Q；bom_id BIGINT → bom；bom_code V50；bom_revision INT?；issue_warehouse_id BIGINT → warehouse；issue_warehouse_name V120；receipt_warehouse_id BIGINT → warehouse；receipt_warehouse_name V120；status V24；remark V500；confirmed_at T?；issued_at T?；reported_at T?；completed_at T?；cancelled_at T?；deleted_at T? | UQ(order_no)；IX(status,created_at,id)；IX(product_material_id,created_at)；IX(issue_warehouse_id)；IX(receipt_warehouse_id)；Q>0 |
| production_material_snapshot | production_order_id BIGINT → production_order；line_no INT；material_id BIGINT → material；material_code V50；material_name V120；unit V10；quantity_per Q；required_quantity Q | UQ(production_order_id,material_id)；UQ(production_order_id,line_no)；两数量>0；确认后不可变 |
| production_report | production_order_id BIGINT → production_order；reported_quantity Q；reported_by BIGINT → sys_user；reported_by_name V80；reported_at T | UQ(production_order_id)；Q>0；reported_quantity必须等于工单planned_quantity；应用不可变实体 |

工单状态：DRAFT/READY_TO_ISSUE/IN_PRODUCTION/PENDING_INBOUND/COMPLETED/CANCELLED。bom_revision 草稿为空，确认后>0且快照1～100行；required_quantity=quantity_per×planned_quantity。工单不维护已领数量、分批报工量或在制余额。报工表独立保存事实，入库由 stock_document 表关联，不在工单再存重复的 receipt_id。

## WMS（4 表）

### stock_document

字段：`document_no V64, kind V30, direction V3, status V12, warehouse_id BIGINT→warehouse, warehouse_name V120, source_type V30, source_id BIGINT, source_no V64, fingerprint CHAR(64), executed_at T?, executed_by BIGINT?→sys_user, executed_by_name V80?`，另有通用审计列（库存单created_by/updated_by非空）。编号为ST-UUID，不使用时间序列号。

| kind | direction | source_type | 提交后可出现状态 |
| --- | --- | --- | --- |
| PURCHASE_RECEIPT | IN | PURCHASE_ORDER | EXECUTED |
| PRODUCTION_ISSUE | OUT | PRODUCTION_ORDER | EXECUTED |
| PRODUCTION_RECEIPT | IN | PRODUCTION_ORDER | PENDING/EXECUTED |
| SALES_SHIPMENT | OUT | SALES_ORDER | EXECUTED |

CHECK强制kind、direction、source_type匹配、source_id>0；PENDING时执行人/姓名/时间都空，EXECUTED时都非空。UQ(document_no)、UQ(kind,source_type,source_id)；IX(created_at,id)、IX(warehouse_id,status,kind,id)。同工单最多一张领料出库、一张生产入库。其余类型记账事务中短暂创建PENDING，成功提交前转EXECUTED；仅生产入库允许单独准备PENDING。

来源为逻辑多态引用，不能声明单一ERP外键；第4阶段采购/销售/工单应用服务在同事务锁真实来源头、校验状态及冻结明细，再调用库存服务。fingerprint为规范化不可变记账内容SHA-256，重复同来源同内容返回原单，不同内容409 SOURCE_CONFLICT。来源存在性与合法动作由公开业务用例负责，库存没有任意来源写HTTP。

### stock_document_item

字段：`stock_document_id BIGINT→stock_document, line_no INT, source_line_id BIGINT, material_id BIGINT→material, material_code V50, material_name V120, unit V10, quantity Q`。

UQ(stock_document_id,material_id)、UQ(stock_document_id,line_no)、UQ(stock_document_id,source_line_id)，Q>0、source_line_id>0、line_no>0。来源行材料和冻结数量由调用方业务服务校验；库存服务检查类型/数量/重复并保存快照，只在创建时生成，之后不可修改。PENDING生产入库有完整明细，没有余额变动或流水。外键所需material_id索引由MySQL建立。

### stock_balance

字段：`warehouse_id BIGINT→warehouse, material_id BIGINT→material, quantity Q DEFAULT 0, version BIGINT DEFAULT 0, updated_at T`（无通用审计列）。

UQ(warehouse_id,material_id)，IX(material_id,warehouse_id)，CHECK quantity>=0/version>=0。只由 WMS 记账服务修改，不公开增删改接口。余额行按需在事务内创建，零余额行保留，避免先删后建的并发问题。

### stock_ledger

字段：`balance_id BIGINT→stock_balance, stock_document_id BIGINT→stock_document, stock_document_item_id BIGINT→stock_document_item, warehouse_id BIGINT→warehouse, material_id BIGINT→material, source_type V30, source_id BIGINT, source_no V64, source_line_id BIGINT, kind V30, change_quantity Q, before_quantity Q, after_quantity Q, occurred_at T, operator_id BIGINT→sys_user, operator_name V80`（无通用审计列）。

UQ(stock_document_item_id)保证每库存单行只记一次账；IX(warehouse_id,material_id,id)、IX(occurred_at,id)、IX(balance_id,id)、IX(source_type,source_id,source_line_id)。CHECK change_quantity<>0，before_quantity>=0，after_quantity>=0，after_quantity=before_quantity+change_quantity。正负号与出入方向、余额维度与单据维度由同一应用服务保证并测试。

流水保留来源类型/头ID/行ID/编号及操作人姓名的只读投影，统一库存服务从锁定头与不可变明细写入；查询展示物料/仓库快照。流水不提供改删接口。库存对账：每维度余额=该维度全部change_quantity之和（从0起）；SUM用更高精度避免聚合溢出。

## 命令去重（1表，V6已实现）

`command_record`：`id ID, resource_type V30, resource_id BIGINT, action V20, actor_id BIGINT→sys_user, committed_at T, trace_id V64`。

UQ(resource_type,resource_id,action)，IX(committed_at)。resource_type 为 PURCHASE_ORDER/PRODUCTION_ORDER/SALES_ORDER/STOCK_DOCUMENT；action 为 CONFIRM/RECEIVE/ISSUE/REPORT/EXECUTE/SHIP/CANCEL。resource_id 为逻辑多态引用，应用校验对应资源存在，无虚假 FK 声明。仅记录成功事务；与业务一起提交。返回 operationId 即此表 id。删除仅限从未执行成功动作的草稿；命令记录不清理或复用。

## 迁移顺序与约束验证

共24张应用表（另有Flyway历史表）；以V1～V7为实际创建顺序，ERP并不作为库存表创建前提，逻辑来源由应用校验。系统用户自关联审计FK建表后补充；管理员密码由本地环境变量输入并由幂等bootstrap生成哈希，不写进迁移或仓库。

已实施V1系统/权限、V2资料/BOM、V3工单/快照、V4库存核心、V5编码排序及索引、V6报工/成功命令、V7采购销售头/明细。已执行迁移不改写；不灌入业务交易。Flyway clean禁用，ddl-auto=validate。stage3_test_source仅由第3阶段集成测试在mini_mfg_test创建，不是应用业务表。

验收数据库 CHECK/唯一键真实生效、索引覆盖分页/查询、库存来源链完整、外键阻止孤儿；在独立 MySQL8 中并发首次入库/出库验证按需建余额、整单锁定与回滚。不执行参考项目的测试或 SQL。

## 第5阶段

本阶段无新表及结构迁移，继续使用Flyway V1～V7和JPA validate。工作台为既有业务表的只读聚合投影；库存仍只通过库存应用服务写入。测试新建资料和单据只在mini_mfg_test，使用唯一编码，不清库。

## V8：AI会话与消息

Flyway V8__ai_conversations.sql只增加两张表，不修改销售、库存结构，不自动灌交易，不清库。ai_conversation：id BIGINT主键、tenant_key VARCHAR(64)二进制比较、user_id FK sys_user、title VARCHAR(160)、status VARCHAR(24)检查约束、context_json TEXT可空、run_id VARCHAR(64)可空、created_at/updated_at DATETIME(3)。索引(tenant_key,user_id,updated_at,id)，列表/详情强制工厂与用户双条件。context只存澄清/日期等最小数据，不存能力令牌或Key。

ai_message：id BIGINT主键、conversation_id FK ai_conversation、role user/assistant检查约束、content TEXT、created_at DATETIME(3)，索引(conversation_id,id)。会话启动在FOR UPDATE下检查运行状态并同事务保存用户消息；终态在run_id匹配且仍RUNNING时保存助手消息和状态，避免旧任务覆盖新任务或取消结果。模型等待不持有数据库长事务。

业务表仍为单工厂独立数据源，没有tenant_id列，不适用于共享业务库多工厂。AI会话本身使用tenant_key隔离，并测试其他工厂会话不可读取。开发/测试分别由现有Flyway执行V8至26张应用表；禁止手工clean/create-drop。
## 2026-10-20历史演示数据

无需结构迁移，Flyway仍为V8；未添加表或改写旧迁移。追加批次DEMO-PC-20261020使用独立DM261020-资料编码及两个演示仓库，单据备注保存批次与稳定键。历史时间在JPA/JDBC首次生成时由BusinessTime线程作用域提供；流水及日志不事后改写，SQL时间仅格式化受控Instant。实际导入时间及单据ID保存在忽略的本地报告。原有行摘要前后相等；开发库备份留在.local/backups，不自动恢复或清理。数量/金额/来源唯一性及事务规则保持不变。详见[数据说明](demo-data/README.md)。

## AI评测基线（2026-10-20）

无结构/Flyway变更，不清库、不写开发库评测数据。独立工具基准使用mini_mfg_test明细查询；新增销售夹具事务回滚，HTTP测试保留测试会话与随机无权限用户。见[评测说明](ai-evaluation.md)。

## AI时间与指标澄清（2026-10-20）

无Flyway或表结构变更。既有context_json保存metric/revenue澄清类型及已确定日期，不增加身份来源。业务查询仍由Java按Asia/Shanghai日界执行；新增历史夏令时夹具仅写mini_mfg_test并事务回滚。开发库不灌评测数据、不清库，详见[时间规则](ai-time-ranges.md)。

## AI客户/商品维度（2026-10-20）

无Flyway或表结构变更。复用sales_order与sales_order_item确认快照、SHIPPED/未删除/created_at范围，以及现有索引和同单同物料唯一约束。单头与明细金额分别用于客户/商品，订单去重，数量按unit分组；不修改资料启停规则、历史名称或库存。新增测试只写mini_mfg_test，销售夹具回滚；HTTP验收只追加测试会话及同名停用客户资料，不清库/灌开发库交易。详见[实际结构及查询规则](ai-sales-dimensions.md)。


## AI第4阶段只读库存与缺料

无表结构或Flyway变更，不新增预留、采购计划或历史库存表。公开应用服务复用stock_balance、material.min_stock、production_material_snapshot及PENDING成品stock_document/item。多个工单只读同一当前余额，不产生预留；待入库不记账。测试仅mini_mfg_test，库存夹具走InventoryService、事务回滚；HTTP分权测试追加唯一命名角色/用户/会话，不清库。详见[规则与验证](ai-inventory-production.md)。

## 统一启动的数据库保护

启动入口校验独立实例与现有库/账号登录，旧初始化标记不能替代实际校验；不会自动恢复缺失库。2026-10-20用户单独授权后从最近项目备份恢复原缺失的两库；冷启动损坏目录完整保留并重新初始化专属数据目录，未改业务结构或迁移版本。Flyway正常validate/migrate，未执行clean或自动演示灌数。恢复及备份边界见docs/one-click-start.md。

AI批量回归修复未改表结构、索引或迁移。上下文沿用ai_conversation.context_json保存操作和已解析范围。真实评测仅向mini_mfg_test追加会话；库存/销售数值用独立只读SQL核对，开发库不写测试数据。详见docs/ai-batch-testing.md。


## AI第5阶段证据质量

无Flyway、表、索引或预留规则变更。客户集中度分母为同期间SHIPPED未删除销售单头总额；库存/缺料沿用原公开服务与确认快照。跨工具60秒读取时差不等于共同事务快照。新增固定2405年销售夹具与资料仅在mini_mfg_test事务中回滚，无库存余额/流水直写，不灌开发库。详见[验证说明](ai-evidence-quality.md)。


## AI第6阶段：多轮条件与澄清恢复

复用ai_conversation.context_json及ai_message，无DDL/Flyway变更。context版本1、UTF-8最多4096字节，只有允许的查询条件与待澄清状态；不存工具结果/候选列表/票据。最近会话50条、历史最多200条消息，容量不足则新建；租户/用户隔离与run_id条件更新避免旧执行覆盖新任务。详见[上下文契约](ai-context.md)与[本阶段报告](ai-context-report.md)。


## 2026-10-21 AI第7阶段：页面与端到端验收

本阶段无DDL、Flyway或交易写接口变更，仍使用现有会话/消息/context_json。执行统计只在本轮事件及验收报告提供，不新增数据库字段；刷新历史时明确未保存统计，不推算历史token或成本。历史敏感内容通过读取呈现过滤，不清库或改写旧记录。详见[启动、能力与五分钟演示](ai-page-acceptance.md)、[实际评测结果](ai-page-report.md)。

## 2026-10-21 前端视觉与 AI 交互优化

本轮为前端主题与 AI 对话布局调整，无数据库结构变更、无 Flyway 迁移、无新增持久化字段。会话搜索和折叠状态仅保存在页面内存。未写开发库。详见 [本次界面验收](minimal-ui-report.md)。

## 2026-10-21 第二轮简约界面

第二轮简化页面，无结构或持久化变更、无迁移及业务数据写入。详见 [第二轮报告](minimal-ui-chat-report.md)。

## 2026-10-23 安全与分析一致性补齐

Flyway V9新增ai_quota_owner与ai_call，按工厂/用户串行接纳，索引支持每日与滚动60秒计数；持久运行状态/耗时/Java派发尝试/实际返回的模型次数和token。缺失用量为NULL，不保存认证票据或模型原始响应。销售复用既有shipped_at，无财务字段；只在mini_mfg_test验证迁移，开发库随下次正常后端启动执行Flyway。详见 [交付与验证](hardening-report.md)、[额度](ai-quota.md)、[销售口径与快照](sales-basis-snapshot.md)。
