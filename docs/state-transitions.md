# 后端状态流转（第4阶段已实现）

所有路径基址/api/v1。动作POST仅接收`{version:number}`；写入需固定动作权限及CSRF。成功后重放原operationId，返回当前详情，replayed=true；即使版本已经变化，也不会重复副作用。没有成功记录时必须比较版本和合法状态。

| 来源 | 动作/路径后缀 | 转换 | 库存及事务副作用 |
| --- | --- | --- | --- |
| purchase-orders | confirm | DRAFT→CONFIRMED | 启用资料/金额及显示快照冻结，无库存预留 |
| purchase-orders | receive | CONFIRMED→RECEIVED | 全明细原料入库，EXECUTED采购入库单和流水 |
| production-orders | confirm | DRAFT→READY_TO_ISSUE | 启用BOM与物料检查，复制不可变单耗/需求快照 |
| production-orders | issue | READY_TO_ISSUE→IN_PRODUCTION | 按冻结需求全量扣料，EXECUTED生产领料出库单和流水 |
| production-orders | report | IN_PRODUCTION→PENDING_INBOUND | 报工量固定计划量，唯一报工事实及PENDING生产入库，成品库存不变 |
| stock-documents | execute | PENDING→EXECUTED | 仅生产入库；同事务成品增加、真实工单PENDING_INBOUND→COMPLETED |
| sales-orders | confirm | DRAFT→CONFIRMED | 启用客户/成品/仓库及金额快照冻结，无库存预留 |
| sales-orders | ship | CONFIRMED→SHIPPED | 全明细成品扣减，EXECUTED销售出库单和流水 |
| 三类业务头 | cancel | DRAFT→CANCELLED | 无库存副作用，终态；取消可重放 |

草稿可PUT完整替换业务字段或DELETE逻辑删除（详情404、审计保留）；已确认及取消态不可编辑/删除。没有通用status更新接口，确认后不提供取消、删除、反冲或分批操作。库存余额/流水不可CRUD。

来源头行锁→查唯一成功命令→版本/状态→固定顺序物料/余额锁→全部检查→库存/来源/流水/出入库单/命令/成功审计同事务提交。任一异常全回滚；失败动作不留下成功命令，可以补足条件后重试。Redis只负责会话/登录限流。

电脑组装验收：CPU/主板/SSD/电源/机箱各20、内存40入库；工单10台按单耗1/1/2/1/1/1领料后五类各10、内存20；报工整机0，执行入库10，发货3后7。实际HTTP/MySQL测试记录见stage4-verification.md；未开始前端。
