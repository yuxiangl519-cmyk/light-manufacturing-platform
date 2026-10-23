# AI频率、每日额度与调用记录

默认同工厂同用户滚动60秒最多5次，Asia/Shanghai自然日最多50次。`mfg.ai.quota.per-minute`、`mfg.ai.quota.per-day` 可配置为正整数；不是每分钟整点清零。V9增加 `ai_quota_owner` 行锁与 `ai_call` 持久调用记录，所有实例使用相同MySQL；重启不清额度。接纳事务以当前读计数，避免MySQL REPEATABLE READ旧快照导致并发超额。

HTTP每次使用当前用户及业务读权限，工厂和用户由Java固定。无归属、会话忙、历史容量不足、授权创建失败、服务并发容量不足均在接纳前拒绝，不扣额；接纳后失败、澄清、超时、断线或主动取消仍计一次，不返还。写消息、接纳记录、会话RUNNING状态在同一事务。额度不足为429 `AI_DAILY_LIMIT`，频率超限为429 `AI_RATE_LIMIT`。

`GET /api/v1/ai/conversations/quota` 返回当前用户的 `day/timezone/dailyLimit/dailyUsed/dailyRemaining/perMinuteLimit/recentUsed/resetsAt`。`GET /api/v1/ai/conversations/calls` 返回当前用户最近50条调用，不接受用户或工厂参数。调用包含runId、conversationId（字符串）、status、startedAt、finishedAt、durationMs、toolCalls、modelCalls、inputTokens、outputTokens。工具次数由Java派发尝试计数（缓存命中、工具失败及库存参数拒绝也可计入，不是成功次数）；模型次数与token数仅采集经过字段过滤的Agent事件中的 `execution`（结果/错误事件也可能携带，done不必重复），缺失保持NULL；断线前未返回模型用量不能推断，未提供完整计费价格故不编造cost。

终态更新仅作用于RUNNING，同一运行重复done/取消/错误不会改写终态。超时、断线和停止均记录终态。每60秒清理超过210秒仍RUNNING的遗留会话（包括异常进程退出后的任务），保留原有扣额与记录；旧任务不会覆盖后续运行。记录不保存密钥、能力令牌或模型原始响应。

测试：`AiQuotaTest` 覆盖上海跨日和配置；`AiQuotaIntegrationTest` 必须在已批准mini_mfg_test运行，验证10路并发只接纳5次、滚动时间边界、午夜恢复、服务重建保留额度、事务回滚、幂等取消、真实用量/缺失用量及用户隔离。传输与权限测试使用独立高额度配置，限额由本测试专项验证。未运行真实MySQL测试不得宣称并发限额已验证。

2026-10-23已在mini_mfg_test实际运行专项3项及相关HTTP权限/传输测试。新增前端对429的固定说明，区分每日额度、频率与公共容量，不自动重发问题。默认5/60秒、50/日的配置单测通过。
