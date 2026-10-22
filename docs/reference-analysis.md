# SPMS-Server 只读参考分析

范围：`C:/Users/Yoga 7i/Desktop/github/SPMS-Server`，HEAD `23a4b0b9570cc79c8a9090563f60817f2165dce8`。仅静态阅读pom、LICENSE及限定Java文件；未运行Maven/测试/应用/SQL，未读取application配置、settings.xml、运行日志或凭据。参考项目自身AGENTS约束其工作树，本次没有在那里开发。

## 排序结论

| 排名 | 结论 | 置信度 | 依据/边界 |
| --- | --- | --- | --- |
| 1 | 业务模块划分和来源单据关联可借鉴 | 高 | module/channel、mes、wms、asset分包；采购服务生成关联purchase的入库单 |
| 2 | 不搬用依赖与通用单据框架 | 高 | AirPower父工程及curd/file/mqtt/open/email/websocket、InfluxDB超过目标栈 |
| 3 | 并发去重与库存锁需专门设计验证 | 高（设计启示） | 基类有锁/状态守卫，库存服务调用updateWithLock；不代表整体并发安全已验证 |
| 4 | 数量、流程、库存维度须独立设计 | 高 | double数量、明细完成钩子、审核/暂停状态、仓库与生产单元库存并存，与本项目不同 |

## 静态证据

路径相对参考根，行号为读取版本：

- `pom.xml:5`父工程cn.hamm:airpower:9.0.0；`:17`起依赖AirPower扩展。新项目不继承POM，不复制settings.xml。
- `LICENSE:1`为MIT License，`:3`为Copyright (c) 2022 Hamm。
- `src/main/java/cn/hamm/spms/module/channel/purchase/PurchaseService.java:68`起根据完成明细创建关联采购入库；`:72`、`:96`用double统计金额。
- `src/main/java/cn/hamm/spms/base/bill/AbstractBaseBillService.java:70`事务帮助器内读取锁定单据；`:80`起状态守卫和下游钩子。
- `src/main/java/cn/hamm/spms/module/wms/input/InputService.java:90`起按明细增加或创建库存，用Double。
- `src/main/java/cn/hamm/spms/module/wms/output/OutputService.java:78`起扣库存并回写销售/领料明细。
- `src/main/java/cn/hamm/spms/module/wms/inventory/InventoryService.java:159`、`:170`增减调用锁定更新；`InventoryRepository.java`分别按仓库和生产单元查询。
- `src/main/java/cn/hamm/spms/module/mes/order/enums/OrderStatus.java`包含审核、驳回、准备、生产、入库、完成和暂停。
- `src/main/java/cn/hamm/spms/module/mes/bom/BomEntity.java`将BOM明细建为JPA关联；新项目独立bom_item外键和工单快照。
- `src/test/java/cn/hamm/spms/ConcurrencyLockVerifyTest.java:24`起描述并发锁验证目标；`:35`起为本地profile的SpringBootTest，因此本次仅读不运行。

## 推论与未知

推论：显式采购/销售/工单应用服务比复用其通用审核钩子更符合简化范围；统一BigDecimal避免迁入浮点表示。库存只按仓库+物料，领料直接消耗，不迁入车间库存。

未知：参考应用运行行为、父框架实际Spring Boot版本、业务库结构、测试通过情况、未阅读父框架内部锁实现。静态代码/注释不等于运行结论，不据此断定实际缺陷或安全性。

## 许可

本阶段只借鉴概念，无具体代码/配置/资源复制，不将参考许可证冒用为新项目整体许可证。后续如复用实质代码，须在THIRD_PARTY_NOTICES写明仓库/commit/文件范围，保留MIT完整许可及Copyright (c) 2022 Hamm，并核实被复用代码及依赖的适用许可。新项目自身许可证由作者另定。
