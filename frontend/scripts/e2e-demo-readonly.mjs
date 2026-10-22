// Read-only business acceptance of the explicitly authorized development demo batch.
// The only writes are ordinary login/logout audit and Redis sessions; no business actions.
import { chromium } from '@playwright/test'
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'

assert.equal(process.env.MFG_DEMO_DATABASE, 'mini_mfg_dev')
assert.ok(process.env.MFG_OPERATOR_PASSWORD)
const base = 'http://localhost:5173'
assert.equal((await fetch(base)).headers.get('x-light-mfg-backend-target'), 'http://127.0.0.1:8081')
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
const page = await context.newPage(), errors = [], checks = []
page.setDefaultTimeout(20000)
page.on('pageerror', error => errors.push(error.message))
await mkdir('../output/playwright/demo-data', { recursive: true })
async function read(path) {
  const response = await context.request.get(base + '/api/v1' + path)
  assert.equal(response.status(), 200, path)
  return response.json()
}
try {
  await page.goto(base + '/login')
  await page.getByLabel('账号', { exact: true }).fill('operator')
  await page.getByLabel('密码', { exact: true }).fill(process.env.MFG_OPERATOR_PASSWORD)
  await page.getByRole('button', { name: '登录工作空间' }).click()
  await page.waitForURL(url => url.pathname === '/workbench')
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: '../output/playwright/demo-data/workbench.png', fullPage: true })
  checks.push('实际工作台加载，无浏览器脚本错误')
  const balances = await read('/stock-balances?q=DM261020&size=100')
  // The WMS query intentionally includes zero stock for material/warehouse combinations.
  const stocked = balances.items.filter(row => Number(row.quantity) > 0)
  assert.equal(stocked.length, 7)
  for (const row of stocked) {
    assert.equal(row.quantity, row.materialCode.endsWith('PC') ? '14' : row.materialCode.endsWith('RAM') ? '20' : '10')
  }
  checks.push('真实接口返回7项演示库存，10/20/14余额正确')
  const amount = await read('/workbench?from=2026-09-20T16:00:00Z&to=2026-10-20T16:00:00Z')
  assert.ok(Number(amount.cards.shippedSalesAmount) >= 479658)
  checks.push('指定30天工作台包含新增模拟销售额，保留全厂汇总口径')
  const orders = await read('/production-orders?productMaterialId=' + balances.items.find(x => x.materialCode.endsWith('PC')).materialId + '&size=100')
  assert.equal(orders.items.length, 28)
  assert.equal(orders.items.filter(x => x.status === 'PENDING_INBOUND').length, 1)
  checks.push('28张生产工单及待入库状态可查询')
  await page.goto(base + '/wms/balances')
  await page.waitForLoadState('networkidle')
  await page.getByPlaceholder('搜索编号或名称').fill('DM261020')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByRole('row').filter({ hasText: 'DM261020-PC' }).filter({ hasText: '演示·办公整机仓' }).waitFor()
  await page.screenshot({ path: '../output/playwright/demo-data/balances.png', fullPage: true })
  checks.push('库存页面真实搜索与演示库存展示')
  const pending = orders.items.find(x => x.status === 'PENDING_INBOUND')
  await page.goto(base + '/mes/production-orders/' + pending.id)
  await page.waitForLoadState('networkidle')
  await page.getByText('标准办公台式机（演示）', { exact: false }).first().waitFor()
  await page.screenshot({ path: '../output/playwright/demo-data/pending-production.png', fullPage: true })
  checks.push('历史工单详情直达、用料快照与待办展示')
  assert.deepEqual(errors, [])
  await writeFile('../output/playwright/demo-data/results.json', JSON.stringify({ database: 'mini_mfg_dev', batch: 'DEMO-PC-20261020', checks, errors, fullFactorySalesAmount: amount.cards.shippedSalesAmount }, null, 2))
  console.log('DEMO BROWSER PASSED ' + checks.length)
} catch (error) {
  await page.screenshot({ path: '../output/playwright/demo-data/failure.png', fullPage: true }).catch(() => {})
  const safe = String(error.message || error).split(process.env.MFG_OPERATOR_PASSWORD).join('[REDACTED]')
  console.error(safe)
  process.exitCode = 1
} finally {
  try {
    const token = await read('/auth/csrf')
    await context.request.post(base + '/api/v1/auth/logout', { headers: { 'X-CSRF-TOKEN': token.token } })
  } catch { /* Expired sessions need no additional writes. */ }
  await browser.close()
}
