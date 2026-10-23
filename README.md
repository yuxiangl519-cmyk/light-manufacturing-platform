# 轻量制造管理平台

AI当前采用[模型决策→工具执行→结果回模型](docs/ai-model-loop.md)，实际验收见[改造报告](docs/ai-model-loop-report.md)。所有分析问题需要模型可用；只读权限、日期和数值仍由服务端控制。

## 推荐：一键启动

在项目根目录双击 **Start.cmd**，即可检查并启动MySQL、Redis、后端、AI和前端；就绪后自动打开http://localhost:5173。双击 **Stop.cmd** 全部关闭；**Restart.cmd** 重启应用。无需多个终端或激活虚拟环境。重复启动会复用已验证的项目进程。详细命令、日志与数据恢复说明见[统一启动说明](docs/one-click-start.md)。


面向电脑组装工厂的ERP、MES、WMS个人作品，演示成品为标准办公台式机。

**第5阶段及AI增量MVP完成：完整Vue前端与真实后端联通。** 包含登录权限、系统管理、资料/BOM、采购/生产/销售、仓储及真实工作台。

```powershell
.\scripts\Start-LocalServices.ps1
.\scripts\Start-Backend.ps1
```

后端使用localhost:8081，健康检查为[Actuator health](http://localhost:8081/actuator/health)。本机凭据由脚本从被Git忽略的私密文件导入，不在仓库内提供默认密码。

- [启动、登录请求与测试说明](docs/startup.md)
- [本阶段实际命令与验证结果](docs/stage5-verification.md)
- [已实现状态流转](docs/state-transitions.md)
- [当前进度与未开始范围](docs/progress.md)
- [需求与规则](docs/requirements.md)
- [架构与事务](docs/architecture.md)
- [数据库](docs/database.md)
- [API、DTO与权限](docs/api-contract.md)
- [中文UI设计与页面约定](docs/ui-spec.md)
- [环境实测](docs/environment.md)
- [独立环境安排](docs/local-environment-plan.md)
- [版本和官方依据](docs/versions.md)
- [参考项目只读分析](docs/reference-analysis.md)
- [完整业务演示与后续测试设计](docs/demo-and-tests.md)
- [P0设计核对](docs/design-review.md)
- [开发规范](AGENTS.md)
- [第三方来源与许可说明](THIRD_PARTY_NOTICES.md)

后端13项单元+47项真实HTTP/MySQL/Redis集成测试通过；前端类型检查、5项单元测试及生产构建通过。真实Edge页面完成电脑组装13组检查：采购20/40、生产10、报工库存0、入库10、销售3余7，包含三角色和登录过期检查。

另开终端：先cd frontend并执行npm ci，再回根目录执行scripts/Start-Frontend.ps1，访问 http://localhost:5173 。账号admin/operator/viewer，密码使用本项目本地环境变量，仓库没有默认密码。开发库不自动灌交易。

[高保真设计参考](docs/design/saas-home-reference.png) · [真实1440页面截图](docs/design/stage5-home-1440.png) · [完整验证记录与限制](docs/stage5-verification.md)。2026-10-23已完成授权安全修复，npm audit为0项；尚未进行生产部署。
## 新增：智能经营分析Agent

Java+FastAPI+LangGraph+Vue真实销售分析已接通DeepSeek；支持经营指标、等长上期比较、规则异常、利润/日期澄清、历史和实时SSE。原Java/Vue之外运行./scripts/Start-Agent.ps1，访问http://localhost:5173/ai-analysis。详情见[启动与能力边界](docs/ai-agent-mvp.md)及[真实验证记录](docs/ai-agent-verification.md)。当前单工厂部署，不宣称自由SQL或完整长期记忆；未自动灌入开发交易。

## 历史演示数据

开发库已追加电脑组装模拟经营数据（2026-08-22～10-20），真实型号与公开报价有来源，交易均标明模拟，已有数据保留。页面搜索DM261020或选择演示仓库。详见[演示数据说明](docs/demo-data/README.md)、[资料来源](docs/demo-data/sources.md)与[验收记录](docs/demo-data/verification.md)。

## 安全与分析质量补齐（2026-10-23）

已增加按用户限频/日额度与持久调用记录、按实际发货日期统计、同一销售分析快照及CI。实际结果与单实例限制见 [交付报告](docs/hardening-report.md)。
