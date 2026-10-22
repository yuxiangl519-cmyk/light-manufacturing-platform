# AI经营分析MVP验证记录

日期：2026-10-19。当前轻量制造平台增量，不是jshERP。全部业务测试在独立`mini_mfg_test`及测试Redis中进行，不清库。测试销售记录是既有ERP验收数据，不能当作实际工厂经营业绩；页面和Agent始终实时查询数据库，没有静态报告或假成功。

## 实际命令与结果

| 命令/操作 | 实际结果 |
| --- | --- |
| scripts/Start-LocalServices.ps1 | 项目MySQL8.4.7/13306、Redis7.4.2/16379与16380归属/认证通过 |
| 导入环境后 backend/mvnw.cmd -B test | 首次16单元通过；无新增Maven依赖 |
| backend/mvnw.cmd -B -P integration verify | 最终16单元+52集成，失败/错误/跳过0；22:01:39 BUILD SUCCESS |
| agent/.venv/Scripts/python.exe -m pytest agent/tests -q | 21通过；1条Starlette/AnyIO第三方弃用警告 |
| 同一Python -m pip check / -m compileall -q agent/app | 无依赖冲突；编译检查通过 |
| frontend/npm run typecheck | 通过 |
| frontend/npm run test:unit -- --run | 10通过（既有5+AI5），含分片UTF-8 SSE、坏帧/限长、HTML安全文本与表格 |
| frontend/npm run build | 通过；既有ElementPlus/ECharts大资源块警告保留 |
| scripts/Test-Ai.ps1 | 4组真实Java→FastAPI→LangGraph→DeepSeek→ERP检查通过 |
| scripts/Test-Frontend.ps1 -Ai | 4组真实Microsoft Edge页面检查通过，无pageerror |
| 临时无效模型配置+live_failure_smoke.py | 真实模型API错误经完整链路返回MODEL_REQUEST_FAILED，会话ERROR，无伪造报告 |
| 临时不可达ERP回调地址+live_failure_smoke.py | 真实工具连接失败返回ERP_TOOL_FAILED，会话ERROR，无伪造报告 |
| scripts/Start-Backend/Start-Agent/Start-Frontend | 开发8081/8091/5173启动成功；Java UP、Agent ok且模型已配置、AI页面HTML200 |
| 开发操作员登录→只读AI列表/工作台→退出 | 通过；该用户AI历史0，已发货金额0.00；未写入开发AI会话或交易 |

隔离Agent用`Start-Agent.ps1 -Test`指向18081，浏览器5174代理同一后端。故障注入完成后已恢复正常模型/地址。交付切为开发8081，并停止测试18081/5174；没有运行/修改参考项目或连接其数据库。

本地详细日志位于`.local/logs/ai-*`（忽略）；Maven XML位于backend/target；原始截图/JSON位于output/ai（忽略）；公开副本为[真实页面截图](design/ai-analysis-live.png)、[真实链路结果](design/ai-live-results.json)、[浏览器结果](design/ai-browser-results.json)。不保存Cookie、CSRF、模型Key或能力令牌。

## 验证内容与证据边界

Java新增3项销售查询单元测试、2项真实MySQL销售聚合集成测试、3项AI集成测试。原认证/业务/库存回归继续执行。聚合验证SHIPPED/删除排除、创建时间UTC边界、等长上期、366天上限和缺日补零。SQL超时配置为每语句5秒，单元测试校验PreparedStatement配置；未做大数据慢SQL压测。

AI集成验证匿名401、无销售权限403、其他用户/其他工厂会话404、CSRF、伪造tenant/sql字段400、服务认证、能力伪造/撤销、模板白名单和调用次数上限。SSE传输测试使用**明确的本机上游测试桩**验证首帧早于完整响应、取消持久化，不将这个桩称为真实模型。

Python单元测试使用测试专用FakeModel/FakeTools驱动真正的StateGraph，验证规划/执行/分析/反思路由、模板TextToSQL分支、累计调用限制、日期/利润/维度澄清及恢复、空数据、数值核对、安全请求结构和模型缺失；不把单元测试替身当作真实联调。

真实联调使用本机私密配置中的DeepSeek Key和`deepseek-v4-flash`别名。SSE实际经过Planner、Executor、Analyzer、Reflection、Reporter；报告金额与同区间ERP工作台已发货金额完全一致，报告保存于MySQL。利润问题先Clarification并持久化，用户确认后真实模型继续分析。1990年空区间返回无订单/0值，不生成利润或原因。TextToSQL分支已通过真实图单元测试，真实模型的具体工具选择由其受控计划决定，不宣称每次都会选择该分支。

浏览器实际操作登录、打开AI菜单、发送问题、看进度/安全Markdown表格、刷新并打开历史、利润澄清续问及取消真实模型任务，取消状态保留。报告截图为测试库真实查询结果；测试新增数据后金额可能变化，断言每次重新查询口径，未硬编码业绩数字。

故障注入：仅临时切换本项目隔离Python服务，分别使用不存在模型名和已核对未监听的本机ERP地址，验证错误安全返回。Key未打印，失败不进入Reporter。开发管理员初始化环境密码已不匹配当前账号，未覆盖或重置；改用仍有效的操作员做只读启动冒烟，用户继续使用自己设置的密码。

## 修复与审查

针对性静态审查发现的断流RUNNING残留、无换行响应内存限制、启动授权失败残留和终态不一致已修复：run结束条件化更新、断流关闭HTTP、逐字符限长、网关失败落ERROR、done状态由Java规范化。HTTP/1.1避免本机Uvicorn无意义升级告警。用户消息/会话标题重复造成一次浏览器定位歧义，已限定对话区域并完整重跑通过。

## 尚未验证/实现

共享库多租户未实现，部署边界为单工厂独立数据库；多实例取消/能力共享、完整持久化Checkpoint、长期自动记忆、自由SQL、利润成本/客户商品维度、库存工具未实现。未验证Docker/生产TLS/容量/跨浏览器全面兼容/第三方AI依赖完整安全审计；旧前端npm审计8项漏洞仍需后续处理。本次不是生产安全发布验收。

运行步骤、模型配置变量、迁移说明和演示问题见[ai-agent-mvp.md](ai-agent-mvp.md)。没有执行Git提交或推送。

最终增加问题入模前手机号/邮箱/Key格式的有限脱敏，新增两项测试；Python最终21项通过，pip check与compileall通过。已知本地密码/模型Key/服务令牌对Git可见文本扫描无匹配，私密配置、venv和原始输出均被忽略。

2026-10-20：新增固定三层评测，运行与证据边界见[ai-evaluation.md](ai-evaluation.md)及[本次结果](ai-evaluation-verification.md)。本页2026-10-19结果保留为历史，不作为新基线运行证据。
