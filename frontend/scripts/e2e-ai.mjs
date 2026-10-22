import {chromium} from '@playwright/test'
import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
assert.equal(process.env.MFG_E2E_DATABASE,'mini_mfg_test');const base=process.env.MFG_E2E_BASE_URL;assert.equal(base,'http://localhost:5174');assert.equal((await fetch(base)).headers.get('x-light-mfg-backend-target'),'http://127.0.0.1:18081')
const browser=await chromium.launch({channel:'msedge',headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage();page.setDefaultTimeout(20000)
const errors=[],checks=[];page.on('pageerror',e=>errors.push(e.message));await mkdir('../output/ai',{recursive:true})
async function check(name,work){await work();checks.push(name);console.log('PASS '+name)}
async function ask(message){await page.getByRole('textbox',{name:/你的问题|补充说明/}).fill(message);await page.getByRole('button',{name:/^发送问题$|^继续分析$/}).click()}
try{
await page.goto(base+'/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(process.env.MFG_ADMIN_PASSWORD);await page.getByRole('button',{name:'登录工作空间'}).click();await page.waitForURL(u=>u.pathname!='/login');await page.locator('aside').getByRole('link',{name:'AI 智能分析',exact:true}).click();await page.getByRole('heading',{name:'AI 智能分析',exact:true}).waitFor()
await check('真实Vue→Java→FastAPI→DeepSeek分析与Markdown表格',async()=>{await ask('分析最近30天的销售情况，并给出经营建议。');await page.getByRole('status').filter({hasText:'分析进行中'}).waitFor();await page.getByText('本次分析已结束',{exact:true}).waitFor({timeout:150000});await page.locator('.report-table').first().waitFor();await page.getByText(/数据来源：已发货销售订单受控统计/).first().waitFor();assert.equal(await page.getByRole('alert').count(),0);await page.screenshot({path:'../output/ai/analysis-live.png',fullPage:true})})
await check('刷新后打开历史保留真实报告',async()=>{await page.reload();await page.locator('.history-item').first().click();await page.locator('.report-table').first().waitFor();await page.locator('.messages').getByText('分析最近30天的销售情况，并给出经营建议。',{exact:true}).waitFor()})
await check('利润澄清后继续同一会话',async()=>{await page.getByRole('button',{name:'新建会话',exact:true}).click();await ask('帮我分析最近的利润情况');await page.getByText('等待补充信息',{exact:true}).waitFor();await page.getByText(/当前系统没有可核算利润/).waitFor();await ask('可以，改为最近7天全厂销售分析');await page.getByText('本次分析已结束',{exact:true}).waitFor({timeout:150000});await page.locator('.report-table').first().waitFor()})
await check('取消真实模型分析并持久化取消状态',async()=>{await page.getByRole('button',{name:'新建会话',exact:true}).click();await ask('分析最近30天销售情况');await page.getByRole('status').filter({hasText:'分析进行中'}).waitFor();await page.getByRole('button',{name:'取消分析',exact:true}).click();await page.getByRole('button',{name:'发送问题',exact:true}).waitFor();await page.reload();await page.locator('.history-item').first().getByText('已取消',{exact:true}).waitFor()})
assert.deepEqual(errors,[]);await writeFile('../output/ai/browser-results.json',JSON.stringify({database:'mini_mfg_test',checks,errors},null,2));console.log('AI BROWSER COMPLETE '+checks.length)
}catch(e){await page.screenshot({path:'../output/ai/browser-failure.png',fullPage:true}).catch(()=>{});console.error(String(e.stack||e).split(process.env.MFG_ADMIN_PASSWORD||'NO_SECRET').join('[REDACTED]'));process.exitCode=1}
finally{await browser.close()}
