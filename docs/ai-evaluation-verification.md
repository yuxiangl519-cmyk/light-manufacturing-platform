# AI评测第1阶段验证记录

日期：2026-10-20。当前项目增量，不重建、不升级依赖、不增加业务工具。命令与运行边界见[评测说明](ai-evaluation.md)；简洁汇总见[评测报告](ai-evaluation-report.md)，逐项状态、耗时、失败原因和三轮真实模型记录见[结果JSON](ai-evaluation-results.json)。

| 实际验证 | 结果 |
|---|---|
| `Test-AiEvaluation.ps1 -Live` | 显式运行三层；第二轮整套入口退出0 |
| 最终 `python -m pytest agent/tests -q --junitxml=output/ai-evaluation/unit.xml` | 39通过，1.06秒；保留1条Starlette/AnyIO弃用警告 |
| `mvnw.cmd -B -P integration -Dit.test=SalesAnalyticsIntegrationTest,AiIntegrationTest verify` | 20单元+6真实MySQL8.4.7定向集成；失败/错误/跳过0，BUILD SUCCESS，30.351秒 |
| 最终 `python -m evaluation.live`（显式测试保护变量） | 真实模型层12通过；逐项总耗时70.040秒，无跳过/失败 |
| `python -m evaluation.report --root <本项目根> --live` | 汇总本次XML及live.json，退出0；不复制原始请求/响应到公开报告 |
| `python -m pytest agent/tests/test_evaluation_runner.py -q` | 报告生成/脱敏、旧live结果隔离、独立明细oracle与收集错误识别4项通过 |
| `python -m pip check` / `python -m compileall -q agent/app agent/evaluation` | 无依赖冲突；编译通过 |
| 本次审阅源码/文档匹配已知本地凭据值 | 0匹配；不输出凭据内容，非完整安全审计 |

确定性基线先通过失败测试确认时钟注入缺失（TypeError），再新增构造参数与时钟测试；报告空JUnit检测亦先失败后修复。新增Java明细oracle首次遇到JDBC返回LocalDateTime而非Timestamp，修正测试转换后重新通过，没有改动业务查询。

真实模型使用本项目私密配置中的deepseek-v4-flash别名，服务端权重未锁定。第一轮11通过、1失败：日期澄清续问终态不匹配；读取测试库保存的安全错误提示为“模型调用失败”，供应商侧原因尚未确认。补充安全错误码诊断后第二轮12通过（42.328秒）；最后增强源数据前后逐日一致性核对，再执行最终真实模型12项通过（70.040秒）。没有隐藏第一次失败、自动重试直到成功或修改生产故障行为；一次全通过不保证模型服务永远稳定。

真实模型层的12项包含HTTP前置澄清/拒绝/取消，前置分支无需模型调用；销售成功及澄清续问确实经过Java→FastAPI→LangGraph→外部真实模型→Java工具→mini_mfg_test。金额、订单、增长率、区间、来源/CNY、日均及方向与独立明细SQL+Python计算比较，逐日工具数据由Java独立明细分组核对。模型失败和工具失败两项为Agent真实网络探针，JSON scope单独标记，未当作Java转发故障验收。Java SSE集成上游是测试桩，不是模型。

未验证：完整后端其余集成/库存并发回归、前端构建与浏览器回归（本次无业务/UI改动）、Java层真实故障注入、生产TLS/容量/跨实例、供应商故障根因、共享库多租户或长期记忆。没有据本次定向测试重新宣称库存一致性验收通过。

未清库，未写开发库评测会话或交易；销售夹具事务回滚，HTTP测试留下测试会话及随机无权限用户。未运行参考项目。最终停止本次额外启动的测试后端18081和Agent18091，原开发服务与数据库/Redis保留。无Git提交或推送。
