# 设计一致性核对

核对日期：2026-10-17。本文件仅记录设计核对，不代表代码、数据库或运行测试验收。用户确认电脑组装场景，本阶段停止在文档。

## 规范源与交叉映射

| 核对项 | 规范源 | 对照文件 | 结论 |
| --- | --- | --- | --- |
| 产品/范围 | requirements | README、UI、demo-and-tests | 标准办公台式机，六种原料；不采用旧场景 |
| 状态流转 | requirements状态表 | database枚举、api动作、ui状态标签 | 采购3主状态、工单5主状态、销售3主状态，另有草稿取消终态 |
| 采购收货 | requirements receive | architecture事务、数据库来源、API、验收表 | 同动作全量入库并生成已执行记录，无二次采购入库 |
| 领料 | 工单确认快照 | 快照表、issue接口、UI预览、B05/B07 | 按冻结BOM扣原料，不维护车间在制品 |
| 报工 | report不增库存 | report表、PENDING库存单、DTO、F03 | 计划量报工一次，只生成待执行入库单 |
| 整机入库 | execute | 工单先锁、生产入库、UI链接、验收表 | 同事务库存增加且工单完成，唯一执行入口 |
| 销售发货 | ship | sales/stock/ledger、动作权限、B04 | 全量扣成品，库存不足整单失败 |
| 数量与金额 | requirements精度 | SQL类型、DTO字符串、UI、B09/B10 | 正整数输入/零余额允许；金额两位，后端精确计算 |
| 字段 | database列 | api camelCase DTO、ui表格 | 明确warehouseId/materialId、快照、version、可空时间；不直接序列化实体 |
| 来源唯一 | stock_document/ledger/command | architecture幂等、B02/B03/B13 | 同单同动作成功一次，每库存单明细记账一次 |
| 事务与锁 | architecture | database唯一/CHECK、B04/B05/B06/B12 | MySQL本地事务、固定锁顺序、整单回滚；Redis不管理库存 |
| 权限 | API固定点表 | requirements角色、UI入口、S01～S07 | 34点；后端鉴权、CSRF；默认三角色并可分配固定点 |
| 资料修订 | requirements快照/停用 | database、api、ui | 新确认检查启用，已确认按冻结内容执行，历史名称不漂移 |
| 工作台 | requirements统计口径 | api响应、ui日期标签 | 金额按createdAt区间，完成量按completedAt，当前待办/状态/预警不受日期影响 |
| 版本与环境 | versions | environment、progress | 默认Java24但可进程切17；MySQL9.6不当作8验收；Redis认证未完成 |
| 数据与断言 | demo-and-tests | requirements六步、来源表、命令表 | 六类采购、10台生产、3台发货；最终成品7、流水14、成功动作8 |

字段映射规则：SQL下划线→API驼峰；quantity_per→quantityPer、required_quantity→requiredQuantity、planned_quantity→plannedQuantity、total_amount→totalAmount、change_quantity→changeQuantity。sourceType/sourceId从三类业务外键推导，不要求数据库存在同名通用列。UI路由和API路径不同是有意分离。

## 已处理的歧义

- 场景冲突由用户明确选择电脑组装解决，早期需求文件改为单一规范入口。
- 草稿取消保留CANCELLED终态；草稿删除逻辑隐藏；确认后无取消、反冲和修改。
- “固定BOM”指初版固定单层产品配置；允许受控修订后续用量，确认工单始终保留自己的快照，不提供选配或多层。
- 数量不靠SQL的DECIMAL(12,0)自动舍入校验；API先拒绝小数，库存/阈值独立允许0。
- 登录“服务有响应”与“账号有权限”分开报告，不把NOAUTH或MySQL握手当成认证成功。
- 3307/6380也占用，Compose仅保留13306/16379候选，未执行部署。
- 成功动作重试先查已提交命令，再比较旧version；保证提交成功但响应丢失后能安全重试。
- 文档自检发现的权限数量、演示动作计数及库存停用过滤说明已统一。

## 交付边界与遗留

本阶段交付AGENTS和全部指定设计文档，另补版本依据、参考分析、演示测试与本核对记录。文件存在、相对链接和文档内容搜索可做静态检查；业务行为仍须按测试矩阵在真实独立环境验证。

本次已执行Node只读文档检查，退出码0：共14份Markdown；8项指定交付均存在；相对链接无失效；目录无非Markdown文件；权限展开计数34；三份核心规格的状态/库存类型枚举齐全；演示计算为原料剩余[10,10,20,10,10,10]、采购47000、销售9000、成品7、流水14、成功动作8。未发现TODO/TBD占位。此检查不验证实际业务实现。

环境遗留：独立MySQL8实例及账号/权限；Redis认证、服务端版本和命名空间授权；获准建库。此处无数据库写入，不能把这些标为已完成。

下一阶段入口为progress的P1。需用户新的开发阶段指令后才生成业务工程，不自动开始实现。
