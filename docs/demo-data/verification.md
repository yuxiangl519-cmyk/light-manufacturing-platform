# 历史演示数据实测记录（2026-10-20）

本记录只声称本次实际运行的检查。MySQL为本项目专属13306上的8.4.7，开发mini_mfg_dev、测试mini_mfg_test；未访问参考项目或8082。

|命令/检查|实际结果|
|---|---|
|`mvnw.cmd -Dtest=BusinessTimeTest test`（实现前/后）|实现前因类缺失失败；实现后2项通过，验证显式开关、异常清理、线程隔离与SQL日期格式|
|`mvnw.cmd -Dtest=DemoPlanTest test`（实现前/后）|实现前因计划类缺失失败；实现后2项通过，106/90单数量、日期、唯一键、全程无负库存及14台终值正确|
|`mvnw.cmd -Dtest=DemoPlanTest,BusinessTimeTest test`|4项通过，导入源也已编译|
|`scripts/Import-DemoData.ps1 -Database Test`|先备份；首次创建106，125库存单/324流水；7项余额符合预期，原有行摘要未变|
|相同测试库命令再次执行|创建0，跳过106，记录及余额不变|
|`mvnw.cmd -B -P integration verify`|最终20单元+52集成通过，失败/错误/跳过0；16:21:59构建成功|
|`scripts/Import-DemoData.ps1 -Database Development`|先备份；创建106，既有数据未变，库存/流水/状态/时间检查通过|
|相同开发库命令再次执行|创建0，跳过106；保留首次结果与重复结果|
|恢复8081后`GET /actuator/health`|UP，前端5173及既有Agent8091保持可用|
|`node frontend/scripts/e2e-demo-readonly.mjs`（在frontend目录）|真实Edge 6组通过；登录operator、真实工作台/库存接口、页面搜索、详情URL和截图；pageerror为0；不执行业务动作|
|`mvnw.cmd -Dtest=DemoAnalyticsReadOnly test`，显式MFG_DEMO_ANALYTICS=true|独立测试库1项通过；调用AI实际SalesAnalyticsService，30天日趋势求和=汇总=独立SQL金额，上一周期存在|
|Python `AnalyticsEngine.analyze`读取上述真实查询结果|金额、订单数、环比和异常由Decimal计算成功；实际结果见analytics-result.json；未调用模型|

### 导入结果

[开发库首次结果](development-result.json)、[测试库首次结果](test-result.json)、[浏览器结果](browser-result.json)、[计算引擎结果](analytics-result.json)。详细单据ID清单留在忽略的`.local/demo-data/`，未发布原有行内容、密码哈希、令牌或数据库备份。

开发库新增批次与全厂在指定期间的已发货销售额实测均为479658.00元；前期批次80876.00元。测试库全厂另含既有回归测试交易，因此其AI查询汇总618916.00元/74单大于本批次479658.00元/50单；这正是保留全厂口径的结果，不是批次导入重复。

开发库首次备份：`.local/backups/mini_mfg_dev-before-demo-20261020-162258.sql`，52062字节，mysqldump退出0且完成标记存在，另保存SHA-256侧文件。重复执行前再次备份，不覆盖首次备份。未做恢复演练，也未运行恢复、删除、清库或反冲。

### 发现并解决的问题

1. 首次保护检查的表名白名单未允许数字，遇到既有stage3_test_source即在写入前停止。核对实际表名后将标识符规则改为字母开头、字母数字下划线；没有删改既有测试表。
2. 完整回归首次有1项失败：旧权限测试从角色第一页100条找ADMIN，长期累积角色后ADMIN不在第一页。改用q=ADMIN查询，保留安全断言；最终整套通过。
3. 真实页面检查最初错误假设库存查询只返回7条实际余额；现有启用资料模式会展开物料×仓库，两个演示仓库共14条组合（含零余额）。核对StockQueryService后，验收改为7条正余额及正确仓库，不修改产品库存查询规则。
4. 停服保护首次因Java参数文件的双反斜杠转义未识别项目路径，拒绝停止；核对本项目参数文件后按原格式反转义，确认classpath属于本项目才停止8081。未停止其他服务。

### 验证边界

额外隔离AI验证服务的启动操作被自动审批返回“blocked by policy”，未提供具体原因，因此本次没有重新执行DeepSeek/LangGraph/SSE端到端模型联调。已完成AI实际Java查询及Python确定性计算，不能将它们宣称为真实模型联调通过。此前AI阶段的模型验收记录仍见docs/ai-agent-verification.md，不作为本次重跑证据。

本次未改前端产品代码和依赖，未重复执行前端全量构建/单元测试；新增只读浏览器验收脚本已实际运行。没有实际硬件装机、BIOS/全套QVL验收、企业真实采购凭证或真实交易，报价资料也不保证当前可下单。

UI实图：[库存余额](balances.png)。使用正常启动脚本访问http://localhost:5173即可查看；导入前已有账号与密码未改动。待办后续由用户正常推进，推进后不保证导入初始快照仍等于当前库存。
