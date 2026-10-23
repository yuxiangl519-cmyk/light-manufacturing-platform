# 安全、额度与分析一致性补齐

2026-10-23实际验证，延续2026-10-22开始的工作。用户明确授权有限安全升级；未更换Java/Vue/数据库技术栈，未写开发库测试交易。

| 问题 | 处理 |
| --- | --- |
| npm 8个受影响依赖条目 | 精确升级Vue/Axios/ECharts/Vite/Vitest；独立npm audit结果0项。Vitest4仅用于修复3.x未覆盖的mocker公告，未force/overrides压告警。 |
| Git与CI | 实际已有初始提交7dbb76a、377个跟踪文件、origin/main，远程与本地初始提交一致；未重建Git。新增最小只读权限、固定Action SHA的GitHub Actions，以及私密文件/已知凭据模式检查。当前工作分支codex/security-ai-hardening。 |
| 用户额度 | MySQL持久化，默认每用户滚动60秒5次、上海自然日50次。并发接纳串行，失败/取消仍计接纳次数；状态幂等、异常重启后过期回收。当前用户可读取自己额度与最近50次调用记录。 |
| 销售时间 | 兼容默认created_at，新增shipped_at实际发货归属。用户明确口径后由执行器注入并持久化；报告与范围详情明确口径。收入/利润不支持。 |
| 跨工具一致性 | 同run/期间/口径的总体汇总、趋势、客户/商品排行在短REPEATABLE_READ事务构建bundle，后续派发从有界grant缓存读取。单对象summary/trend同一快照。仍逐次鉴权；不同快照不拼接证据。 |

## 实际验证

- `npm ci`成功；独立`npm audit --json`为0。首次独立审计遇TLS连接失败，IPv4优先后重试成功；没有关闭证书校验。直接官方bulk查询也返回0个受影响包。
- 前端typecheck、20单元测试、build通过；Vite最终构建14.94秒。原有大分包提示保留。
- Edge桌面/笔记本/手机共11组UI fixture回归通过、0浏览器运行错误；没有真实后端或模型调用。
- `mvnw -P integration verify`：36单元+76真实MySQL8.4.7/Redis7.4.2/HTTP集成测试通过，完整Maven117秒。计数取实际运行日志，未把target目录遗留XML重复计数。
- Python258项确定性测试通过；模型决策使用脚本模型替身，不称为真实模型验收。
- 额度专项验证10路并发仅接纳5次、自然日午夜/滚动时间边界、重建服务保持额度、事务回滚不计次、取消幂等及用户隔离。
- 快照专项在汇总与趋势之间真实提交另一个事务：本bundle仍为10.00，新bundle为99.00。日期口径专项区分创建55.00与实际发货37.00，并检查逐日15.00/22.00。
- 用量传输专项验证结果事件带execution而done不带统计时，仍记录模型3次、输入100/输出20；这是HTTP传输桩数据，不是实际模型用量。
- 运行中修正了旧报告定义回归、首次销售域绑定丢失dateBasis、结果事件用量漏记和测试fixture缺少created_by_name；未删减原数值断言。

结构化结果见[hardening-results.json](hardening-results.json)。评测入口Test-AiEvaluation.ps1已纳入额度/快照专项，默认真实模型关闭。

## Git与CI

CI含前端锁文件安装/审计/类型/单测/构建、Java17全单元与独立MySQL8.4.7/Redis7.4.2集成、Python3.13.5确定性测试及仓库安全检查。CI只用短命测试实例的公开测试凭据，不读取本机.local、模型密钥或开发库配置。Action固定到官方仓库当前标签解析出的提交，参考官方[checkout](https://github.com/actions/checkout)、[setup-node](https://github.com/actions/setup-node)、[setup-java](https://github.com/actions/setup-java)、[setup-python](https://github.com/actions/setup-python)说明。

私密文件检查只输出文件名与原因，不输出命中值。它覆盖常见私密目录/环境文件/私钥及已知令牌形态，不保证发现任意格式秘密。原隐私测试的合成密钥改为运行时构造，避免与真实泄露混淆，不设测试文件白名单。

远程检查结果另记本文件交付状态；配置文件存在不能宣称GitHub Actions已经执行成功。

## 使用与限制

开发实例未自动重启。下次正常重启Java会执行Flyway V9；同步重启Python以加载新协议。用`MFG_AI_QUOTA_PER_MINUTE`、`MFG_AI_QUOTA_PER_DAY`配置正整数上限；不必设置模型费用参数。

保留单工厂、单应用实例边界。额度/记录跨重启持久化，但grant、运行句柄和bundle仍在内存；过期任务标错后重新发起，不是从节点恢复。未配置LangGraph持久化Checkpointer，不宣称多租户SaaS、高可用或所有工具同一全局快照。未进行生产部署、所有依赖生态全面安全扫描或本轮真实模型验收。没有返回的token/费用不估计。

详细说明：[依赖](dependency-security-2026-10-22.md)、[额度](ai-quota.md)、[销售口径与快照](sales-basis-snapshot.md)。
