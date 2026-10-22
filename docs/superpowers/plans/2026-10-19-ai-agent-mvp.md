# 智能经营分析MVP（2026-10-19）

确认目标：在当前轻量制造平台新增AI分析，不使用jshERP。Java负责会话/权限、固定单工厂上下文、只读参数化销售查询、MySQL会话消息与SSE流代理。Python FastAPI以真实LangGraph StateGraph实现规划/执行/确定性分析/反思/报告/澄清，Vue复用布局和权限。禁止自由SQL；execute_safe_query仅预审核模板。单工厂安装，不改造整个ERP为多租户；拒绝外部tenant身份，按工厂+用户隔离AI记录。

接口：POST /api/v1/ai/conversations -> {id,title,status}; GET同路径 -> 数组；GET /{id} -> {id,title,status,messages:[{id,role,content,createdAt}],pendingQuestion?}; POST /{id}/messages {message} SSE事件 stage/result/clarification/error/done，data为JSON；POST /{id}/cancel。页面权限复用erp:sales:read。

Java->Python POST /v1/analyze，Bearer共享服务令牌，body {requestId,conversationId,message,context:{gatewayToken},resume:{...}?}。Python不接收Cookie/用户自报身份。Python->Java POST /internal/ai/tools/{name}，Bearer共享令牌 + X-AI-Context一次任务短期能力令牌，body工具参数。服务令牌缺失功能关闭，不影响现有ERP。

Python返回同类SSE；result={content,context?}，clarification={content,context:{...}}；stage={node,message}; error={code,message}; done={status}。context仅结构化澄清字段，不是完整企业Checkpoint。

实施：Java安全/聚合/存储/代理；Python图/工具/确定性指标/受限模板/记忆接口；Vue对话/进度/历史/澄清/取消。验证各自测试、编译、启动，最后隔离测试库真实链路。无模型Key明确未联调，绝不模拟为真实LLM。

完成：Java16单元+52集成、Python21、前端10及类型/构建通过；真实DeepSeek链路4组、浏览器4组、模型/ERP失败2组通过。V8已迁移开发/测试，开发8081/8091/5173运行，测试端口已停止。详见docs/ai-agent-verification.md。
