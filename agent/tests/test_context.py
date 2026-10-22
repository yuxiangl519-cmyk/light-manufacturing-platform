"""Stage6: fixed-clock structured context; no model/network/database claims."""
from datetime import date
import json
import pytest
from app.context import clean_context, prepare_turn, merge_context
from app.graph import RuleWorkflow as SalesAgent
from app.config import Settings
from test_dimensions import DimensionTools, NoModel
from test_operations import Tools as StockTools
from test_evaluation import BaselineTools, BaselineModel

TODAY=date(2026,10,20)
PERIOD={'startDate':'2026-10-01','endDate':'2026-10-03'}
async def collect(message,resume=None,tools=None,model=None):
    return [e async for e in SalesAgent(Settings(),tools=tools or DimensionTools(),model=model or NoModel(),clock=lambda:TODAY).events(message,{},resume or {})]

def result(events):return next(v for k,v in events if k=='result')

def test_context_whitelist_never_keeps_raw_dialog_results_candidates_or_secrets():
    c=clean_context({**PERIOD,'metric':'sales','dimension':'customer','entityId':'11','originalMessage':'销售 13800138000 demo@example.org sk-FAKE_LONG_TEST_KEY','gatewayToken':'secret','results':{'salesAmount':'999'},'candidates':[{'id':'11','phone':'13800138000'}]})
    text=json.dumps(c,ensure_ascii=False)
    assert c['version']==1 and c['entityId']=='11'
    assert not any(w in text for w in ('13800138000','demo@example','FAKE_LONG','gatewayToken','results','candidates'))

@pytest.mark.parametrize('bad',['0','-1','9223372036854775808','not-an-id'])
def test_invalid_identity_is_dropped_not_used_for_scope(bad):
    assert 'entityId' not in clean_context({'dimension':'customer','entityId':bad})

@pytest.mark.asyncio
@pytest.mark.parametrize('message',['那上个月呢？','刚才那个客户上个月呢？'])
async def test_previous_month_keeps_confirmed_customer_and_performs_new_query(message):
    tools=DimensionTools();ctx={**PERIOD,'domain':'sales','metric':'sales','dimension':'customer','entityId':'11','lastCustomerId':'11','analysisOperation':'entity'}
    # Fixture must follow requested periods instead of reusing previous tool results.
    class Empty(DimensionTools):
        async def call(self,name,args,token):
            self.calls.append((name,args))
            from test_evaluation import metadata,zero_trend
            if name=='get_entity_sales':
                meta=metadata(args,args['dimension']);trend=zero_trend(args);trend.update(meta)
                return {'entity':{'id':args['entityId'],'name':'客户11'},'summary':{**meta,'current':{'salesAmount':'0','orderCount':'0'},'previous':{'salesAmount':'0','orderCount':'0'}},'trend':trend}
            raise AssertionError(name)
    tools=Empty();events=await collect(message,ctx,tools)
    assert events[-1][1]['status']=='completed'
    assert tools.calls==[('get_entity_sales',{'startDate':'2026-09-01','endDate':'2026-10-01','dimension':'customer','entityId':'11'})]
    assert result(events)['context']['entityId']=='11'
    assert any(k=='stage' and '沿用' in v.get('message','') and '修改' in v.get('message','') for k,v in events)

@pytest.mark.asyncio
async def test_reference_does_not_discard_explicit_unsupported_metric():
    tools=DimensionTools()
    events=await collect('刚才那个客户上个月利润如何？',{**PERIOD,'metric':'sales','lastCustomerId':'11'},tools)
    assert events[-1][1]['status']=='clarification' and not tools.calls
    assert next(v for k,v in events if k=='clarification')['context']['question']=='profit'

@pytest.mark.asyncio
async def test_just_that_customer_uses_only_known_id_not_a_model_guess():
    ctx={**PERIOD,'metric':'sales','lastCustomerId':'11','analysisOperation':'total'}
    tools=DimensionTools();events=await collect('只看刚才那个客户。',ctx,tools)
    assert events[-1][1]['status']=='completed'
    assert tools.calls[-1][1]['entityId']=='11'
    unknown=await collect('只看刚才那个客户。',{**PERIOD,'metric':'sales'})
    assert unknown[-1][1]['status']=='clarification'

@pytest.mark.asyncio
async def test_completed_customer_switch_to_global_does_not_keep_active_entity():
    ctx={**PERIOD,'metric':'sales','dimension':'customer','entityId':'11','analysisOperation':'entity'}
    events=await collect('改看全厂销售',ctx,BaselineTools(),BaselineModel())
    context=result(events)['context']
    assert 'entityId' not in context and context['lastCustomerId']=='11'

@pytest.mark.asyncio
@pytest.mark.parametrize('message',['换成原料仓。','换成原料仓库存'])
async def test_warehouse_switch_keeps_material_but_re_resolves_warehouse(message):
    tools=StockTools();ctx={'domain':'operations','operation':{'name':'get_current_stock'},'operationsArgs':{'warehouseId':'10','materialId':'21'}}
    events=await collect(message,ctx,tools)
    assert events[-1][1]['status']=='completed'
    assert tools.calls[0]==('resolve_operations_entity',{'dimension':'warehouse','query':'原料仓'})
    assert tools.calls[-1]==('get_current_stock',{'warehouseId':'11','materialId':'21'})

@pytest.mark.asyncio
async def test_serialized_pending_selection_resumes_in_a_new_graph_instance():
    initial=await collect('2026-10-01至2026-10-02客户“重名公司”采购情况如何？')
    pending=next(v['context'] for k,v in initial if k=='clarification')
    restored=json.loads(json.dumps(pending));tools=DimensionTools()
    events=await collect('选择客户ID 12',restored,tools)
    assert events[-1][1]['status']=='completed'
    assert tools.calls[0][0]=='resolve_sales_entity' and tools.calls[-1][1]['entityId']=='12'
    assert result(events)['context']['confirmedOptions']['entityId']=='12'

@pytest.mark.asyncio
async def test_model_failure_retains_resolved_new_period_for_retry():
    from app.model import AgentFailure
    class Failed(BaselineModel):
        async def plan(self,*a,**kw):raise AgentFailure('MODEL_REQUEST_FAILED','test failure')
    events=await collect('2026-10-01至2026-10-02销售',{},BaselineTools(),Failed())
    error=next(v for k,v in events if k=='error')
    assert error['context']['startDate']=='2026-10-01'
    retried=await collect('重试',json.loads(json.dumps(error['context'])),BaselineTools(),BaselineModel())
    assert retried[-1][1]['status']=='completed'

def test_new_domain_cannot_keep_previous_sales_scope_active():
    context=merge_context({**PERIOD,'metric':'sales','dimension':'customer','entityId':'11'}, {'domain':'operations','operation':{'name':'get_low_stock_alerts'},'operationsArgs':{'warehouseId':'2'}})
    assert 'entityId' not in context and 'startDate' not in context
    assert context['lastCustomerId']=='11'

def test_new_named_customer_clears_old_active_id_and_confirmation():
    c=merge_context({**PERIOD,'metric':'sales','dimension':'customer','entityId':'11','entityQuery':'旧公司','confirmedOptions':{'entityId':'11'}}, {'domain':'sales','metric':'sales','dimension':'customer','entityQuery':'新公司','analysisOperation':'entity'})
    assert 'entityId' not in c and not c.get('confirmedOptions') and c['entityQuery']=='新公司'

@pytest.mark.asyncio
async def test_warehouse_checkpoint_cannot_resurrect_previous_warehouse_before_lookup():
    tools=StockTools();events=await collect('换成原料仓',{'version':1,'domain':'operations','operation':{'name':'get_current_stock'},'operationsArgs':{'warehouseId':'10','materialId':'21'},'warehouseId':'10','materialId':'21'},tools)
    checkpoints=[v['context'] for k,v in events if k=='stage' and v.get('node')=='Context']
    assert 'warehouseId' not in checkpoints[0]
    assert checkpoints[-1]['warehouseId']=='11'
    assert tools.calls[-1][1]['warehouseId']=='11'

@pytest.mark.asyncio
async def test_warehouse_change_from_sales_requires_query_kind_clarification():
    tools=DimensionTools();events=await collect('换成原料仓',{'metric':'sales',**PERIOD},tools)
    assert events[-1][1]['status']=='clarification' and not tools.calls

def test_sensitive_resolver_query_is_not_automatically_remembered():
    c=clean_context({'entityQuery':'demo@example.org','operation':{'name':'get_current_stock'},'operationsLookup':{'field':'warehouseId','dimension':'warehouse','query':'13800138000'}})
    assert 'entityQuery' not in c and 'operationsLookup' not in c

def test_unknown_context_version_cannot_be_silently_replayed():
    assert clean_context({'version':99,'metric':'sales','entityId':'11'})=={'version':1}

def test_period_followup_keeps_ranking_limit():
    from app.dimensions import intent
    text,context,_=prepare_turn('那上个月呢',{'metric':'sales','dimension':'customer','analysisOperation':'ranking','rankingLimit':3},TODAY)
    assert intent(text,context)['limit']==3

def test_service_resume_payload_has_a_hard_byte_limit():
    from fastapi.testclient import TestClient
    from app.main import create_app
    client=TestClient(create_app(Settings(service_token='test-secret')))
    response=client.post('/v1/analyze',headers={'Authorization':'Bearer test-secret'},json={'requestId':'r','conversationId':'c','message':'继续','context':{'gatewayToken':'opaque'},'resume':{'history':'x'*4097}})
    assert response.status_code==422

@pytest.mark.asyncio
async def test_failed_new_entity_retries_that_entity_not_previous_tool_results():
    class Transient(DimensionTools):
        async def call(self,name,args,token):
            if name=='get_entity_sales' and not self.calls:
                self.calls.append((name,args));raise OSError('transient')
            return await super().call(name,args,token)
    tools=Transient();ctx={**PERIOD,'metric':'sales','dimension':'customer','entityId':'11','analysisOperation':'entity'}
    failed=await collect('客户ID 12',ctx,tools)
    saved=next(v['context'] for k,v in failed if k=='error');assert saved['entityId']=='12'
    retried=await collect('重试',json.loads(json.dumps(saved)),tools)
    assert retried[-1][1]['status']=='completed'
    assert [args['entityId'] for name,args in tools.calls]==['12','12']

@pytest.mark.asyncio
async def test_explicit_new_customer_id_does_not_reuse_completed_name_candidates():
    tools=DimensionTools()
    events=await collect('客户ID 12',{**PERIOD,'metric':'sales','dimension':'customer','entityId':'11','entityQuery':'旧公司','analysisOperation':'entity'},tools)
    assert events[-1][1]['status']=='completed'
    assert tools.calls==[('get_entity_sales',{**PERIOD,'dimension':'customer','entityId':'12'})]

@pytest.mark.asyncio
@pytest.mark.parametrize('message',['换成成品仓','换成成品仓库存'])
async def test_warehouse_switch_during_material_clarification_keeps_pending_material(message):
    class Ambiguous(StockTools):
        async def call(self,name,args,token):
            if name=='resolve_operations_entity' and args['dimension']=='material':
                self.calls.append((name,args))
                return {'candidates':[{'id':'21','code':'A','name':'重名零件'},{'id':'22','code':'B','name':'重名零件'}],'truncated':False}
            return await super().call(name,args,token)
    tools=Ambiguous();ctx={'version':1,'domain':'operations','question':'operation','operation':{'name':'get_current_stock'},'operationsArgs':{'warehouseId':'10'},'operationsLookup':{'dimension':'material','query':'重名零件','field':'materialId'}}
    events=await collect(message,ctx,tools)
    assert events[-1][1]['status']=='clarification'
    assert tools.calls[0]==('resolve_operations_entity',{'dimension':'warehouse','query':'成品仓'})
    assert tools.calls[1]==('resolve_operations_entity',{'dimension':'material','query':'重名零件'})
    assert all(name!='get_current_stock' for name,args in tools.calls)
