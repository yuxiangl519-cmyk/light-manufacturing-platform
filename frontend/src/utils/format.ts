export function dateTime(value?: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '—' }
export function money(value?: string | null) {
  if (value == null) return '—'
  const [integer = '0', fraction = '00'] = value.split('.')
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + fraction.padEnd(2, '0')
}
const labels: Record<string, string> = { DRAFT: '草稿', CONFIRMED: '已确认', RECEIVED: '已收货', READY_TO_ISSUE: '待领料', IN_PRODUCTION: '生产中', PENDING_INBOUND: '待入库', COMPLETED: '已完成', SHIPPED: '已发货', CANCELLED: '已取消', PENDING: '待执行', EXECUTED: '已执行', RAW_MATERIAL: '原料', FINISHED_GOOD: '成品', PURCHASE_RECEIPT: '采购入库', PRODUCTION_ISSUE: '生产领料', PRODUCTION_RECEIPT: '整机入库', SALES_SHIPMENT: '销售出库' }
export function statusLabel(value: string) { return labels[value] || value }
export function sourceRoute(type: string, id: string) { const resources: Record<string, [string, string]> = { PURCHASE_ORDER: ['/erp/purchases', 'erp:purchase:read'], SALES_ORDER: ['/erp/sales', 'erp:sales:read'], PRODUCTION_ORDER: ['/mes/production-orders', 'mes:production:read'] }; const route = resources[type]; return route ? { path: `${route[0]}/${id}`, permission: route[1] } : null }
export function positiveInteger(value: string) { return /^[1-9]\d{0,11}$/.test(value) }
export function nonNegativeInteger(value: string) { return /^(0|[1-9]\d{0,11})$/.test(value) }
export function validMoney(value: string) { return /^(0|[1-9]\d{0,15})(\.\d{1,2})?$/.test(value) }
