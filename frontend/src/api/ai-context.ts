/** Display/route helpers only; browser context is never sent back as authorization. */
export function conversationId(value: unknown): string {
  return typeof value==='string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value)<=9223372036854775807n ? value : ''
}
export function scopeText(context: Record<string,unknown> | undefined): string {
  if(!context) return ''
  const fields: string[]=[]
  if(typeof context.startDate==='string' && typeof context.endDate==='string') fields.push(`日期 ${context.startDate} 至 ${context.endDate}（结束日排除，Asia/Shanghai）`)
  if(context.metric==='sales') fields.push(`指标：含税已发货销售额、订单数；${context.dateBasis==='shipped_at'?'按实际发货时间':'按订单创建时间'}`)
  if(context.dimension==='customer' || context.dimension==='product') {
    const id=conversationId(context.entityId)
    if(id) fields.push(`${context.dimension==='customer'?'客户':'商品'}ID ${id}`)
  }
  for(const [key,label] of [['warehouseId','仓库'],['materialId','物料'],['productionOrderId','工单']]) {
    const id=conversationId(context[key!]);if(id) fields.push(`${label}ID ${id}`)
  }
  if(context.domain==='operations') fields.push('当前库存/工单查询，不沿用销售日期')
  return fields.join('；')
}
