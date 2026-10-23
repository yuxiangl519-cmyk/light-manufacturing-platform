# 前端依赖安全复核（2026-10-22）

用户授权本次有限安全修复；本报告只覆盖 frontend npm 依赖，不表示后端/Python 全栈安全审计。读取 npm 官方 audit 返回并逐项访问其 49 条唯一 GitHub 公告（同一公告可能由多个包重复报告），结合项目调用路径判断适用性；未运行漏洞攻击 PoC。API 限流的公告改读公开公告页面。

## 固定版本与兼容判断

| 直接依赖 | 原版 | 修复版 | 依据 |
| --- | --- | --- | --- |
| vue | 3.5.22 | 3.5.42 | 同一 minor 的首个 SSR 修复版，同步 Vue 内部包 |
| axios | 1.12.2 | 1.20.0 | 同一 major，覆盖本次所有已报告漏洞 |
| echarts | 6.0.0 | 6.1.0 | 同一 major 的 Lines tooltip 修复版 |
| vite | 7.1.12 | 7.3.7 | 保留 Vite 7，覆盖最新开发服务/编辑器漏洞 |
| vitest | 3.2.4 | 4.1.12 | 3.x 不再回补 mocker 漏洞；4.1.12 同时覆盖手工 mock 代码注入 |

没有使用 npm audit fix --force、overrides 或忽略公告来制造零告警。npm audit 建议的 Vitest 3.2.7 不能覆盖 GHSA-82fw-gwwq-j7x9 / GHSA-qxg6-89mg-rqv8，故经审查迁移到 4.1.12。Vitest 4 不再依赖 tinypool，避免强行跨主版本覆盖它。其官方元数据接受 Node 24、Vite 7 与 @types/node 24；Vite 7.3.7 的 Node 要求仍为 ^20.19.0 或 >=22.12.0。现有 Node 24.13.0 满足。测试使用基础 describe/it/expect/vi API，未使用移除的 poolOptions/workspace 等配置。应用代码、API 契约、数据库结构和 UI 业务行为未因本修复改变。

版本元数据：[Vue](https://registry.npmjs.org/vue/3.5.42)、[Axios](https://registry.npmjs.org/axios/1.20.0)、[ECharts](https://registry.npmjs.org/echarts/6.1.0)、[Vite](https://registry.npmjs.org/vite/7.3.7)、[Vitest](https://registry.npmjs.org/vitest/4.1.12)。[Vitest 官方迁移说明](https://vitest.dev/guide/migration.html)。

## 逐项公告适用性

判断证据：frontend/package.json、vite.config.ts、src/main.ts、src/api/http.ts、src/views/WorkbenchView.vue 以及前端测试/请求调用检索。下面的“不匹配”仅指当前代码路径，不作为保留受影响版本的理由；所有列出的受影响依赖均纳入修复。原型污染 gadget 通常需要其他漏洞先污染原型，不能仅凭依赖安装断言本应用可被直接攻击。

| 包 | 公告 | 原审计受影响范围 | 本项目判断 |
| --- | --- | --- | --- |
| @vitest/mocker | [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9) | >=2.1.0 <4.1.11 | mocker/interceptor 公共插件 WebSocket 文件读取；项目无这些插件，仍升级。 |
| @vitest/mocker | [GHSA-qxg6-89mg-rqv8](https://github.com/advisories/GHSA-qxg6-89mg-rqv8) | >=2.1.0-beta.7 <4.1.12 | mocker 手工 mock 注册代码注入；项目无公共 mocker 插件，仍升级。 |
| @vue/server-renderer | [GHSA-g2v6-rqmx-r4w6](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6) | <3.5.42 | SSR 动态属性名 XSS；项目为 createApp SPA，无服务端渲染。 |
| axios | [GHSA-35jp-ww65-95wh](https://github.com/advisories/GHSA-35jp-ww65-95wh) | >=1.0.0 <1.16.0 | 先存原型污染结合 Node config.proxy；浏览器无 Node 代理。 |
| axios | [GHSA-3g43-6gmg-66jw](https://github.com/advisories/GHSA-3g43-6gmg-66jw) | >=1.0.0 <1.15.2 | 先存原型污染影响响应转换；函数值污染需额外能力，未证明存在污染入口。 |
| axios | [GHSA-3p68-rc4w-qgx5](https://github.com/advisories/GHSA-3p68-rc4w-qgx5) | >=1.0.0 <1.15.0 | Node HTTP 的 NO_PROXY 规范化；本项目浏览器请求无 Node 代理。 |
| axios | [GHSA-3w6x-2g7m-8v23](https://github.com/advisories/GHSA-3w6x-2g7m-8v23) | >=1.0.0 <1.15.2 | 先存函数值原型污染可篡改 JSON 响应；本项目读取 JSON，升级消除 gadget，未证明存在污染入口。 |
| axios | [GHSA-42h9-826w-cgv3](https://github.com/advisories/GHSA-42h9-826w-cgv3) | >=1.0.0 <1.18.0 | 不可信 FormData 键转 JSON 递归；项目无 formToJSON 或 FormData 转 JSON。 |
| axios | [GHSA-43fc-jf86-j433](https://github.com/advisories/GHSA-43fc-jf86-j433) | >=1.0.0 <=1.13.4 | 要求攻击者提供 Axios config 的 __proto__ 自有键；项目配置由代码构造，无任意配置输入。 |
| axios | [GHSA-445q-vr5w-6q77](https://github.com/advisories/GHSA-445q-vr5w-6q77) | >=1.0.0 <1.15.1 | Node multipart Blob MIME 注入；本项目无 Node 上传代理。 |
| axios | [GHSA-4hqw-qxg8-jxx2](https://github.com/advisories/GHSA-4hqw-qxg8-jxx2) | >=1.12.0 <1.20.0 | fetch/FormData 原型头注入；项目无此适配器/数据路径。 |
| axios | [GHSA-5c9x-8gcm-mpgx](https://github.com/advisories/GHSA-5c9x-8gcm-mpgx) | >=1.0.0 <1.15.1 | Node HTTP 流上传大小限制；本项目无该适配器/流上传。 |
| axios | [GHSA-62hf-57xw-28j9](https://github.com/advisories/GHSA-62hf-57xw-28j9) | >=1.0.0 <1.15.1 | 不可信深层对象经 toFormData/params 递归；项目为固定浅层查询和 JSON 业务载荷，未见任意嵌套入口。 |
| axios | [GHSA-6chq-wfr3-2hj9](https://github.com/advisories/GHSA-6chq-wfr3-2hj9) | >=1.0.0 <1.15.1 | 先存原型污染结合 Node HTTP FormData 头注入；浏览器无该路径。 |
| axios | [GHSA-777c-7fjr-54vf](https://github.com/advisories/GHSA-777c-7fjr-54vf) | >=1.7.0 <1.16.0 | fetch 适配器有限大小限制绕过；项目未选 fetch 或设置这些限制。 |
| axios | [GHSA-7q8q-rj6j-mhjq](https://github.com/advisories/GHSA-7q8q-rj6j-mhjq) | >=1.0.0 <1.18.0 | 先存污染加 auth/paramsSerializer 空对象；项目未传这些选项。 |
| axios | [GHSA-898c-q2cr-xwhg](https://github.com/advisories/GHSA-898c-q2cr-xwhg) | >=1.0.0 <1.16.0 | 先存原型污染影响合并头/请求可用性；请求路径相关，未证明存在污染入口。 |
| axios | [GHSA-9fr6-4gfg-395g](https://github.com/advisories/GHSA-9fr6-4gfg-395g) | >=1.0.0 <1.20.0 | 默认实例无 method 请求才触发；项目使用方法别名和 create 实例。 |
| axios | [GHSA-fvcv-3m26-pcqx](https://github.com/advisories/GHSA-fvcv-3m26-pcqx) | >=1.0.0 <1.15.0 | 先存原型污染结合 Node HTTP 头注入；浏览器无该路径。 |
| axios | [GHSA-hfxv-24rg-xrqf](https://github.com/advisories/GHSA-hfxv-24rg-xrqf) | >=1.0.0 <1.16.0 | 要求控制 xsrfCookieName；项目使用默认静态名称，无用户配置入口。 |
| axios | [GHSA-j5f8-grm9-p9fc](https://github.com/advisories/GHSA-j5f8-grm9-p9fc) | >=1.0.0 <1.16.0 | Node 代理转直连重定向泄漏；浏览器适配器不受此路径影响。 |
| axios | [GHSA-j8rh-479h-cp32](https://github.com/advisories/GHSA-j8rh-479h-cp32) | >=1.0.0 <1.20.0 | 拦截器返回无 headers 新对象才触发；项目修改并返回原 config，保留 headers。 |
| axios | [GHSA-jqh4-m9w3-8hp9](https://github.com/advisories/GHSA-jqh4-m9w3-8hp9) | >=1.7.0 <1.18.0 | fetch ReadableStream 上传限制绕过；项目无该上传路径。 |
| axios | [GHSA-m7pr-hjqh-92cm](https://github.com/advisories/GHSA-m7pr-hjqh-92cm) | >=1.0.0 <1.15.1 | Node HTTP 回环 IP 别名代理绕过；本项目无此运行路径。 |
| axios | [GHSA-mmx7-hfxf-jppx](https://github.com/advisories/GHSA-mmx7-hfxf-jppx) | >=1.0.0 <1.18.0 | 先存原型污染可影响 GET 等别名的请求体；项目使用别名，未证明存在污染入口。 |
| axios | [GHSA-p92q-9vqr-4j8v](https://github.com/advisories/GHSA-p92q-9vqr-4j8v) | >=1.0.0 <1.16.0 | Node 认证代理重定向泄漏；浏览器适配器不受此路径影响。 |
| axios | [GHSA-pf86-5x62-jrwf](https://github.com/advisories/GHSA-pf86-5x62-jrwf) | >=1.0.0 <1.15.1 | 先存原型污染可篡改响应/劫持传输；响应路径相关，未证明存在污染入口。 |
| axios | [GHSA-pmv8-rq9r-6j72](https://github.com/advisories/GHSA-pmv8-rq9r-6j72) | >=1.0.0 <1.18.0 | 不可信 FormData 键转 JSON 递归；项目无 formToJSON 或 FormData 转 JSON。 |
| axios | [GHSA-pmwg-cvhr-8vh7](https://github.com/advisories/GHSA-pmwg-cvhr-8vh7) | >=1.0.0 <1.15.1 | Node HTTP 回环网段代理绕过；本项目无此运行路径。 |
| axios | [GHSA-q8qp-cvcw-x6jj](https://github.com/advisories/GHSA-q8qp-cvcw-x6jj) | >=1.0.0 <1.15.2 | Node HTTP 配置原型读取；本项目无 Node HTTP 业务调用，baseURL 固定。 |
| axios | [GHSA-vf2m-468p-8v99](https://github.com/advisories/GHSA-vf2m-468p-8v99) | >=1.0.0 <1.15.1 | Node HTTP 流响应大小限制；本项目无该适配器/流响应。 |
| axios | [GHSA-vh66-26gq-q6x8](https://github.com/advisories/GHSA-vh66-26gq-q6x8) | >=1.7.0 <1.20.0 | fetchOptions 原型污染；项目默认浏览器适配器，无 fetchOptions。 |
| axios | [GHSA-w9j2-pvgh-6h63](https://github.com/advisories/GHSA-w9j2-pvgh-6h63) | >=1.0.0 <1.15.1 | 先存原型污染可改变状态校验；本项目依赖正常 401/403 处理，升级消除 gadget，未证明存在污染入口。 |
| axios | [GHSA-xhjh-pmcv-23jw](https://github.com/advisories/GHSA-xhjh-pmcv-23jw) | >=1.0.0 <1.15.1 | 要求直接内部 URLSearchParams 或自定义 serializer；项目未使用。 |
| axios | [GHSA-xx6v-rp6x-q39c](https://github.com/advisories/GHSA-xx6v-rp6x-q39c) | >=1.0.0 <1.15.1 | 浏览器 XSRF 配置污染可跨域发令牌；本项目同源 API/显式 CSRF 头，仍升级消除 gadget。 |
| echarts | [GHSA-fgmj-fm8m-jvvx](https://github.com/advisories/GHSA-fgmj-fm8m-jvvx) | <6.1.0 | 仅 Lines 系列默认 tooltip/name 路径；本项目 LineChart 折线（非 Lines），仍升级。 |
| tinypool | [GHSA-5gmw-xhrv-c9v3](https://github.com/advisories/GHSA-5gmw-xhrv-c9v3) | <=2.1.0 | 先存原型污染影响 worker options；仅测试依赖，升级 Vitest 后移除 tinypool。 |
| tinypool | [GHSA-85c8-ppgw-ccpr](https://github.com/advisories/GHSA-85c8-ppgw-ccpr) | <2.1.2 | 先存原型污染影响 run options；仅测试依赖，升级 Vitest 后移除 tinypool。 |
| vite | [GHSA-4w7w-66w2-5vf9](https://github.com/advisories/GHSA-4w7w-66w2-5vf9) | >=7.0.0 <=7.3.1 | 开发服务 .map 路径穿越；服务默认 localhost 限制网络暴露，仍须修复。 |
| vite | [GHSA-587h-7wmj-h5pm](https://github.com/advisories/GHSA-587h-7wmj-h5pm) | >=7.0.0 <=7.3.6 | Windows 文件存在检查绕过，可串联命令注入；本机 Windows，升级。 |
| vite | [GHSA-698v-4r3x-3vcv](https://github.com/advisories/GHSA-698v-4r3x-3vcv) | >=7.0.0 <=7.3.6 | Windows 编辑器命令转义；要求恶意文件/环境变量等条件，本机 Windows，升级。 |
| vite | [GHSA-9jrq-w75r-8gcw](https://github.com/advisories/GHSA-9jrq-w75r-8gcw) | >=7.0.0 <=7.3.6 | 自定义 HTML 转换中间件跨域脚本；项目默认 SPA，无 transformIndexHtml 调用。 |
| vite | [GHSA-fx2h-pf6j-xcff](https://github.com/advisories/GHSA-fx2h-pf6j-xcff) | >=7.0.0 <=7.3.4 | Windows NTFS/短文件名绕过 fs.deny；本机 Windows，升级。 |
| vite | [GHSA-p9ff-h696-f583](https://github.com/advisories/GHSA-p9ff-h696-f583) | >=7.0.0 <=7.3.1 | 开发服务 WebSocket fetchModule 文件读取；服务使用 WebSocket，升级。 |
| vite | [GHSA-r825-w8mm-5pxw](https://github.com/advisories/GHSA-r825-w8mm-5pxw) | >=7.0.0 <=7.3.6 | 特定编辑器 CLI 标志解释；未核实用户编辑器/WSL 条件，按条件风险修复。 |
| vite | [GHSA-rq7h-c2jc-7f22](https://github.com/advisories/GHSA-rq7h-c2jc-7f22) | >=7.0.0 <=7.3.6 | 非 Windows 特定根路径导入触发；本机 Windows 不匹配，升级也覆盖未来环境。 |
| vite | [GHSA-v2wj-q39q-566r](https://github.com/advisories/GHSA-v2wj-q39q-566r) | >=7.1.0 <=7.3.1 | 开发服务查询串绕过 fs.deny；默认 localhost 不等于修复，升级。 |
| vite | [GHSA-v6wh-96g9-6wx3](https://github.com/advisories/GHSA-v6wh-96g9-6wx3) | >=7.0.0 <=7.3.4 | Windows launch-editor UNC/NTLM 泄漏；本机 Windows，开发服务可成为入口，升级。 |
| vitest | [GHSA-5xrq-8626-4rwp](https://github.com/advisories/GHSA-5xrq-8626-4rwp) | <3.2.6 | Vitest UI/API/browser 服务文件读取；本项目 Node 单测，无 UI/browser/API 配置。 |

## 验证结果

2026-10-23完成真实验证：npm ci成功；独立npm audit --json结果0条（初始8条）。第一次独立audit因TLS连接失败没有结果，改为IPv4优先后重新请求成功；未通过关闭证书验证绕过问题。原始结构化审计结果已归入docs/hardening-results.json的审计部分。

Vue类型检查通过、Vitest 20项单测通过、生产构建通过；Edge的11组隔离界面回归通过。构建仍有原有大分包提示。浏览器fixture不是实际模型/数据库端到端验收。

对应锁文件均已更新，后续使用npm ci；CI执行npm audit --audit-level=moderate，新增告警会阻止检查通过。
