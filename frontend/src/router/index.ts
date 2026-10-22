import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { navigation } from '../navigation'
const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue'), meta: { title: '登录', public: true } },
  { path: '/', component: () => import('../layouts/AppLayout.vue'), children: [
    { path: '', redirect: '/workbench' },
    { path: '/workbench', component: () => import('../views/WorkbenchView.vue'), meta: { title: '首页概览', permission: 'workbench:read' } },
    { path: '/ai-analysis', component: () => import('../views/AiAnalysisView.vue'), meta: { title: 'AI 智能分析', permission: ['erp:sales:read','wms:stock:read','mes:production:read','wms:document:read'] } },
    ...['materials','customers','suppliers','warehouses'].map(resource => ({ path: `/master/${resource}`, component: () => import('../views/MasterView.vue'), meta: { title: ({materials:'物料管理',customers:'客户管理',suppliers:'供应商',warehouses:'仓库管理'} as Record<string,string>)[resource], resource, permission: `master:${({materials:'material',customers:'customer',suppliers:'supplier',warehouses:'warehouse'} as Record<string,string>)[resource]}:read` } })),
    { path: '/mes/boms', component: () => import('../views/BomView.vue'), meta: { title: '单层 BOM', permission: 'mes:bom:read' } },
    ...([{ path: '/erp/purchases', resource: 'purchase', title: '零部件采购', permission: 'erp:purchase:read' },{ path: '/erp/sales', resource: 'sales', title: '销售订单', permission: 'erp:sales:read' },{ path: '/mes/production-orders', resource: 'production', title: '整机生产', permission: 'mes:production:read' }]).flatMap(item => [
      { path: item.path, component: () => import('../views/BusinessListView.vue'), meta: item },
      { path: `${item.path}/:id`, component: () => import('../views/BusinessDetailView.vue'), meta: { ...item, detail: true } }
    ]),
    ...['inbound','outbound','balances','ledgers'].map(stockView => ({ path: `/wms/${stockView}`, component: () => import('../views/StockView.vue'), meta: { stockView, title: ({inbound:'入库记录',outbound:'出库记录',balances:'库存余额',ledgers:'库存流水'} as Record<string,string>)[stockView], permission: stockView==='balances'?'wms:stock:read':stockView==='ledgers'?'wms:ledger:read':'wms:document:read' } })),
    ...['inbound','outbound'].map(stockView => ({ path: `/wms/${stockView}/:id`, component: () => import('../views/StockDetailView.vue'), meta: { title: '库存单详情', stockView, permission: 'wms:document:read', detail: true } })),
    ...['users','roles','logs'].map(resource => ({ path: `/system/${resource}`, component: () => import('../views/SystemView.vue'), meta: { resource, title: ({users:'用户管理',roles:'角色与权限',logs:'操作日志'} as Record<string,string>)[resource], permission: ({users:'system:user:read',roles:'system:role:read',logs:'system:log:read'} as Record<string,string>)[resource] } })),
    { path: '/403', component: () => import('../views/ErrorView.vue'), meta: { title: '无权限', error: '403' } },
    { path: '/:pathMatch(.*)*', component: () => import('../views/ErrorView.vue'), meta: { title: '页面不存在', error: '404' } }
  ] }
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async to => {
  if (to.meta.public) return true
  const auth = useAuthStore()
  try {
    if (!(await auth.load())) return { path: '/login', query: { reason: '请登录后继续访问', redirect: to.fullPath } }
    const permission = to.meta.permission as string | string[] | undefined
    if (permission && !auth.can(permission)) {
      if (to.path === '/workbench') { const first = navigation.flatMap(g => g.items).find(item => auth.can(item.permission)); if(first) return first.path }
      return '/403'
    }
  } catch { return { path: '/login', query: { reason: '服务暂不可用，请稍后重试', redirect: to.fullPath } } }
})
window.addEventListener('session-expired', () => {
  const auth = useAuthStore(); auth.clear()
  if(router.currentRoute.value.path !== '/login') void router.replace({ path: '/login', query: { reason: '登录已过期，请重新登录', redirect: router.currentRoute.value.fullPath } })
})
export default router
