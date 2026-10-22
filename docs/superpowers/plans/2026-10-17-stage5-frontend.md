# 第5阶段：真实前端与工作台

已确认设计：白/浅灰/低饱和蓝SaaS布局，224px导航、64px工具栏、24px留白、适中圆角和细边框。用户给出的通用企业首页是视觉方向；实际菜单/进度/待办对应电脑组装ERP/MES/WMS，不增加项目协作或财务核算业务。

## 实施文件与边界

- frontend/package.json及package-lock：严格采用docs/versions.md精确版本，npm ci可重现。Vue3/TS/Vite/Router/Pinia/Axios/ElementPlus/ECharts，Vitest检查和真实浏览器脚本。
- src/api/http.ts、stores/auth.ts、router/index.ts、layouts/AppLayout.vue：同源/api代理、Cookie、CSRF、401/403/错误、路由及权限刷新；不持久化密码或Cookie。
- views：工作台、资料/BOM、系统用户/角色/日志、业务列表/编辑/详情、WMS查询及入库；所有数据来自API。数量和金额以字符串保存，不用浮点计算结算。
- backend/workbench：从MySQL读聚合，按后端有效权限过滤金额/数量/待办及近7天序列，空库零值；不汇总不同物料单位的库存。补direction过滤使WMS列表真实分页匹配。
- docs/design：高保真设计参考图及真实页面截图分开标识，设计稿不冒充运行数据。

## 约定

共享HTTP导出api(Axios实例)、loadPage<T>(path,params)；auth store导出useAuthStore，提供user、can(permission)、load、login、logout，组件从store读取can(permission)；组件错误统一errorText(error)。配置导航route.meta.permission，详情刷新重新读后端。ElementPlus对话框重要库存动作展示仓库及全部明细，提交loading+后端幂等。业务详情响应字段按实际Java DTO。

UI完整加载/空态/错误/重试/提交中；搜索重置分页、远程资料选择器支持分页搜索、真实停用历史详情、草稿取消/删除及当前状态下一步；WMS只能执行PENDING生产入库。只有有权限的来源链接可点击。

## 验证步骤

- [x] 读取规范/实际DTO、创建前端骨架和锁文件，安装固定依赖。
- [x] 认证布局/系统/资料/BOM及业务/WMS完整页面，真实工作台后端及前端。
- [x] TypeScript、Vitest、构建；后端工作台权限/统计真实集成测试与既有回归。
- [x] 独立测试库的前后端浏览器实例，真实采购零部件→10台生产→入库→销售3台余7。
- [x] 管理员/操作员/只读权限、刷新/详情直达、过期/失败反馈、1440/1920及窄屏，修复问题。
- [x] 生成高保真图、真实页面截图、更新UI/API/环境/进度和启动/验证记录后停止。

分工：前端实施子任务负责业务与WMS页面；主代理负责骨架/布局/认证、资料/BOM/系统和工作台/后端、最终浏览器验证与文档。单独审查规格和质量。不修改参考项目，测试交易仅在mini_mfg_test，不清库。

完成于2026-10-18。实际13后端单元+47集成、5前端单元、13组闭环+7组管理浏览器检查通过。依赖审计8项未修复及未验证范围见docs/stage5-verification.md；没有宣称完整独立审查通过。
