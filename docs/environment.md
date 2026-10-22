# 实际环境与第2阶段运行状态

更新：2026-10-17，Asia/Shanghai。以下为第2阶段实际检查和实施结果，替代P0的环境阻塞状态。参考项目未修改、未启动，未读取其凭据或访问业务库。

## 当前可用资源

| 项目 | 实测 | 用途/隔离 |
| --- | --- | --- |
| JDK | Temurin17.0.20.1+1，java/javac可用 | 项目进程JAVA_HOME；系统默认Java24未改 |
| Maven Wrapper | 3.3.4，实际Maven3.9.12/Java17 | 官方生成；distribution SHA-256已锁定 |
| MySQL | 独立8.4.7，127.0.0.1:13306 | 本项目便携实例，不替换已有9.6 |
| 开发数据库 | mini_mfg_dev，账号mini_mfg_dev@localhost | 账号登录、Flyway、JPA validate、本地启动通过 |
| 测试数据库 | mini_mfg_test，账号mini_mfg_test@localhost | 12项真实集成测试通过；该账号访问开发库被1044拒绝 |
| Redis开发 | 独立7.4.2，127.0.0.1:16379 | 认证、会话、故障关闭验证；light-mfg:local: |
| Redis测试 | 独立7.4.2，127.0.0.1:16380 | 认证、会话、限流及测试隔离；light-mfg:test: |
| 后端 | localhost:8081，local profile | Spring Boot3.5.16实际启动，health200 |
| Node/npm/Git | 24.13.0 / 11.6.2 / 2.51.0.windows.2 | 版本已查；本阶段不生成前端 |
| Docker | 未发现 | 可选方案未搭建，不是本阶段阻塞 |

服务响应与账号认证已分别验证：两个项目数据库各自账号SELECT VERSION/DATABASE/CURRENT_USER成功；Redis脚本通过AUTH、PING和INFO server验证7.4.2，非仅TCP探测。应用使用库级账号，不使用root；开发/测试用户无彼此库的访问权限。

## 安装与资源归属

- MySQL官方固定版本压缩包MD5与官方值一致，并锁定实测SHA-256；Redis源码SHA-256与官方redis-hashes记录一致。Maven发行包对照官方SHA-512并在Wrapper固定SHA-256。
- MySQL安装/数据/私密配置在`%LOCALAPPDATA%/LightManufacturing/stage2`，owner.json绑定当前项目路径；WSL Redis在当前用户`~/.local/share/light-manufacturing`，dev/test独立配置、数据和进程。
- 首次初始化新实例后查询同名库，确认mini_mfg_dev、mini_mfg_test均不存在，再创建；后续复用检查项目归属标记，不覆盖库、不重置账号密码。
- 仅为编译Redis安装WSL Ubuntu的gcc/make/libc6-dev，没有覆盖已有Redis安装或服务配置。
- 凭据随机生成，保存在被忽略的.local/secrets.json；Windows私密目录ACL仅当前用户，WSL配置目录700/文件600。报告不含凭据。
- 本阶段按用户新指令将原设计light_mfg命名更新为mini_mfg_dev/mini_mfg_test；Redis前缀保持已确定的light-mfg隔离命名。

## 既有资源

P0发现3306/3307为MySQL9.6、6379/6380为需认证的既有Redis、8080为已有Java应用。它们没有被停止、重配或用作本项目数据库。本项目新增13306/16379/16380/8081，未尝试猜测既有密码。

## 实测与记录

详见[stage2-verification.md](stage2-verification.md)：20项测试全通过，包含登录/失败/退出、401/403、CSRF、权限即时变化、停用、密码重置及重复初始化；另已验证独立账号数据库隔离、测试启动防误连、OpenAPI权限、Redis真实中断返回503和恢复，以及旧PID文件不能停止另一实例。

日志位于被忽略的logs或.local/logs；请求traceId与脱敏错误可关联，Logback按日/大小滚动。没有输出密码、会话标识或CSRF Token到报告。

## 第3阶段增量实测

2026-10-17沿用Java17/MySQL8.4.7/Redis7.4.2及原隔离端口，无依赖升级。开发/测试Flyway均升级到V5，18张应用表，JPA validate通过。`mvnw -B clean -P integration verify`成功：10项单元、31项真实HTTP/MySQL/Redis集成测试，失败/错误/跳过均0。

独立测试事务实际提交首次并发入库、并发扣减、多物料反向输入、来源幂等、整单不足/外层异常回滚及流水对账。stage3_test_source仅位于mini_mfg_test。开发库未灌入资料或交易；本地8081 health=UP，登录后8类新增列表均200/total=0，OpenAPI生成及退出成功。凭据未输出。

命令及范围见[stage3-verification.md](stage3-verification.md)。前端、容器与完整电脑组装应用闭环未验证，等待后续阶段。

## 第4阶段增量实测

2026-10-17沿用隔离MySQL/Redis，无新增依赖或版本升级。开发/测试库迁移至V7，24张应用表，JPA validate通过；测试账号仍只使用mini_mfg_test。最终clean verify通过13单元+44集成，失败/错误/跳过0。

完整电脑组装HTTP案例及真实并发收货/生产确认/领料/报工/入库/销售均通过；外层事务在收货成功后抛异常，来源状态、库存、命令与成功审计全部回滚。当前开发库未灌业务数据，本地8081 health=UP，采购/生产/销售及库存列表200/total=0，OpenAPI包含收货/缺料预览/报工/发货/入库路径，登录退出成功。

实际命令及未验证项见[stage4-verification.md](stage4-verification.md)。前端/工作台、浏览器演示和容器仍未实施。

## 限制与后续

本阶段运行资源已就绪，无需用户寻找既有账号。容器profile仅提供配置，容器实际启动和前端Vite浏览器联调未验证。Flyway11.7.2对MySQL8.4可能提示高于其历史测试版本，本次真实迁移与validate已通过，未擅自改变锁定版本。WSL曾提示localhost代理转发及Redis内存overcommit，未更改系统代理或sysctl配置；此主机上的连接/测试已实际通过。

启动、停止、导入环境和测试命令见[startup.md](startup.md)。不提供清库或覆盖既有服务的脚本。

## 第5阶段实测（2026-10-18）

恢复本项目依赖后，MySQL8.4.7端口13306、Redis7.4.2开发16379/测试16380通过脚本认证与归属检查。前端Node24.13.0/npm11.6.2及锁定依赖执行npm ci成功；Vue类型检查、5项单元测试及Vite生产构建成功。前端开发5173代理后端8081；浏览器测试5174仅代理隔离后端18081/test profile，不使用8082（已有其他应用占用）。未修改其他应用、系统服务配置或参考项目。

Microsoft Edge无头真实浏览器已完成电脑组装13组检查：真实资料/BOM/采购/生产/报工/入库/销售、余量7、三角色差异、详情直达/刷新、失败提示、Cookie过期；1440/1920与768宽度截图。使用mini_mfg_test，不清理或写开发库。

npm audit当前锁定版本报告8项（2中危、4高危、2严重），涉及axios/vue/vite/vitest等直接和传递依赖，未使用audit fix或擅自升级。此结果意味着本阶段功能验收不能等同于安全发布验收；版本修复须另行评估并按版本规范更新。Vite对Element Plus/ECharts两个大资源块提示>500kB，构建成功，尚未进行性能压测。详细命令与最终后端回归见stage5-verification.md。
第5阶段最终补测：后端13单元+47集成，前端5单元，浏览器13闭环+7管理组全部通过；交付时开发8081/5173保留，测试18081/5174已停止。会话过期旧CSRF清理和系统LIKE转义均有先红后绿回归。

## Windows PowerShell兼容修复（2026-10-18）

本地JSON按UTF-8无BOM保存；Windows PowerShell 5.1默认ANSI读取中文路径会破坏JSON解析。已为环境、归属和运行状态JSON读取显式指定-Encoding UTF8，并对凭据解析异常仅返回脱敏提示，避免ConvertFrom-Json原始异常打印凭据。已分别用powershell.exe和PowerShell7实际导入验证通过，未修改密码或私密文件。

## AI增量环境（2026-10-19）

Python3.13.5独立venv，FastAPI8091仅127.0.0.1；Java8081/Vue5173继续原栈，MySQL/Redis复用独立实例。开发及测试Flyway V8已执行，26表，不清库。真实DeepSeek兼容模型调用、Java业务查询、SSE与浏览器闭环通过；实际命令见ai-agent-verification.md。交付开发8081/8091/5173保留；测试18081/5174停止。开发管理员初始化密码不匹配，保留用户现有密码；操作员只读登录/AI列表/工作台/退出通过，无开发交易或AI测试会话灌入。模型Key仅被忽略私密文件及进程变量，不含于本文。
# 2026-10-20历史演示数据追加实测补充

本项目mini_mfg_dev已备份并追加获准批次DEMO-PC-20261020；mini_mfg_test先行验证，两个库重复执行均新建0张。未新建数据库、改服务配置或访问参考库。当前8081后端健康UP、8091 Agent健康接口可访问、5173前端HTTP200；没有遗留18081/8092验证服务。只读Edge页面6组及20单元+52集成测试通过。额外隔离AI模型联调服务启动被自动审批拦截，未重新声称模型端到端验证通过；已验证实际销售查询与确定性计算。备份、来源和命令详见[演示验收](demo-data/verification.md)，不在本文保存凭据。


## 2026-10-20统一入口与数据库恢复

统一入口为根目录Start.cmd、Stop.cmd、Restart.cmd。已实测Windows PowerShell5.1启动、重复复用、完整关闭和冷启动。原专属MySQL数据目录存在混杂重做日志导致无法冷启动；用户授权后保留原目录并重建专属数据目录，从最近本项目备份恢复mini_mfg_dev（27表）和mini_mfg_test（28表）及账号。原数据缺失/目录损坏的具体来源未确定，不能仅凭现象归因。现有其他MySQL3306未被停止或修改，my.ini保留。启动日志在.local/logs，恢复不包含备份之后未另存的变更。详见[说明](one-click-start.md)。

## 2026-10-20重复启动误入初始化分支修复

215414启动日志显示旧目录判定先于监听进程核验，导致已运行本项目MySQL仍报端口占用。本次调整为先核验13306可执行文件和专属my.ini并复用，再验证登录；仅未运行时以auto.cnf/mysql.ibd判断已初始化。旧标记存在或目录非空时禁止再次初始化。具体目录检测为何在两次调用间不同尚未确定，不能据此断言数据库丢失。此次未停止MySQL、未恢复/清理数据，Start.cmd实际复用启动通过。新增Test-MySqlStartup.ps1五项回归，加原启动辅助九项全部通过。
# 2026-10-20 AI批量回归环境补充

本轮使用现有MySQL13306中的mini_mfg_test和测试Redis16380；隔离后端18081（test配置）、Agent8092（回调18081），与开发8081/8091隔离。真实模型配置从本项目忽略私密文件导入，未记录凭据。实际运行Test-AiBatch.ps1，35/35通过；Python140、Java单元26及AI相关真实MySQL集成19通过。完整命令、证据、故障验证范围见docs/ai-batch-testing.md。

修复后实际执行Start-Project.ps1 -Restart，开发后端8081、Agent8091、前端5173健康检查通过；复用原有MySQL/Redis，未重建或恢复数据库。测试进程在验证完成后关闭，开发服务保留。本轮未重新执行浏览器UI验收。

