# 开发规范

- 项目：轻量制造管理平台；场景只用电脑组装工厂，演示成品“标准办公台式机”。需求以 `docs/requirements.md` 为准。
- 第5阶段已完成：实现Vue完整前端并连接真实后端，补齐真实工作台聚合，进行浏览器闭环验收及设计图交付。不扩展项目/财务等未确认业务。
- 参考目录 `C:/Users/Yoga 7i/Desktop/github/SPMS-Server` 严格只读：不修改、不运行、不访问其数据库；不读取或复制其凭据、连接配置、日志、settings.xml。具体代码复用须保留适用许可证和来源；当前无代码复用。
- 独立命名：`io.github.lightmanufacturing`，数据库 `mini_mfg_dev`，测试库 `mini_mfg_test`，Redis 前缀 `light-mfg:local:` / `light-mfg:test:`；不得连接参考项目业务库。
- 模块化单体，后端 `backend/`，前端 `frontend/`，文档 `docs/`，后续可选容器文件 `infra/`。版本锁定见 `docs/versions.md`，禁止 latest、SNAPSHOT、动态版本及擅自升级。
- 后端模块 `system/master/erp/mes/wms/workbench/ai`；跨模块只调用公开应用服务，不访问对方 Repository；实体不直接作为 API DTO。事务编排位于 `application`。
- 所有写接口后端鉴权和校验；会话 + CSRF；默认拒绝。数量 Java BigDecimal/SQL DECIMAL(12,0)，正整数输入；金额 DECIMAL(18,2)/HALF_UP，拒绝超过两位小数的输入。JSON 数量、金额和 ID 使用字符串。
- MySQL 是库存唯一事实来源。禁止库存 CRUD、负库存、分批动作、确认后编辑/取消/反冲。记账必须整单事务、来源唯一、防重复、按固定顺序加锁；报工不加库存。
- Flyway 管理结构，JPA `ddl-auto=validate`、`open-in-view=false`。不得使用自动建表、create-drop、clean 或启动时自动灌入演示交易。
- 不输出或提交密码、会话、令牌；凭据只从本项目环境变量或用户明确指定的私密文件读取。不得覆盖既有服务配置；建库、清库须另获授权。
- 每次改动同步 API、数据库、UI 和测试文档；禁止宣称未运行的检查通过。测试只能使用明确批准的独立测试资源。

## 阶段验收与未来命令

实际用户阶段与验收见 `docs/progress.md`；P0～P5是早期里程碑编号。第2阶段基础认证、第3阶段资料/BOM/库存、第4阶段后端业务闭环均已完成；第5阶段前端、工作台与真实浏览器验收已完成。

后端Wrapper和前端锁文件均已提供，按下列命令验证。先从根目录导入 `. ./scripts/Import-LocalEnvironment.ps1`；后端默认本地端口8081。

```powershell
cd backend
.\mvnw.cmd -v
.\mvnw.cmd test
.\mvnw.cmd -P integration verify
cd ../frontend
npm ci
npm run typecheck
npm run test:unit -- --run
npm run build
npm run test:e2e # 需先启动隔离18081/5174并设置测试保护变量，推荐根目录scripts/Test-Frontend.ps1
```

集成测试前必须核对数据库名为 `mini_mfg_test`、Redis 前缀为 `light-mfg:test:`。至少验证整条电脑组装案例、整单回滚、并发扣减、重复请求、权限和 CSRF；没有真实 MySQL 8 验证不得声称库存一致性已通过。

## AI增量规范（2026-10-19）

用户已授权并完成AI经营分析MVP。新增backend的ai模块与agent/ Python服务；详见docs/ai-agent-mvp.md。保持只读业务查询、SQL预审核模板、Java服务端固定工厂/用户归属、内部服务认证+短期能力，禁止自由SQL及从模型接受身份。Java/Vue不升级既定依赖，Python按requirements.lock.txt安装。只有mini_mfg_test可写测试数据，开发库不灌AI测试会话或业务交易。AI密钥与服务令牌不得输出，.local/ai-secrets.json和venv/output均忽略。图实际运行、确定性计算、澄清/模型失败不得假成功；共享库多租户/完整Checkpoint/长期自动记忆尚未实现。后端16单元+52集成、Python21、前端10及真实浏览器4组已验证。

## 2026-10-20已授权演示数据

用户明确批准向当前开发库追加DEMO-PC-20261020模拟经营数据，保留已有数据；详见docs/demo-data/README.md。仅此批次允许本地显式导入，先备份、先在mini_mfg_test验证，禁止自动启动灌数/直接拼库存/修改已有流水。真实型号与公开报价须有来源，模拟交易与定价必须标明。BusinessTime历史时钟仅供本地显式进程线程作用域，HTTP不得提供历史时间开关；其余测试仍只用测试库。
