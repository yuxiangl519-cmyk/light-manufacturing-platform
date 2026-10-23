import {expect,it} from 'vitest'
import {conversationId,scopeText} from './ai-context'
it('refresh stores only a valid conversation URL id, never context or credentials',()=>{
  expect(conversationId('11')).toBe('11')
  for(const value of ['0','-1','9223372036854775808',['11'],null,'11?user=2'])expect(conversationId(value)).toBe('')
})
it('context summary separates date, metric and backend scope ids',()=>{
  const text=scopeText({startDate:'2026-10-01',endDate:'2026-10-03',metric:'sales',dimension:'customer',entityId:'11',phone:'private'})
  expect(text).toContain('Asia/Shanghai');expect(text).toContain('客户ID 11');expect(text).not.toContain('private')
  expect(scopeText({domain:'operations',warehouseId:'2',materialId:'3'})).toContain('不沿用销售日期')
  expect(scopeText({metric:'sales',dateBasis:'shipped_at'})).toContain('按实际发货时间')
  expect(scopeText({metric:'sales'})).toContain('按订单创建时间')
})
