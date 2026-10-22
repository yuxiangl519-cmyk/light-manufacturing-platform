"""Real graph, deterministic adapters: no live model/database claims."""
from datetime import date
import pytest
from app.graph import RuleWorkflow as SalesAgent
from app.config import Settings
from app.tools import validate_call

class NoModel:
    async def plan(self,*args): raise AssertionError('Operations must not invoke model')
    async def reflect(self,*args): raise AssertionError('Operations must not invoke model')

class Tools:
    def __init__(self): self.calls=[]
    async def call(self,name,args,token):
        self.calls.append((name,args))
        if name=='resolve_operations_entity':
            ids=['11','12'] if args['query']=='重名仓' else ['11']
            return {'candidates':[{'id':i,'code':'W'+i,'name':args['query']} for i in ids], 'truncated':False}
        common={'asOf':'2026-10-20T01:00:00Z','timezone':'Asia/Shanghai','source':'WMS/受控查询','total':'1','truncated':False}
        if name in ('get_current_stock','get_low_stock_alerts'):
            return {**common,'rows':[{'warehouseId':'11','warehouseName':'原料仓','materialId':'21','materialCode':'CPU','materialName':'处理器','unit':'个','quantity':'0','minStock':'5','lowStock':True}]}
        if name=='get_production_shortage':
            return {**common,'productionOrderId':args['productionOrderId'],'status':'READY_TO_ISSUE','orderNo':'MO-1','bomRevision':1,'items':[{'materialId':'21','materialCode':'CPU','materialName':'处理器','unit':'个','requiredQuantity':'8','availableQuantity':'3','shortageQuantity':'5'}]}
        return {**common,'rows':[{'id':'31','documentNo':'ST-1','warehouseName':'成品仓','sourceNo':'MO-1','items':[{'materialId':'41','materialCode':'PC','materialName':'标准办公台式机','unit':'台','quantity':'2'}]}]}

async def collect(tools,message,resume=None):
    service=SalesAgent(Settings(),model=NoModel(),tools=tools,clock=lambda:date(2026,10,20))
    return [e async for e in service.events(message,{},resume or {})]

@pytest.mark.asyncio
@pytest.mark.parametrize('question,name',[
    ('原料仓哪些物料库存不足？','get_low_stock_alerts'),
    ('仓库ID 11 物料ID 21 当前库存','get_current_stock'),
    ('工单ID 11还缺哪些零部件？','get_production_shortage'),
    ('目前有哪些成品等待入库？','get_pending_production_receipts')])
async def test_operations_route_without_sales_dates_or_model(question,name):
    tools=Tools();events=await collect(tools,question)
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-1][0]==name
    content=next(v['content'] for k,v in events if k=='result')
    assert 'Asia/Shanghai' in content and '2026-10-20' in content
    assert '未预留' in content and '待入库' in content

@pytest.mark.asyncio
async def test_ambiguous_warehouse_requires_bound_candidate_then_revalidates():
    tools=Tools();events=await collect(tools,'重名仓哪些物料库存不足？')
    pending=next(v for k,v in events if k=='clarification')
    assert events[-1][1]['status']=='clarification'
    assert not any(n=='get_low_stock_alerts' for n,a in tools.calls)
    events=await collect(tools,'选择ID 12',pending['context'])
    assert events[-1][1]['status']=='completed'
    assert tools.calls[-1][1]['warehouseId']=='12'
    assert sum(n=='resolve_operations_entity' for n,a in tools.calls)==2

@pytest.mark.asyncio
async def test_missing_work_order_clarifies_then_continues():
    tools=Tools();events=await collect(tools,'这张工单还缺哪些零部件？')
    pending=next(v for k,v in events if k=='clarification')
    assert tools.calls==[]
    events=await collect(tools,'工单ID 11',pending['context'])
    assert events[-1][1]['status']=='completed'
    assert tools.calls[-1]==('get_production_shortage',{'productionOrderId':'11'})

@pytest.mark.asyncio
@pytest.mark.parametrize('question',['昨天原料仓库存是多少？','2026-10-01库存','上个月 仓库ID 11 物料ID 21 库存','2024年 仓库ID 11 物料ID 21 库存','请执行成品入库','帮我创建采购单补料'])
async def test_historical_stock_and_writes_do_not_call_tools(question):
    tools=Tools();events=await collect(tools,question)
    assert tools.calls==[]
    assert events[-1][1]['status']=='clarification'

def test_model_cannot_choose_operational_scope_and_dates_rejected():
    with pytest.raises(ValueError):validate_call('get_current_stock',{'warehouseId':'11'}, {})
    with pytest.raises(ValueError):validate_call('get_current_stock',{'startDate':'2026-10-01'}, {},scope={'operation':'get_current_stock'})

@pytest.mark.asyncio
async def test_missing_warehouse_name_reply_resumes_current_query():
    tools=Tools();events=await collect(tools,'当前库存')
    pending=next(v for k,v in events if k=='clarification')
    events=await collect(tools,'原料仓',pending['context'])
    assert events[-1][1]['status']=='completed'
    assert tools.calls[-1]==('get_current_stock',{'warehouseId':'11'})

@pytest.mark.asyncio
async def test_unparsed_material_does_not_silently_return_all_stock():
    tools=Tools();events=await collect(tools,'原料仓的CPU库存')
    assert events[-1][1]['status']=='clarification'
    assert not any(n=='get_current_stock' for n,a in tools.calls)

@pytest.mark.asyncio
async def test_pending_named_warehouse_uses_document_permission_lookup():
    tools=Tools();events=await collect(tools,'成品仓有哪些成品等待入库？')
    assert events[-1][1]['status']=='completed'
    assert tools.calls[0]==('resolve_operations_entity',{'dimension':'receiptWarehouse','query':'成品仓'})

@pytest.mark.asyncio
@pytest.mark.parametrize('bad_value',['NaN','-1','1.5','Infinity'])
async def test_invalid_quantity_never_reports_success(bad_value):
    class InvalidTools(Tools):
        async def call(self,name,args,token):
            value=await super().call(name,args,token)
            value['rows'][0]['quantity']=bad_value
            return value
    events=await collect(InvalidTools(),'仓库ID 11 物料ID 21 当前库存')
    assert not any(k=='result' for k,v in events)
    assert next(v for k,v in events if k=='error')['code']=='INVALID_ERP_DATA'

@pytest.mark.asyncio
async def test_tool_permission_failure_stays_error_not_empty_success():
    import httpx
    class Denied:
        async def call(self,*args):
            response=httpx.Response(403,request=httpx.Request('POST','http://test/internal/ai/tools/get_current_stock'))
            response.raise_for_status()
    events=await collect(Denied(),'仓库ID 11 当前库存')
    assert next(v for k,v in events if k=='error')['code']=='FORBIDDEN'
    assert events[-1][1]['status']=='error'

@pytest.mark.asyncio
async def test_already_issued_order_shows_balance_without_repeated_shortage():
    class Issued(Tools):
        async def call(self,name,args,token):
            value=await super().call(name,args,token)
            value.update(needsIssue=False,items=[],notice='已领料，不再重复补料',currentStock=[{'materialId':'21','materialName':'CPU','unit':'个','requiredQuantity':'8','availableQuantity':'0'}])
            return value
    events=await collect(Issued(),'工单ID 11还缺哪些零部件？')
    content=next(v['content'] for k,v in events if k=='result')
    assert '| 21 / CPU | 8 | 0 | 不适用（已领料） | 个 |' in content
    assert events[-1][1]['status']=='completed'

@pytest.mark.asyncio
@pytest.mark.parametrize('question,name',[
    ('原料仓有哪些缺货物料？','get_low_stock_alerts'),
    ('有哪些整机还没入库？','get_pending_production_receipts'),
    ('演示·电脑零部件仓哪些物料库存不足？','get_low_stock_alerts'),
    ('仓库「DEMO-RAW」当前库存','get_current_stock'),
    ('原料仓还剩多少库存？','get_current_stock'),
    ('原料仓物料「CPU」库存','get_current_stock')])
async def test_real_world_operational_expressions(question,name):
    tools=Tools();events=await collect(tools,question)
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-1][0]==name
    if 'CPU' in question:assert tools.calls[-1][1]=={'warehouseId':'11','materialId':'11'}

@pytest.mark.asyncio
async def test_correct_explicit_warehouse_id_replaces_failed_name_lookup():
    class Missing(Tools):
        async def call(self,name,args,token):
            if name=='resolve_operations_entity':
                self.calls.append((name,args));return {'candidates':[],'truncated':False}
            return await super().call(name,args,token)
    tools=Missing();events=await collect(tools,'不存在仓哪些物料库存不足？')
    pending=next(v for k,v in events if k=='clarification')
    events=await collect(tools,'仓库ID 11',pending['context'])
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-1]==('get_low_stock_alerts',{'warehouseId':'11'})

@pytest.mark.asyncio
async def test_warehouse_name_is_not_greedily_extended_by_stock_word():
    tools=Tools();await collect(tools,'零部件仓库存预警')
    assert tools.calls[0][1]['query']=='零部件仓'

def test_pending_operation_does_not_hijack_new_sales_question():
    from app.operations import intent
    assert intent('最近30天销售情况',{'question':'operation','operation':{'name':'get_current_stock','request':'当前库存'}}) is None

@pytest.mark.asyncio
async def test_corrected_name_replaces_failed_name_lookup():
    class Missing(Tools):
        async def call(self,name,args,token):
            if name=='resolve_operations_entity' and args['query']=='不存在仓':
                self.calls.append((name,args));return {'candidates':[],'truncated':False}
            return await super().call(name,args,token)
    tools=Missing();events=await collect(tools,'不存在仓哪些物料库存不足？')
    pending=next(v for k,v in events if k=='clarification')
    events=await collect(tools,'原料仓',pending['context'])
    assert events[-1]==('done',{'status':'completed'})

@pytest.mark.asyncio
async def test_combined_scope_continues_second_lookup_after_warehouse_selection():
    tools=Tools();events=await collect(tools,'重名仓物料「CPU」库存')
    pending=next(v for k,v in events if k=='clarification')
    events=await collect(tools,'选择ID 12',pending['context'])
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-1]==('get_current_stock',{'warehouseId':'12','materialId':'11'})

@pytest.mark.asyncio
async def test_completed_stock_query_can_follow_up_with_another_warehouse():
    tools=Tools();events=await collect(tools,'仓库ID 11 物料ID 21 当前库存')
    context=next(v['context'] for k,v in events if k=='result')
    events=await collect(tools,'那成品仓呢？',context)
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-1]==('get_current_stock',{'warehouseId':'11','materialId':'21'})
    assert tools.calls[-2][1]['query']=='成品仓'
