# 一键启动、关闭和重启

在项目根目录双击：

|入口|作用|
|---|---|
|Start.cmd|依次检查MySQL/Redis、启动Java、AI和Vue，全部健康后打开浏览器|
|Stop.cmd|关闭本项目三个应用及专属MySQL/Redis，保留数据库和演示数据|
|Restart.cmd|重启三个应用，复用并检查MySQL/Redis，打开浏览器|

无需激活Python虚拟环境，无需打开三个终端，也不需要手动导入环境变量。应用在后台运行；关闭启动窗口不会关闭应用。首次启动可能需要Maven编译，启动入口会等待健康检查，不要重复点击。

PowerShell等效命令：

```powershell
.\scripts\Start-Project.ps1
.\scripts\Start-Project.ps1 -Restart
.\scripts\Stop-Project.ps1
.\scripts\Stop-Project.ps1 -IncludeDependencies
```

脚本默认不打开浏览器，加`-OpenBrowser`可打开。所有命令从项目根目录执行；根目录cmd入口自动定位目录。Windows PowerShell 5.1和PowerShell 7均可使用；cmd入口固定调用系统Windows PowerShell。

## 行为与保护

- MySQL13306、Redis16379/16380、后端8081、AI8091、前端5173。不会接触参考项目8082。
- 已运行的应用先验证可执行程序、项目路径、启动命令，再验证健康状态；重复启动复用原进程。其他程序占用端口时明确失败，不抢占、不自动换端口、不全局结束Java/Node/Python。
- Java健康需UP；AI需服务认证与模型配置已加载（不是一次实际模型调用）；前端需HTTP200且代理目标为本项目8081。
- 并发启动/关闭命令通过项目本地文件锁互斥。启动失败保留已启动的服务和日志，修复后再执行即可，不自动清理数据。
- 启动日志位于被Git忽略的`.local/logs/launcher-*`。主窗口只显示进度与日志路径，WSL代理提示保存在依赖服务日志中；不修改用户代理设置。
- 只允许当前已经完成初始化的项目环境；缺少依赖或私密配置时给出提示，不自动下载软件或安装动态版本。
- 初始化脚本现在分别检查数据库是否存在及应用账号能否登录，不再只凭root登录成功和旧标记宣称就绪。旧标记存在但库缺失时拒绝自动重建，需用户另行授权恢复。
- 关闭前完成正在进行的业务/AI操作。应用关闭按核实后的PID执行，未提交事务由数据库回滚；MySQL使用mysqladmin正常关闭、Redis发送TERM，数据目录保留。
- 原来的Start-Backend/Agent/Frontend及Start/Stop-LocalServices脚本保留，方便单模块开发。

## 本次环境修复

2026-10-20检查发现本项目专属MySQL实例内两个业务库及账号缺失，但旧provision-state标记仍在。无法仅凭这些证据确定数据缺失的原因。

用户明确授权后，从以下最近备份分别恢复当前缺失的库并重建对应账号，没有覆盖其他库：

- mini_mfg_dev-before-demo-20261020-162351.sql，恢复27张表（含Flyway表）。
- mini_mfg_test-before-demo-20261020-161816.sql，恢复28张表（含测试辅助表和Flyway表）。

完整关闭后重新启动时，原数据目录出现InnoDB重做日志来自不同目录的错误，无法启动。用户已授权备份恢复范围内，原目录完整保存在专属runtime的mysql-data-preserved-*目录；通过原生目录重命名保留后重新初始化mysql-data，再恢复上述备份及账号。没有删除单个重做日志，没有修改my.ini，也没有接触其他实例。无法仅凭这些现象确定损坏原因。

恢复不可能包含备份之后未另存的变更；原备份保留。凭据仍只读取本项目私密文件，未发布或写入本说明。此恢复是用户单独授权的一次操作，统一启动入口不自动执行恢复。

## 验证

验证脚本：`powershell.exe -NoProfile -File scripts/Test-ProjectLauncher.ps1`。包含其他项目进程拒绝、合法进程识别、PowerShell5字节数组JSON健康响应等检查。

实际启动、复用、关闭和冷启动结果见docs/progress.md的统一启动条目。启动检查不是模型推理测试，也不是完整后端业务回归。

实测：9项辅助回归通过；重复启动三个应用PID不变；全停后六端口释放；完整冷启动全部健康；Redis错误PID保护通过。修复过程还覆盖PowerShell5原生命令stderr处理及WSL中文路径编码。未重跑业务全量测试、前端构建或真实模型推理，本次只改变启动运维脚本。

最终复核：根目录Start.cmd已通过cmd.exe实际执行，健康后自动打开浏览器；-Restart应用重启成功；恢复后106张演示单据及成品14台核对通过；真实Edge只读6组通过。还修复了PowerShell7经cmd启动Windows PowerShell时继承PSModulePath导致Get-Acl模块加载失败的问题：cmd子进程清除继承值、统一入口为Windows PowerShell子进程使用系统模块路径，均不修改系统持久配置。

## 2026-10-20重复启动误入初始化分支修复

215414启动日志显示旧目录判定先于监听进程核验，导致已运行本项目MySQL仍报端口占用。本次调整为先核验13306可执行文件和专属my.ini并复用，再验证登录；仅未运行时以auto.cnf/mysql.ibd判断已初始化。旧标记存在或目录非空时禁止再次初始化。具体目录检测为何在两次调用间不同尚未确定，不能据此断言数据库丢失。此次未停止MySQL、未恢复/清理数据，Start.cmd实际复用启动通过。新增Test-MySqlStartup.ps1五项回归，加原启动辅助九项全部通过。
