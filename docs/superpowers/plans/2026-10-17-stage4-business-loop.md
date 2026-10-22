# 第4阶段：最简ERP/MES/WMS后端闭环

沿用已确认电脑组装场景。技术版本不变，参考项目严格只读；所有真实测试只连mini_mfg_test，不清库。

## 文件和职责

- shared/application/CommandService、shared/api/BusinessDtos、shared/security/BusinessActor：同事务成功动作记录与重放、动作响应、认证操作者。
- erp/{controller,dto,application,domain,repository}：采购和销售独立应用服务，草稿CRUD/取消、确认及收货/发货，后端金额计算。
- mes/{controller,dto,application,domain,repository}：复用工单/用料快照，增加草稿、缺料预览、领料、报工及生产入库编排；原确认服务继续负责一次确认快照。
- application/controller/ProductionReceiptController：唯一生产入库HTTP入口，调用MES公开用例，保持工单→物料→库存单→余额锁序。
- V6：成功命令记录及生产报工；V7：ERP头/明细。已执行V1～V5不修改。
- docs及Stage4*IntegrationTest：接口/数据库/状态机/实际命令和可重复电脑组装集成测试。

## 固定契约和事务

HTTP DTO禁止接收status、totalAmount、lineAmount、实际收发/报工数量；动作只收version。金额输入String，非负至多两位小数，DECIMAL(18,2)，单价明确为人民币含税单价，不计算税额/会计账，数量×单价和总额在后端计算并检查上限。每单1～100个不重复物料，采购仅原料/销售仅成品。

成功动作先锁来源头，再查CommandService.find(resourceType,id,action)；存在则忽略过时version返回当前详情及原operationId、replayed=true。否则校验版本/状态、执行全部库存与状态变更、保存唯一命令记录及成功审计，共享同一事务。CommandService.find返回Optional<String>，record返回String，均MANDATORY，resourceType/action固定字符串；record actor从BusinessActor.requireId取得。所有命令头锁先于库存。

CommandService不依赖业务模块；system.AuditService在相同事务写成功日志。ActionResponse<T>={operationId,replayed,document,relatedDocuments:List<StockDtos.Document>}。CommandService不返回实体。草稿删除仅逻辑标记，DRAFT可取消，确认后不可编辑/删/取消/反冲。

生产草稿可选已有启用bomId且必须匹配成品；确认锁定该BOM复制不可变快照。缺料查询只读，不加预留；正式领料重新以冻结快照调用库存服务。报工只创建唯一report事实和待执行入库，成品仍为0；入库入口先锁真实工单并验证库存单来源/类型/关联，执行库存并将工单COMPLETED，返回relatedProductionOrder。重复动作不重复审计或写库存。

## 实施与验证顺序

- [x] 读取设计、运行第3阶段41项基线。
- [x] 写缺失能力测试，观察失败；实现共用命令契约、V6及ERP/V7。
- [x] 实现MES草稿/确认/预览/领料/报工/入库，扩展只读库存来源查询和余额查询接口。
- [x] 独立测试库真实事务：电脑标准BOM 1/1/2/1/1/1，收原料20/20/40/20/20/20，产10、发3余7。
- [x] 覆盖重复提交/非法状态/缺料与超发/快照/整单回滚/并发/权限/CSRF/金额未知字段；不得用模拟代替真实提交。
- [x] 独立规格审查、质量审查，最终clean verify及本地启动冒烟通过。
- [x] 更新API、状态流转、数据库、进度、启动和验证说明；列未验证事项后停止，不开始前端。

分工：实施子任务负责ERP、MasterReferenceService客户/供应商公开投影及V7；主代理负责共用命令/V6、MES/WMS协调、全流程验证和文档。共享测试库不并行运行集成测试，不创建提交或PR。
