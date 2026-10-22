// Isolated presentation regression. Every API request is intercepted; no real DB/model calls.
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import { fileURLToPath } from 'node:url'

const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:5174'
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base)) throw new Error('Use a local frontend URL')
const out = new URL('../../output/playwright/minimal-ui/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' })
const page = await context.newPage()
const failures = [], results = [], unknownRequests = []
page.on('pageerror', error => failures.push(error.message))
let readonly = false, mode = 'result', sends = 0, cancels = 0
const permissions = ['workbench:read','erp:sales:read','erp:sales:write','erp:purchase:read','mes:production:read','wms:stock:read','wms:ledger:read','wms:document:read','master:warehouse:read','master:material:read','master:material:write','master:customer:read','master:supplier:read','mes:bom:read','system:user:read','system:role:read','system:log:read']
const report = `销售分析：2026-09-21 至 2026-10-21（结束日期不含当天，上海时间）

| 指标 | 本期 | 等长上期 | 增长率 |
| --- | --- | --- | --- |
| 已发货销售额（元） | 479658.00 | 80876.00 | 493.08% |
| 已发货订单数 | 50 | 9 | 455.56% |

口径：按创建时间筛选当前已发货销售单，人民币含税订单金额；不是收入或利润。

### 已查询确认的事实
销售额较等长上期增加 398782.00 元。比较区间为 2026-08-22 至 2026-09-21（结束日排除）。
查询时刻：2026-10-21T14:46:08+08:00；时区：Asia/Shanghai。
数据来源：已发货销售汇总工具。

### 仍需核实的可能解释
增长可能与集中发货有关，当前证据不足以归因。

### 建议采取的行动
核对本期 50 笔已发货订单，确认是否存在集中发货。
`
const scope = { startDate:'2026-09-21', endDate:'2026-10-21', metric:'sales' }
const conversations = [
  { id:'101', title:'最近30天的销售情况', status:'COMPLETED', context:scope, messages:[{id:'1',role:'user',content:'分析最近30天的销售情况'}, {id:'2',role:'assistant',content:report}] },
  { id:'102', title:'原料仓库存预警', status:'CLARIFICATION', context:{domain:'operations',question:'warehouse'}, pendingQuestion:'请确认要查询哪个仓库？', messages:[{id:'3',role:'user',content:'原料有哪些库存预警？'},{id:'4',role:'assistant',content:'请确认要查询哪个仓库？'}] },
  { id:'103', title:'销售分析 · 等待重试', status:'ERROR', context:scope, messages:[{id:'5',role:'user',content:'最近30天销售情况'}] },
  { id:'104', title:'工单缺料查询', status:'RUNNING', context:{domain:'operations'}, messages:[{id:'6',role:'user',content:'工单 7 还缺哪些零部件？'}] },
  { id:'105', title:'长表格与多轮对话', status:'COMPLETED', context:scope, messages:Array.from({length:10},(_,i)=>({id:`long-${i}`,role:i%2?'assistant':'user',content:i%2?`| 仓库 | 物料 | 当前库存 | 最低阈值 | 单位 |\n| --- | --- | --- | --- | --- |\n${Array.from({length:8},(_,j)=>`| 原料仓 | 标准办公台式机零部件 ${j+1} | 10 | 15 | 件 |`).join('\n')}\n\n查询时刻：2026-10-21T14:46:08+08:00。数据来源：库存查询工具。`:'查看原料仓库存预警'})) },
]
const material = { id:'1',code:'CPU-001',name:'CPU Intel Core i3-14100',type:'RAW_MATERIAL',unit:'件',specification:'盒装 / 电脑组装零部件',minStock:'15',enabled:true,createdAt:'2026-10-20T01:00:00Z',version:0 }
const paged = items => ({items,total:String(items.length),page:1,size:20})
await context.route('**/api/v1/**', async route => {
  const req = route.request(), path = new URL(req.url()).pathname.replace('/api/v1','')
  const json = body => route.fulfill({json:body})
  if(path==='/auth/me') return json({id:'1',username:'ui-review',displayName:'界面验收',roles:['UI_FIXTURE'],permissions:readonly?['wms:stock:read']:permissions})
  if(path==='/auth/csrf') return json({token:'ui-fixture-only'})
  if(path==='/ai/conversations' && req.method()==='GET') return json(conversations.map(({messages,context,pendingQuestion,...item})=>item))
  if(path==='/ai/conversations' && req.method()==='POST') { const item={id:String(200+conversations.length),title:'界面回归会话',status:'IDLE',messages:[],context:{}};conversations.unshift(item);return json(item) }
  const match = path.match(/^\/ai\/conversations\/(\d+)(\/messages|\/cancel)?$/)
  if(match){
    const item = conversations.find(c=>c.id===match[1])
    if(!item)return route.fulfill({status:404,json:{message:'测试会话不存在'}})
    if(match[2]==='/cancel'){cancels++;item.status='CANCELLED';return json({})}
    if(match[2]==='/messages'){
      sends++;const text=JSON.parse(req.postData()).message;item.messages.push({id:`u-${sends}`,role:'user',content:text})
      if(mode==='delayed'){item.status='RUNNING';await new Promise(r=>setTimeout(r,800));if(item.status==='CANCELLED')return route.fulfill({contentType:'text/event-stream',body:'event: done\ndata: {"status":"CANCELLED"}\n\n'}).catch(()=>{})}
      const events=[['stage',{node:'Gateway'}],['stage',{node:'Decision'}],['stage',{node:'Tools'}],['stage',{node:'Decision'}]]
      if(mode==='error'){item.status='ERROR';events.push(['error',{message:'分析服务暂不可用，请稍后重试'}],['done',{status:'ERROR'}])}
      else {item.status='COMPLETED';item.pendingQuestion='';item.context=scope;item.messages.push({id:`a-${sends}`,role:'assistant',content:report});events.push(['result',{content:report,context:scope}],['done',{status:'COMPLETED',execution:{elapsedMs:1250,toolCalls:2,modelCalls:3}}])}
      return route.fulfill({contentType:'text/event-stream',body:events.map(([event,data])=>`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('')})
    }
    return json(item)
  }
  if(path==='/workbench') return json({period:{from:'2026-10-01T00:00:00+08:00',to:'2026-10-22T00:00:00+08:00'},cards:{purchaseCount:'28',salesCount:'50',receivedPurchaseAmount:'162800.00',shippedSalesAmount:'479658.00'},trend:[{date:'2026-10-20',purchaseCount:'3',salesCount:'5'},{date:'2026-10-21',purchaseCount:'4',salesCount:'7'}],todos:[],recentOrders:[],recentProduction:[]})
  if(path==='/materials/1')return json(material)
  if(path==='/materials')return json(paged([material,{...material,id:'2',code:'PC-001',name:'标准办公台式机',type:'FINISHED_GOOD',unit:'台',minStock:'8'}]))
  if(path==='/sales-orders')return json(paged([{id:'1',orderNo:'SO-20261021-001',customerName:'恒远科技',warehouseName:'成品仓',totalAmount:'23680.00',status:'SHIPPED',createdAt:'2026-10-21T02:30:00Z'}]))
  if(path==='/stock-balances')return json(paged([{id:'1',warehouseId:'1',materialId:'1',warehouseName:'原料仓',materialCode:material.code,materialName:material.name,materialType:'RAW_MATERIAL',quantity:'10',minStock:'15',unit:'件',lowStock:true}]))
  if(path==='/warehouses')return json(paged([{id:'1',code:'RAW',name:'原料仓',enabled:true}]))
  if(['/customers','/suppliers','/boms','/purchase-orders','/production-orders','/stock-documents','/stock-ledgers','/system/users','/system/roles','/system/operation-logs'].includes(path))return json(paged([]))
  unknownRequests.push(`${req.method()} ${path}`);return route.fulfill({status:501,json:{message:'Unmapped UI fixture'}})
})
async function check(name,fn){const start=performance.now();try{await fn();results.push({name,status:'passed',elapsedMs:Math.round(performance.now()-start)});console.log('PASS '+name)}catch(e){results.push({name,status:'failed',reason:e.message,elapsedMs:Math.round(performance.now()-start)});throw e}}
async function goto(path){await page.goto(base+path);await expect(page.locator('.main-content')).toBeVisible()}
async function shot(name){await page.screenshot({path:fileURLToPath(new URL(name+'.png',out)),fullPage:true,animations:'disabled'})}
async function noOverflow(){await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));const overflow=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,items:[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth+1 && getComputedStyle(e).position!=='fixed').slice(0,12).map(e=>({tag:e.tagName,cls:e.className,right:e.getBoundingClientRect().right}))}));if(overflow.scroll>overflow.width+1)throw new Error('Viewport overflow: '+JSON.stringify(overflow))}
try{
  await check('桌面空态、示例填入、固定输入区',async()=>{
    await goto('/ai-analysis');await expect(page.getByText('有什么可以帮你？')).toBeVisible();await page.getByRole('button',{name:'分析最近30天的销售情况',exact:true}).click();await expect(page.getByLabel('你的问题',{exact:true})).toHaveValue('分析最近30天的销售情况');await noOverflow();await page.getByRole('button',{name:'收起会话',exact:true}).click();await expect(page.getByLabel('搜索历史会话')).toBeHidden();await page.getByRole('button',{name:'历史会话',exact:true}).click();await expect(page.getByLabel('搜索历史会话')).toBeVisible();await shot('ai-empty')
  })
  await check('历史搜索、报告不重复、范围和执行详情',async()=>{
    await page.getByLabel('搜索历史会话').fill('最近30天');await expect(page.locator('.history-item')).toHaveCount(1);await page.locator('.history-item').click();await expect(page.getByLabel('本次分析结果')).toHaveCount(1);await expect(page.getByText('增长可能与集中发货有关，当前证据不足以归因。',{exact:true})).toHaveCount(1);await page.getByText('范围与执行详情',{exact:true}).click();await expect(page.getByLabel('当前查询条件')).toContainText('Asia/Shanghai');await page.getByText('范围与执行详情',{exact:true}).click();await page.getByLabel('搜索历史会话').fill('');await shot('ai-report')
  })
  await check('澄清刷新恢复、快捷键发送、统计与阶段',async()=>{
    await goto('/ai-analysis?conversation=102');await expect(page.getByText('等待你的补充',{exact:true})).toBeVisible();await page.reload();await expect(page.getByLabel('补充说明',{exact:true})).toBeVisible();await page.getByLabel('补充说明',{exact:true}).fill('仓库ID 1');await page.getByLabel('补充说明',{exact:true}).press('Control+Enter');await expect(page.getByLabel('本次分析结果')).toBeVisible();await page.getByText('范围与执行详情',{exact:true}).click();await expect(page.getByLabel('执行阶段').locator('li')).toHaveCount(4);await expect(page.getByLabel('执行统计')).toContainText('工具调用 2 次');await page.getByText('范围与执行详情',{exact:true}).click()
  })
  await check('模型错误提示与重试（仅模拟响应）',async()=>{
    mode='error';await page.getByLabel('你的问题',{exact:true}).fill('再分析上个月');await page.getByRole('button',{name:/发送问题/}).click();await expect(page.getByRole('alert')).toContainText('分析服务暂不可用');await shot('ai-error');mode='result';await page.getByRole('button',{name:'重试本次问题'}).click();await expect(page.getByRole('alert')).toHaveCount(0);await expect(page.getByLabel('本次分析结果')).toBeVisible()
  })
  await check('取消与防重复发送（仅模拟响应）',async()=>{
    mode='delayed';const before=sends;await page.getByLabel('你的问题',{exact:true}).fill('检查库存');await page.getByRole('button',{name:/发送问题/}).click();await expect(page.getByRole('button',{name:/发送问题/})).toBeDisabled();await page.getByRole('button',{name:'取消分析'}).click();await expect(page.getByRole('status').filter({hasText:'已'})).toBeVisible();expect(sends-before).toBe(1);expect(cancels).toBe(1);mode='result'
  })
  await check('长报告单滚动、回到底部、输入不移出视口',async()=>{
    await goto('/ai-analysis?conversation=105');await expect(page.getByLabel('本次分析结果')).toBeVisible();const box=await page.locator('.composer').boundingBox();expect(box.y+box.height).toBeLessThan(1000);await page.locator('.conversation-body').evaluate(el=>{el.scrollTop=0;el.dispatchEvent(new Event('scroll'))});await expect(page.getByRole('button',{name:'↓ 回到最新消息'})).toBeVisible();await page.getByRole('button',{name:'↓ 回到最新消息'}).click();await noOverflow()
  })
  await check('手机布局、历史展开、表格局部滚动',async()=>{
    await page.setViewportSize({width:390,height:844});await noOverflow();await page.getByRole('button',{name:'展开菜单',exact:true}).click();await expect(page.locator('.sidebar .brand-text')).toBeVisible();await page.getByRole('button',{name:'关闭导航菜单'}).click();await expect(page.locator('.sidebar .brand-text')).toBeHidden();await expect(page.getByRole('button',{name:'历史会话',exact:true})).toBeVisible();await page.getByRole('button',{name:'历史会话',exact:true}).click();await expect(page.getByLabel('搜索历史会话')).toBeVisible();await page.getByLabel('搜索历史会话').fill('最近30天');await page.locator('.history-item').click();await expect(page.getByLabel('搜索历史会话')).toBeHidden();await shot('ai-mobile');await noOverflow();const box=await page.locator('.composer').boundingBox();expect(box.y+box.height).toBeLessThan(844)
  })
  await check('业务列表、筛选、资料表单与公共弹窗',async()=>{
    await page.setViewportSize({width:1440,height:1000});await goto('/erp/sales');await expect(page.getByText('SO-20261021-001')).toBeVisible();await page.getByRole('button',{name:'查询',exact:true}).click();await shot('sales-list');await goto('/master/materials');await page.getByRole('button',{name:'新建物料',exact:true}).click();await expect(page.getByLabel('编码',{exact:true})).toBeVisible();await shot('material-form');await page.getByRole('button',{name:'取消',exact:true}).click();await page.getByRole('button',{name:'使用帮助'}).click();await expect(page.getByRole('dialog',{name:'六步完成一次电脑组装'})).toBeVisible();await shot('help-dialog');await page.getByRole('dialog',{name:'六步完成一次电脑组装'}).locator('.el-dialog__headerbtn').click()
  })
  await check('工作台与其他模块布局冒烟',async()=>{
    await goto('/workbench');await expect(page.getByText('479,658.00',{exact:false})).toBeVisible();await shot('workbench');for(const route of ['/wms/balances','/wms/inbound','/wms/outbound','/wms/ledgers','/erp/purchases','/mes/production-orders','/mes/boms','/master/customers','/master/suppliers','/master/warehouses','/system/users','/system/roles','/system/logs']){await goto(route);await noOverflow()}await goto('/wms/balances');await shot('stock-list')
  })
  await check('窄屏业务列表与只读权限菜单',async()=>{
    await page.setViewportSize({width:390,height:844});await goto('/master/materials');await noOverflow();await page.getByRole('button',{name:'新建物料',exact:true}).click();await noOverflow();await shot('material-mobile');readonly=true;await goto('/ai-analysis');await expect(page.locator('.nav-item')).toHaveCount(2);await expect(page.locator('.example-grid button')).toHaveCount(1);await expect(page.locator('.example-grid')).toContainText('库存不足');await noOverflow()
  })
  await check('笔记本和手机空态无冗余说明、输入框完整可见',async()=>{
    readonly=false
    for(const viewport of [{width:1280,height:720},{width:390,height:844}]){
      await page.setViewportSize(viewport);await goto('/ai-analysis');await expect(page.getByText('有什么可以帮你？')).toBeVisible();await noOverflow()
      await expect(page.locator('.composer-hint,.composer-note,.history-foot,.page-subtitle')).toHaveCount(0)
      const box=await page.locator('.composer').boundingBox();expect(box.y).toBeGreaterThan(0);expect(box.y+box.height).toBeLessThan(viewport.height)
      await expect(page.getByRole('button',{name:'发送问题',exact:true})).toBeVisible();await shot(viewport.width===1280?'ai-laptop-empty':'ai-mobile-empty')
    }
  })
  expect(failures).toEqual([]);expect(unknownRequests).toEqual([])
}catch(error){await shot('failure');process.exitCode=1;console.error(error.message)}
finally{await writeFile(new URL('results.json',out),JSON.stringify({kind:'isolated UI fixtures; no real backend/database/model',results,runtimeErrors:failures,unmappedRequests:unknownRequests},null,2));await browser.close()}
