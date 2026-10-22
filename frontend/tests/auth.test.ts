import {beforeEach,expect,it,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
vi.mock('../src/api/http',()=>({api:{get:vi.fn()},clearCsrf:vi.fn()}))
import {api,clearCsrf} from '../src/api/http'
import {useAuthStore} from '../src/stores/auth'
import {navigation} from '../src/navigation'
beforeEach(()=>{setActivePinia(createPinia());vi.clearAllMocks()})
it('SPA发现会话过期时同时清理旧CSRF，允许下一次登录获取新令牌',async()=>{
 vi.mocked(api.get).mockRejectedValue({response:{status:401}})
 const auth=useAuthStore()
 expect(await auth.load()).toBe(false)
 expect(auth.user).toBeNull()
 expect(clearCsrf).toHaveBeenCalledOnce()
})
it('库存单独权限可见AI入口，默认仍拒绝其他业务权限',async()=>{
 vi.mocked(api.get).mockResolvedValue({data:{id:'1',username:'stock',displayName:'库存查询',permissions:['wms:stock:read']}} as any)
 const auth=useAuthStore();await auth.load()
 const ai=navigation.flatMap(g=>g.items).find(i=>i.path==='/ai-analysis')!
 expect(auth.can(ai.permission)).toBe(true)
 expect(auth.can('erp:sales:read')).toBe(false)
 expect(auth.can('mes:production:read')).toBe(false)
 expect(auth.can([])).toBe(false)
 auth.clear();expect(auth.can(ai.permission)).toBe(false)
})
