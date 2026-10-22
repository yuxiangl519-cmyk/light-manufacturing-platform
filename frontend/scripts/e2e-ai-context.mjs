import {chromium} from '@playwright/test'
import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
assert.equal(process.env.MFG_E2E_DATABASE,'mini_mfg_test')
const base=process.env.MFG_E2E_BASE_URL
assert.equal(base,'http://localhost:5174')
assert.equal((await fetch(base)).headers.get('x-light-mfg-backend-target'),'http://127.0.0.1:18081')
const browser=await chromium.launch({channel:'msedge',headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage()
page.setDefaultTimeout(20000)
const checks=[],errors=[];page.on('pageerror',e=>errors.push(e.message));await mkdir('../output/ai-context',{recursive:true})
async function check(name,action){const start=Date.now();await action();checks.push({name,status:'passed',seconds:(Date.now()-start)/1000});console.log('PASS '+name)}
async function ask(text){await page.getByRole('textbox',{name:/你的问题|补充说明/}).fill(text);await page.getByRole('button',{name:/^发送问题$|^继续分析$/}).click()}
try{
  await page.goto(base+'/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(process.env.MFG_ADMIN_PASSWORD);await page.getByRole('button',{name:'登录工作空间'}).click();await page.waitForURL(u=>u.pathname!='/login')
  await page.goto(base+'/ai-analysis')
  await check('库存澄清保存URL且不要求销售日期',async()=>{
    await ask('当前库存');await page.getByText('等待补充信息',{exact:true}).waitFor()
    await page.getByText(/请提供仓库名称或仓库ID/).waitFor()
    assert.match(new URL(page.url()).searchParams.get('conversation'),/^[1-9]\d*$/)
  })
  const id=new URL(page.url()).searchParams.get('conversation')
  await check('刷新自动恢复原会话与未完成澄清',async()=>{
    await page.reload();await page.getByRole('button',{name:'继续分析',exact:true}).waitFor()
    await page.getByText(/请提供仓库名称或仓库ID/).waitFor()
    assert.equal(new URL(page.url()).searchParams.get('conversation'),id)
    await page.locator('.history-item.selected').waitFor()
  })
  await check('恢复后补充仓库并重查实际库存',async()=>{
    await ask('原料仓')
    await page.getByRole('button',{name:/^发送问题$|^继续分析$/}).waitFor({state:'visible'})
    await page.waitForFunction(()=>!document.querySelector('.conversation-panel[aria-busy="true"]'))
    // Ambiguity is resolved by a test user selecting a returned backend ID, not model guessing.
    const candidates=await page.locator('.message:not(.user)').last().innerText()
    if(candidates.includes('候选')){
      const match=/ID\s+([1-9]\d*)/.exec(candidates);assert.ok(match);await ask('仓库ID '+match[1])
    }
    await page.getByText('本次分析已结束',{exact:true}).waitFor()
    await page.locator('.report-table').first().waitFor()
    await page.getByLabel('当前查询条件').filter({hasText:'仓库ID'}).waitFor()
    assert.equal(await page.getByRole('alert').count(),0)
  })
  await check('完成后刷新仍保留服务器结构化条件',async()=>{
    await page.reload();await page.getByLabel('当前查询条件').filter({hasText:'仓库ID'}).waitFor()
    await page.locator('.report-table').first().waitFor();assert.equal(new URL(page.url()).searchParams.get('conversation'),id)
    await page.screenshot({path:'../output/ai-context/restored.png',fullPage:true})
  })
  assert.deepEqual(errors,[])
  await writeFile('../output/ai-context/browser-results.json',JSON.stringify({database:'mini_mfg_test',model:'not-called',checks,errors},null,2))
}catch(e){await page.screenshot({path:'../output/ai-context/failure.png',fullPage:true}).catch(()=>{});console.error(String(e.stack||e).split(process.env.MFG_ADMIN_PASSWORD||'NO_SECRET').join('[REDACTED]'));process.exitCode=1}
finally{await browser.close()}
