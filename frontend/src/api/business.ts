export type Resource = 'purchase' | 'sales' | 'production'
export const businessConfig = {
  purchase: { title: '零部件采购', path: '/purchase-orders', route: '/erp/purchases', permission: 'erp:purchase', partner: 'supplier', statuses: ['DRAFT','CONFIRMED','RECEIVED','CANCELLED'] },
  sales: { title: '整机销售', path: '/sales-orders', route: '/erp/sales', permission: 'erp:sales', partner: 'customer', statuses: ['DRAFT','CONFIRMED','SHIPPED','CANCELLED'] },
  production: { title: '整机生产工单', path: '/production-orders', route: '/mes/production-orders', permission: 'mes:production', partner: '', statuses: ['DRAFT','READY_TO_ISSUE','IN_PRODUCTION','PENDING_INBOUND','COMPLETED','CANCELLED'] },
} as const
const kindLabels:Record<string,string> = { PURCHASE_RECEIPT:'采购入库', PRODUCTION_ISSUE:'领料出库', PRODUCTION_RECEIPT:'整机入库', SALES_SHIPMENT:'销售出库' }
export const kindLabel = (kind: string) => kindLabels[kind] || kind
export function primaryAction(resource: Resource, status: string) {
  if (status === 'DRAFT') return { key:'confirm', label:'确认单据' }
  if (resource === 'purchase' && status === 'CONFIRMED') return { key:'receive', label:'全量收货' }
  if (resource === 'sales' && status === 'CONFIRMED') return { key:'ship', label:'全量发货' }
  if (resource === 'production' && status === 'READY_TO_ISSUE') return { key:'issue', label:'按BOM一次性领料' }
  if (resource === 'production' && status === 'IN_PRODUCTION') return { key:'report', label:'一次性组装报工' }
  return null
}
