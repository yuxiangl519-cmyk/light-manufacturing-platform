# 本地启动与阶段验证

## 推荐：一键启动

在项目根目录双击 **Start.cmd**，即可检查并启动MySQL、Redis、后端、AI和前端；就绪后自动打开http://localhost:5173。双击 **Stop.cmd** 全部关闭；**Restart.cmd** 重启应用。无需多个终端或激活虚拟环境。重复启动会复用已验证的项目进程。详细命令、日志与数据恢复说明见[统一启动说明](one-click-start.md)。


第5阶段提供Vue真实前端、权限导航和工作台聚合；后端包含认证/系统、资料/BOM、采购/生产/销售完整最简流程、WMS查询及成品入库执行API。主机使用独立MySQL8.4.7/Redis7.4.2，应用在localhost:8081实际启动。

## 本机启动

在项目根目录使用PowerShell7：

```powershell
# 启动/复用本项目专用服务，不替换已有3306/3307、6379/6380
.\scripts\Start-LocalServices.ps1

# 前台启动后端，Ctrl+C停止应用
.\scripts\Start-Backend.ps1
```

健康检查：[http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)，正常仅返回`{"status":"UP"}`。Swagger UI为[http://localhost:8081/swagger-ui/index.html](http://localhost:8081/swagger-ui/index.html)，仅local配置开放，并需登录及`system:api-docs:read`权限；匿名不能直接浏览。

脚本默认JDK路径为本机已验证的Temurin17安装；其他机器可为Start-Backend/Test-Backend传入`-JavaHome '实际JDK17路径'`，不改变系统默认Java。

## 首次准备（其他机器或尚未安装时）

需要Windows、PowerShell7、WSL Ubuntu、JDK17。此项目不依赖Docker。WSL编译工具缺失时先安装gcc/make/libc6-dev（只安装构建工具，不重配现有Redis）：

```powershell
wsl -d Ubuntu -u root -- sh -c 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gcc make libc6-dev'
.\scripts\Prepare-LocalEnvironment.ps1
```

Prepare下载固定官方MySQL/Redis包，核对SHA-256，Redis在用户自己的WSL目录编译；随后初始化本项目MySQL实例与开发/测试库，并启动两个独立Redis。使用13306/16379/16380，若端口归属不符或发现无归属标记的同名库，脚本拒绝继续，不删除或覆盖。

MySQL运行目录为`%LOCALAPPDATA%/LightManufacturing/stage2`，带项目路径归属标记；Redis位于WSL用户的`~/.local/share/light-manufacturing`，dev/test各自目录。MySQL与Redis不是替换系统服务，也未设置新的开机自启动。项目移动路径后归属校验会拒绝复用，需要先核对资源，不应手改标记绕过检查。

## 凭据与演示账号

首次准备随机生成密码，存入`.local/secrets.json`，该目录和MySQL私密运行目录已限制为当前Windows用户访问，且被Git忽略。不要将文件内容、环境变量整体、Cookie或CSRF Token贴到聊天和日志。

`.env.example`只有变量名，Spring不会自动读取该文件。可自行配置环境变量，或在本机使用：

```powershell
. .\scripts\Import-LocalEnvironment.ps1
```

导入仅对当前进程及其后续子进程生效，不修改系统环境。`admin`、`operator`、`viewer`密码分别来自`MFG_ADMIN_PASSWORD`、`MFG_OPERATOR_PASSWORD`、`MFG_VIEWER_PASSWORD`。首次启动在内存中生成BCrypt哈希写库；初始化只创建缺失账号，重启不会覆盖密码、角色分配或人工授权。更改已有密码应调用当前用户改密或管理员重置接口，修改初始化环境变量不会重置已有账号。

独立数据库`mini_mfg_dev`和`mini_mfg_test`，各自账号只能访问自己的库。当前轻量本地实例为方便Flyway使用库级DDL/DML权限，无全局管理权限，测试账号已实际验证不能访问开发库；不使用root运行应用。容器部署可进一步分离迁移/运行账号，本阶段未搭建容器。

## 会话与CSRF请求示例

统一使用localhost，不混用127.0.0.1与localhost的Cookie。所有写请求（登录、退出也包括）需要CSRF；登录会轮换会话ID并清理旧token，登录后重新获取。

```powershell
. .\scripts\Import-LocalEnvironment.ps1
$base='http://localhost:8081/api/v1'
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf=Invoke-RestMethod "$base/auth/csrf" -WebSession $session
$body=@{username='admin';password=$env:MFG_ADMIN_PASSWORD}|ConvertTo-Json -Compress
$null=Invoke-RestMethod "$base/auth/login" -Method Post -ContentType application/json -Body $body -Headers @{'X-CSRF-TOKEN'=$csrf.token} -WebSession $session
$csrf=Invoke-RestMethod "$base/auth/csrf" -WebSession $session
Invoke-RestMethod "$base/auth/me" -WebSession $session
Invoke-RestMethod "$base/auth/permissions" -WebSession $session
$null=Invoke-RestMethod "$base/auth/logout" -Method Post -Headers @{'X-CSRF-TOKEN'=$csrf.token} -WebSession $session
```

Cookie为LIGHT_MFG_SESSION、HttpOnly、SameSite=Lax、path=/，空闲30分钟失效；本机HTTP不设Secure，HTTPS部署设置`MFG_COOKIE_SECURE=true`。不使用JWT/localStorage。用户停用、角色/权限调整下一请求重新查库生效；改密与重置密码使旧authVersion会话失效。

前端阶段使用Vite同源代理：`server.proxy['/api']={target:'http://127.0.0.1:8081',changeOrigin:false}`，浏览器只访问前端同源`/api`。Axios保留Cookie，写请求携带GET /auth/csrf返回的X-CSRF-TOKEN。该代理已在frontend/vite.config.ts实现，前端始终请求相对路径，不直接跨域访问后端。

## 验证

```powershell
# 13项单元测试 + 47项真实HTTP/MySQL/Redis集成测试
.\scripts\Test-Backend.ps1

# 应用运行时，主动中断本项目开发Redis，验证503并在finally恢复
.\scripts\Verify-RedisFailure.ps1
```

等价构建命令（先导入项目环境）：`cd backend; .\mvnw.cmd -B clean -P integration verify`。若正在运行target下JAR，应先停止该应用，否则Windows会锁定JAR导致clean失败。测试仅连接mini_mfg_test、独立Redis16380和light-mfg:test:前缀；测试在启动数据源/Flyway前拒绝开发库地址。集成测试不清库，创建带唯一前缀的专用测试用户/角色；不会修改演示账号或开发数据。

测试报告在backend/target/surefire-reports与failsafe-reports；本次实测见[stage5-verification.md](stage5-verification.md)。测试新增唯一编码资料/BOM及业务单据，不清空测试库；真实HTTP并发由服务端独立事务提交，另有显式外层事务异常回滚验证。应用错误带traceId，不记录密码/令牌/原始SQL错误；成功操作日志同事务。

## 业务接口入口

登录后可读写`/api/v1/materials`、`/customers`、`/suppliers`、`/warehouses`、`/boms`（基址均/api/v1），写接口需要对应权限与CSRF。资料启停使用完整PUT并提交最新version；无DELETE。DTO见[api-contract.md](api-contract.md)。

业务头路径为/api/v1/purchase-orders、/production-orders、/sales-orders，POST创建草稿，PUT修改，GET详情/分页，DELETE逻辑删除草稿；动作POST仅传version，详见[API契约](api-contract.md)及[状态流转](state-transitions.md)。

工单GET /{id}/material-requirements只读预览，不能替代正式issue；report后查看stockDocuments中PENDING生产入库，唯一执行入口POST /api/v1/stock-documents/{id}/execute，随后工单完成。采购收货/生产领料/销售发货不另提供第二个库存执行接口。库存余额/流水无CRUD。

电脑验收在Stage4BusinessIntegrationTest中可重复执行，测试记录保留于mini_mfg_test并每次使用新ID；不要把测试指向开发库。开发库仍不自动灌交易或设置余额。前端真实浏览器验收使用隔离测试环境，步骤见下文。

## 停止依赖

先Ctrl+C停止前台后端，再执行：

```powershell
.\scripts\Stop-LocalServices.ps1
```

只停止归属于本项目的实例，保留数据和私密配置；不提供清库脚本。测试故障脚本会短暂停开发Redis，请勿在业务演示提交操作时运行。

## 前端启动与完整页面

另开终端，在项目根目录执行：

```powershell
cd frontend
npm ci
cd ..
./scripts/Start-Frontend.ps1
```

访问 http://localhost:5173 ，账号admin/operator/viewer，密码从本项目本地环境变量提供，仓库不含默认明文密码。不要把密码写入命令参数或聊天。后端8081使用mini_mfg_dev；本地开发库没有自动灌入演示交易，首次进入工作台可正常显示0和空态。

前端检查：

```powershell
cd frontend
npm run typecheck
npm run test:unit -- --run
npm run build
```

浏览器验收必须使用独立测试端口（先构建后端并启动本项目依赖），分别在三个终端执行：

```powershell
./scripts/Start-E2eBackend.ps1
./scripts/Start-Frontend.ps1 -Port 5174 -BackendTarget http://127.0.0.1:18081
./scripts/Test-Frontend.ps1
```

测试后端18081强制test profile和mini_mfg_test；测试前端5174带可验证代理目标，测试脚本核验进程归属与代理后才开始写入。实际用Microsoft Edge无头浏览器，所有业务创建/确认/库存操作经页面；不安装或启动参考项目，不使用8082。测试唯一前缀资料/单据保留于测试库，不清库。截图和JSON报告位于output/playwright（Git忽略），公开选择的截图见docs/design。Ctrl+C分别停止对应前台进程。
## AI经营分析Agent

在原Java/Vue终端外增加第三个Agent终端：执行./scripts/Start-Agent.ps1（Python8091）。首次安装用py -3.13 -m venv agent/.venv以及agent/.venv/Scripts/python.exe -m pip install -r agent/requirements.lock.txt。Start-Backend自动导入本项目AI私密配置并执行Flyway V8，原账号密码不覆盖。访问http://localhost:5173/ai-analysis，需要销售查看权限。完整变量、隔离测试和限制见[ai-agent-mvp.md](ai-agent-mvp.md)；实测见[ai-agent-verification.md](ai-agent-verification.md)。

## 2026-10-20已授权历史演示数据

当前开发库已追加2026-08-22～10-20电脑组装模拟单据，最近30天为09-21～10-20。直接启动后在资料/库存搜索DM261020，选择演示仓库即可查看。无需再次初始化；继续推进待办后不要重跑导入来恢复状态。首次/重复导入和备份说明见[演示数据](demo-data/README.md)，来源见[资料来源](demo-data/sources.md)。导入工具默认仅测试库，开发库须显式-Database Development；不随应用启动运行。
