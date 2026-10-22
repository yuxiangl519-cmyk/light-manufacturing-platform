# Minimal Enterprise SaaS 界面优化验收

日期：2026-10-21。项目为轻量制造管理平台，保留项目命名；不是修改其他 jshERP 仓库。

## 实现

- Vue 3.5.22、Element Plus 2.11.5、Vite 7.1.12 保持原版本，无新增依赖。
- `frontend/src/enterprise.css` 作为共享主题层，在既有 styles.css 后加载。浅灰导航、白色内容、低饱和蓝强调；统一边框、按钮、输入、表格、卡片、弹窗、抽屉、焦点与字体层次。
- 导航 216px / 折叠 64px、顶部 58px；窄屏可展开完整导航并通过遮罩、Esc 或导航跳转收起。权限菜单仍来自原 navigation + auth.can。
- AI 单一对话滚动区，最新助手回答直接结构化展示，去掉独立报告与原文重复渲染。历史可搜索、桌面可收起，窄屏按需展开。
- 输入区固定在分析容器底部，自适应 2～5 行；Ctrl / ⌘ + Enter 发送，Enter 换行，输入法组合输入不触发快捷发送。保留原发送防重、取消、刷新、重试状态判断。
- 打开会话定位到最后一条消息开头，长回答可从头阅读；离开底部时显示回到最新消息入口。范围与执行详情可展开查看，不展示内部思维链。
- 事实、需核实事项、行动建议、部分失败保留；范围、金额、数量、时区、来源内容由原报告提供，前端不重新计算业务数值。
- 业务模块通过共享样式统一；未改业务组件的数据请求、单据动作、权限判断与计算规则。

## 实际验证

| 检查 | 结果 | 耗时 |
| --- | --- | --- |
| `npm run typecheck` | 通过 | 已执行；不单独计时 |
| `npm run test:unit -- --run` | 5 文件 / 19 项通过 | Vitest 3.22 秒 |
| `npm run build`（含 vue-tsc） | 通过 | 最终 Vite 构建 6.61 秒，不含前置类型检查 |
| `node frontend/scripts/ui-minimal-review.mjs` | 10 / 10 组通过 | 各组累计 8.033 秒，不含浏览器启动关闭 |

浏览器：Microsoft Edge / Chromium，1440×1000 桌面与 390×844 手机视口。0 pageerror、0 未匹配 API 请求。明细见 [原始结构化结果](minimal-ui-results.json)。

浏览器覆盖：空态和权限示例、桌面会话收起、历史搜索、单份报告、范围详情、澄清刷新恢复、快捷键发送、执行步骤、失败重试、取消防重、长表格局部滚动、输入区可见、窄屏导航展开、手机历史恢复、销售筛选、物料表单、帮助弹窗、工作台及 13 个业务/资料/系统列表路由。

全部 `/api/v1/**` 请求由浏览器 fixture 拦截，**这些是隔离界面回归，不是真实 Java / MySQL / 模型端到端验收**。未写开发库、未调用真实模型，无新增认证绕过或生产 mock。fixture 仅在本脚本创建的浏览器上下文生效。

## 验收中修正

- 会话状态 `clarification` 类名与澄清提示条冲突，改为 `status-*` 独立命名。
- 历史长报告最初滚到底部，调整为定位最新消息开头，避免关键指标被跳过。
- 窄屏导航遮罩覆盖点击目标，改为从展开侧栏右缘开始，支持正常关闭。
- 测试脚本修正 Windows 中文/空格截图路径、Vite `/src/api/` 模块被过宽拦截、Element Plus 中文关闭按钮定位；视口检查等待两帧布局完成，保留真实溢出断言。

## 复测与截图

在项目根目录启动独立前端（不必启动后端）：

```powershell
node frontend/node_modules/vite/bin/vite.js frontend --host 127.0.0.1 --port 5174
```

另一个终端执行：

```powershell
node frontend/scripts/ui-minimal-review.mjs
```

默认仅允许 localhost / 127.0.0.1 URL，可通过 `UI_REVIEW_URL` 指向其他本地端口。使用已安装的 Edge 和项目锁定的 Playwright，不安装新依赖。截图及结果输出 `output/playwright/minimal-ui/`（git 忽略）：`ai-empty.png`、`ai-report.png`、`ai-error.png`、`ai-mobile.png`、`sales-list.png`、`material-form.png`、`material-mobile.png`、`help-dialog.png`、`workbench.png`、`stock-list.png`。截图数据仅为 UI fixture。

## 未验证与限制

- 本轮未重跑真实 Java / MySQL / Python / 模型评测，未验证真实写单据或库存一致性；相关实现未变。
- 未覆盖所有业务详情与状态动作、Safari / Firefox、真实移动设备软键盘或完整屏幕阅读器验收。
- 原有 ECharts / Element Plus 分包超过 500 kB 的构建提示仍在；没有为消除提示而升级依赖或变更构建阈值。
- 矮视口改为页面整体滚动，确保控件仍能到达；常规桌面及手机视口输入区固定在分析容器底部。
