import { describe,it,expect } from 'vitest'
import { money,positiveInteger,nonNegativeInteger,validMoney,sourceRoute } from '../src/utils/format'
import { primaryAction } from '../src/api/business'
describe('精确业务格式与动作',()=>{
  it('不把大金额转成浮点数',()=>{expect(money('9999999999999999.99')).toBe('9,999,999,999,999,999.99');expect(money('0.10')).toBe('0.10')})
  it('数量与价格使用严格十进制字符串',()=>{for(const value of ['0','-1','1.2','1e2','1000000000000'])expect(positiveInteger(value)).toBe(false);expect(nonNegativeInteger('0')).toBe(true);expect(validMoney('01.00')).toBe(false);expect(validMoney('1.001')).toBe(false);expect(validMoney('0.00')).toBe(true)})
  it('只有正确状态提供专用下一步',()=>{expect(primaryAction('production','IN_PRODUCTION')?.key).toBe('report');expect(primaryAction('sales','SHIPPED')).toBeNull();expect(primaryAction('purchase','CONFIRMED')?.key).toBe('receive')})
  it('来源路由附带明确查看权限',()=>{expect(sourceRoute('PRODUCTION_ORDER','12')).toEqual({path:'/mes/production-orders/12',permission:'mes:production:read'});expect(sourceRoute('UNKNOWN','1')).toBeNull()})
})
