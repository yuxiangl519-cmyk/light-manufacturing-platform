"""Read-only independent numeric oracles, and a test-only read role/user."""
import asyncio,json,os,uuid
from pathlib import Path
from evaluation.dimension_checks import query,ranking,detail,aggregate
from evaluation.live import oracle
import httpx

async def main():
    assert os.getenv('AI_LIVE_TEST_DATABASE')=='mini_mfg_test'
    assert '/mini_mfg_test?' in os.environ['MFG_TEST_DB_URL']
    start,end='2026-10-01','2026-10-03'
    ranks=ranking(start,end,'customer',5)
    assert ranks,'Fixed test period needs existing shipped orders; no automatic data seeding'
    raw=query("SELECT id FROM warehouse WHERE code='DM261020-RAW'")
    assert len(raw)==1,'Existing isolated demo warehouse required; never seed development data'
    wid=raw[0][0]
    low=query('SELECT m.id,COALESCE(b.quantity,0),m.min_stock FROM material m LEFT JOIN stock_balance b ON b.material_id=m.id AND b.warehouse_id='+wid+' WHERE m.enabled=1 AND COALESCE(b.quantity,0)<m.min_stock ORDER BY m.code,m.id LIMIT 20')
    orders=query("SELECT id FROM production_order WHERE status='READY_TO_ISSUE' ORDER BY id DESC LIMIT 1")
    assert orders,'An existing confirmed test production order is required'
    oid=orders[0][0]
    shortage=query('SELECT s.material_id,s.required_quantity,COALESCE(b.quantity,0),GREATEST(s.required_quantity-COALESCE(b.quantity,0),0) FROM production_material_snapshot s JOIN production_order o ON o.id=s.production_order_id LEFT JOIN stock_balance b ON b.material_id=s.material_id AND b.warehouse_id=o.issue_warehouse_id WHERE o.id='+oid+' ORDER BY s.material_id')
    totals,_=oracle(start,end)
    fixture={'startDate':start,'endDate':end,'sales':totals[0],'ranking':ranks,'customer':aggregate(detail(start,end,'customer',ranks[0]['id'])),'warehouseId':wid,'low':low,'orderId':oid,'shortage':shortage}
    async with httpx.AsyncClient(base_url='http://127.0.0.1:18081',trust_env=False,timeout=30) as c:
        csrf=(await c.get('/api/v1/auth/csrf')).json()['token']
        r=await c.post('/api/v1/auth/login',headers={'X-CSRF-TOKEN':csrf},json={'username':'admin','password':os.environ['MFG_ADMIN_PASSWORD']});assert r.status_code==200,'Test admin login failed'
        headers={'X-CSRF-TOKEN':(await c.get('/api/v1/auth/csrf')).json()['token']}
        async def post(path,data):
            r=await c.post('/api/v1/'+path,headers=headers,json=data);assert r.status_code==201,'Test fixture create failed';return r.json()
        code='S7_'+uuid.uuid4().hex[:10].upper()
        role=await post('system/roles',{'code':code,'name':'AI验收只读库存','description':'mini_mfg_test only'})
        r=await c.put('/api/v1/system/roles/'+role['id']+'/permissions',headers=headers,json={'version':role['version'],'permissionCodes':['wms:stock:read']});assert r.status_code==200
        username='s7-'+uuid.uuid4().hex[:10];password=uuid.uuid4().hex
        await post('system/users',{'username':username,'displayName':'AI验收只读库存','initialPassword':password,'enabled':True,'roleIds':[role['id']]})
    out=Path('output/ai-acceptance');out.mkdir(parents=True,exist_ok=True)
    (out/'fixtures.json').write_text(json.dumps(fixture,ensure_ascii=False),encoding='utf-8')
    # Ignored local handoff, never copied into public reports or command arguments.
    (out/'private-user.json').write_text(json.dumps({'username':username,'password':password}),encoding='utf-8')
    print('Prepared independent numeric oracles and isolated read-only user; no business writes')

if __name__=='__main__':asyncio.run(main())
