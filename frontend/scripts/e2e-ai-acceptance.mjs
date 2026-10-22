import {chromium} from '@playwright/test'
import assert from 'node:assert/strict'
import {readFile,writeFile,mkdir,unlink} from 'node:fs/promises'
assert.equal(process.env.MFG_E2E_DATABASE,'mini_mfg_test')
const base=process.env.MFG_E2E_BASE_URL;assert.equal(base,'http://localhost:5174')
assert.equal((await fetch(base)).headers.get('x-light-mfg-backend-target'),'http://127.0.0.1:18081')
const out='../output/ai-acceptance',fixtures=JSON.parse(await readFile(out+'/fixtures.json','utf8')),reader=JSON.parse(await readFile(out+'/private-user.json','utf8'))
const live=process.env.AI_EVAL_LIVE==='1',browser=await chromium.launch({channel:'msedge',headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage()
page.setDefaultTimeout(20000)
const checks=[],executions=[],errors=[],pending=[];let sends=0,activeCase='setup';const requestCases=new WeakMap()
function audit(p){p.on('pageerror',e=>errors.push(e.message))
p.on('request',r=>{if(r.url().endsWith('/messages')&&r.method()==='POST'){sends++;requestCases.set(r,activeCase)}})
}
function initAudit(){
 window.__aiAcceptance={executions:[],leaks:0,pending:0}
 const original=window.fetch.bind(window)
 window.fetch=async(...args)=>{
  const response=await original(...args)
  if(String(args[0]).includes('/ai/conversations/')&&String(args[0]).endsWith('/messages')){
   const state=window.__aiAcceptance;state.pending++
   void response.clone().text().then(text=>{
    for(const chunk of text.replace(/\r\n/g,'\n').split('\n\n')){
     const data=chunk.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trim()).join('\n');if(!data)continue
     const value=JSON.parse(data)
     if(value.execution)state.executions.push(value.execution)
     if(/Bearer |sk-[A-Za-z0-9_-]{12,}|gatewayToken|SELECT\s+.+\s+FROM|reasoning_content|<think>/i.test(data))state.leaks++
    }
   }).catch(()=>{}).finally(()=>state.pending--)
  }
  return response
 }
}
await page.addInitScript(initAudit)
async function drain(p=page){
 await p.waitForFunction(()=>window.__aiAcceptance?.pending===0,{},{timeout:15000})
 const data=await p.evaluate(()=>{const d=window.__aiAcceptance;const result={executions:d.executions,leaks:d.leaks};d.executions=[];d.leaks=0;return result})
 assert.equal(data.leaks,0,'Public stream leaked internal or sensitive fields')
 executions.push(...data.executions.map(e=>({case:activeCase,...e})))
}

audit(page)
async function check(name,fn){activeCase=name;const started=Date.now(),before=sends;try{await fn();await drain();checks.push({case:name,status:'passed',seconds:(Date.now()-started)/1000,sends:sends-before,reason:''});console.log('PASS '+name)}catch(e){checks.push({case:name,status:'failed',seconds:(Date.now()-started)/1000,sends:sends-before,reason:String(e.message||e).slice(0,350)});console.log('FAIL '+name);await page.screenshot({path:out+'/failure-'+checks.length+'.png',fullPage:true}).catch(()=>{})}}
async function login(p,user,password){await p.goto(base+'/login');await p.getByLabel('账号',{exact:true}).fill(user);await p.getByLabel('密码',{exact:true}).fill(password);await p.getByRole('button',{name:'登录工作空间'}).click();await p.waitForURL(u=>u.pathname!='/login');await p.goto(base+'/ai-analysis');await p.getByRole('heading',{name:'AI 智能分析',exact:true}).waitFor()}
async function ask(text){await page.getByRole('textbox',{name:/你的问题|补充说明/}).fill(text);await page.getByRole('button',{name:/^发送问题$|^继续分析$/}).click();await page.waitForFunction(()=>document.querySelector('.conversation-panel')?.getAttribute('aria-busy')==='false',{},{timeout:185000});await drain()}
async function fresh(){await page.getByRole('button',{name:'新建会话',exact:true}).click()}
function clean(t){return t.replace(/\*\*/g,'').trim()}
async function rows(){return await page.getByLabel('本次分析结果',{exact:true}).locator('.report-table').first().locator('tbody tr').evaluateAll(rs=>rs.map(r=>[...r.querySelectorAll('td')].map(c=>c.textContent.trim())))}
async function summary(expected){const table=await rows();assert.equal(clean(table[0][1]),expected.salesAmount);assert.equal(clean(table[1][1]),expected.orderCount);await page.getByLabel('当前查询条件').filter({hasText:'2026-10-01 至 2026-10-03'}).waitFor();assert.ok((await page.getByLabel('本次分析结果',{exact:true}).innerText()).includes('不是收入或利润'))}
await mkdir(out,{recursive:true})
try{
 await login(page,'admin',process.env.MFG_ADMIN_PASSWORD)
 if(live)await check('真实模型销售总览与独立SQL金额订单核对',async()=>{await ask('2026-10-01至2026-10-02全厂销售分析');await page.getByText('本次分析已结束',{exact:true}).waitFor();await summary(fixtures.sales);await page.getByLabel('关键指标').waitFor();await page.getByText('事实与证据来源',{exact:true}).waitFor()})
 else checks.push({case:'真实模型销售总览',status:'not-run',seconds:0,sends:0,reason:'Real model not explicitly enabled; no doubles used as live verification'})
 await check('客户排行与独立SQL逐项核对',async()=>{if(!live)await fresh();await ask('2026-10-01至2026-10-02客户销售排行前5名');const got=await rows();assert.equal(got.length,fixtures.ranking.length);for(let i=0;i<got.length;i++){const want=fixtures.ranking[i];assert.equal(clean(got[i][0]),want.id);assert.equal(clean(got[i][2]),want.salesAmount);assert.equal(clean(got[i][3]),want.orderCount)}await page.screenshot({path:out+'/customer-ranking.png',fullPage:true})})
 await check('同会话追问单个客户与独立SQL核对',async()=>{await ask('客户ID '+fixtures.ranking[0].id);await summary(fixtures.customer);await page.getByLabel('当前查询条件').filter({hasText:'客户ID '+fixtures.ranking[0].id}).waitFor();assert.ok((await page.getByLabel('本次分析结果',{exact:true}).innerText()).includes('已发货销售订单受控统计'))})
 await check('库存预警与独立余额阈值核对',async()=>{await fresh();await ask('仓库ID '+fixtures.warehouseId+' 哪些物料库存不足');const got=await rows();assert.equal(got.length,fixtures.low.length);for(let i=0;i<got.length;i++){assert.equal(got[i][1].split(' / ')[0],fixtures.low[i][0]);assert.equal(got[i][2],fixtures.low[i][1]);assert.equal(got[i][3],fixtures.low[i][2])}})
 await check('库存切换工单缺料与独立快照余额核对',async()=>{await ask('工单ID '+fixtures.orderId+' 还缺哪些零部件');const got=await rows();assert.deepEqual(got.map(r=>[r[0].split(' / ')[0],r[1],r[2],r[3]]).sort((a,b)=>Number(a[0])-Number(b[0])),fixtures.shortage);assert.ok((await page.getByLabel('当前查询条件').innerText()).includes('工单ID '+fixtures.orderId));await page.screenshot({path:out+'/shortage.png',fullPage:true})})
 await check('利润安全澄清而非销售假成功',async()=>{await fresh();await ask('2026-10-01至2026-10-02利润情况');await page.getByText('等待补充信息',{exact:true}).waitFor();await page.locator('.messages').getByText(/没有可核算利润/).waitFor();assert.equal(await page.getByLabel('关键指标').count(),0)})
 await check('澄清刷新恢复后继续真实库存查询',async()=>{await fresh();await ask('当前库存');const id=new URL(page.url()).searchParams.get('conversation');await page.reload();await page.getByRole('button',{name:'继续分析',exact:true}).waitFor();assert.equal(new URL(page.url()).searchParams.get('conversation'),id);await ask('仓库ID '+fixtures.warehouseId);await page.getByText('本次分析已结束',{exact:true}).waitFor();await page.reload();await page.getByLabel('当前查询条件').filter({hasText:'仓库ID '+fixtures.warehouseId}).waitFor();await page.getByLabel('本次分析结果',{exact:true}).locator('.report-table').first().waitFor()})
 await check('真实工具404失败提示与显式重试',async()=>{await fresh();await ask('仓库ID 9223372036854775807 当前库存');await page.getByRole('button',{name:'重试本次问题',exact:true}).waitFor();await page.getByRole('button',{name:'重试本次问题',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.conversation-panel')?.getAttribute('aria-busy')==='false');await page.getByRole('button',{name:'重试本次问题',exact:true}).waitFor();assert.equal(await page.getByLabel('关键指标').count(),0)})
 if(live)await check('真实提供方模型失败与同会话重试恢复',async()=>{await fresh();await ask('验收模型失败：2026-10-01至2026-10-02全厂销售分析');await page.getByRole('alert').filter({hasText:'模型调用失败'}).waitFor();assert.equal(await page.getByLabel('关键指标').count(),0);await page.getByRole('button',{name:'重试本次问题',exact:true}).waitFor();await ask('重试');await page.getByText('本次分析已结束',{exact:true}).waitFor();await summary(fixtures.sales)})
 if(!live)await check('未配置模型的真实进程失败提示',async()=>{await fresh();await ask('2026-10-01至2026-10-02全厂销售分析');await page.getByRole('alert').filter({hasText:'尚未配置模型'}).waitFor();assert.equal(await page.getByLabel('关键指标').count(),0);await page.getByRole('button',{name:'重试本次问题',exact:true}).waitFor()})
 await check('取消真实任务并防重复发送（仅注入读取延迟）',async()=>{await fresh();const before=sends;await page.getByRole('textbox',{name:'你的问题',exact:true}).fill('验收取消：仓库ID '+fixtures.warehouseId+' 当前库存');await page.getByRole('button',{name:'发送问题',exact:true}).click();await page.getByLabel('执行阶段').filter({hasText:'执行受控工具'}).waitFor();assert.ok(await page.getByRole('button',{name:'发送问题',exact:true}).isDisabled());await page.getByRole('button',{name:'取消分析',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.conversation-panel')?.getAttribute('aria-busy')==='false');assert.equal(sends-before,1);await page.reload();await page.locator('.history-item.selected').getByText('已取消',{exact:true}).waitFor();assert.equal(await page.getByLabel('关键指标').count(),0)})
 await check('只读库存用户权限示例与销售拒绝',async()=>{const readContext=await browser.newContext({locale:'zh-CN'}),readPage=await readContext.newPage();try{await readPage.addInitScript(initAudit);audit(readPage);await login(readPage,reader.username,reader.password);const welcome=readPage.locator('.welcome');assert.equal(await welcome.getByRole('button').count(),1);await welcome.getByRole('button',{name:/原料仓/}).waitFor();await readPage.getByRole('textbox',{name:'你的问题',exact:true}).fill('2026-10-01至2026-10-02客户销售排行');await readPage.getByRole('button',{name:'发送问题',exact:true}).click();await readPage.getByRole('alert').waitFor();assert.equal(await readPage.getByLabel('关键指标').count(),0);const csrf=(await (await readPage.request.get(base+'/api/v1/auth/csrf')).json()).token;const forbidden=await readPage.request.post(base+'/api/v1/master/materials',{headers:{'X-CSRF-TOKEN':csrf},data:{}});assert.equal(forbidden.status(),403);await readPage.getByRole('textbox',{name:'你的问题',exact:true}).fill('仓库ID '+fixtures.warehouseId+' 当前库存');await readPage.getByRole('button',{name:'发送问题',exact:true}).click();await readPage.getByText('本次分析已结束',{exact:true}).waitFor();await readPage.getByLabel('本次分析结果',{exact:true}).locator('.report-table').first().waitFor();await drain(readPage)}finally{await readContext.close()}})
 assert.deepEqual(errors,[]);assert.equal(executions.length,sends-1,'Every non-cancelled terminal must have measured execution stats')
}catch(e){checks.push({case:'acceptance harness',status:'failed',seconds:0,reason:String(e.message||e).slice(0,300)})}
finally{
 await Promise.allSettled(pending)
 const report={database:'mini_mfg_test',fixedClock:'2026-10-20',model:live?'explicit-live':'not-enabled',faultInjection:live?'Cancellation: four-second delay; model failure: invalid model name sent to real configured provider; no business result doubles':'Cancellation only: four-second delay before real read; no business result doubles',checks,executions,errors,sends}
 await writeFile(out+'/browser-results.json',JSON.stringify(report,null,2));await unlink(out+'/private-user.json').catch(()=>{});await browser.close()
 console.log('BROWSER '+checks.filter(c=>c.status==='passed').length+' passed, '+checks.filter(c=>c.status==='failed').length+' failed, '+checks.filter(c=>c.status==='not-run').length+' not-run')
 if(checks.some(c=>c.status==='failed')||errors.length)process.exitCode=1
}
