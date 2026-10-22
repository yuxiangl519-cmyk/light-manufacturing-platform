"""Production model-first graph with scripted model/tools; never live verification."""
import json
from datetime import date
import pytest
from app.graph import SalesAgent
from app.config import Settings
from test_operations import Tools as StockTools
from test_dimensions import DimensionTools

def call(name,args=None,id='c1'):
    return {'role':'assistant','content':None,'tool_calls':[{'id':id,'type':'function','function':{'name':name,'arguments':json.dumps(args or {})}}]}
def final(action='answer',**kwargs):return {'role':'assistant','content':json.dumps({'action':action,**kwargs},ensure_ascii=False)}
class Model:
    def __init__(self,*turns):self.turns=list(turns);self.inputs=[]
    async def decide(self,messages):
        self.inputs.append(json.loads(json.dumps(messages)))
        assert self.turns,'Model should terminate within the script'
        return self.turns.pop(0)
async def run(message,model,tools=None,resume=None,settings=None):
    return [e async for e in SalesAgent(settings or Settings(),model=model,tools=tools or StockTools(),clock=lambda:date(2026,10,20)).events(message,{'gatewayToken':'private-ticket'},resume or {})]
def result(events):return next(v for k,v in events if k=='result')

@pytest.mark.asyncio
async def test_inventory_is_model_first_and_results_return_to_model_with_call_id():
    model=Model(call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    tools=StockTools();events=await run('仓库ID 11当前库存',model,tools)
    assert len(model.inputs)==2 and tools.calls==[('get_current_stock',{'warehouseId':'11'})]
    tool=next(m for m in model.inputs[1] if m['role']=='tool')
    assert tool['tool_call_id']=='c1' and json.loads(tool['content'])['ok']
    assert 'private-ticket' not in json.dumps(model.inputs)
    assert events[-1]==('done',{'status':'completed'}) and '当前库存' in result(events)['content']

@pytest.mark.asyncio
async def test_model_can_clarify_without_calling_any_business_tool():
    model=Model(final('clarify',question='dates',content='需要分析哪个日期范围？'))
    tools=StockTools();events=await run('经营怎么样',model,tools)
    assert len(model.inputs)==1 and not tools.calls and events[-1][1]['status']=='clarification'

@pytest.mark.asyncio
async def test_no_fabricated_final_numbers_without_verified_evidence():
    events=await run('销售怎么样',Model(final(content='销售额999999元',reportIds=['invented'])))
    assert not any(k=='result' for k,v in events) and events[-1][1]['status']=='error'

@pytest.mark.asyncio
@pytest.mark.parametrize('name,args',[('execute_sql',{'sql':'SELECT secret'}),('get_current_stock',{'warehouseId':'11','userId':'1'}),('get_current_stock',{'warehouseId':'999'}),('get_sales_summary',{'startDate':'2020-01-01','endDate':'2020-01-02'})])
async def test_model_cannot_expand_tools_identity_ids_or_dates(name,args):
    tools=StockTools();model=Model(call(name,args),final('clarify',question='operation',content='请补充有效范围。'))
    events=await run('仓库ID 11当前库存',model,tools)
    assert not tools.calls and not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_model_failure_never_falls_back_to_rule_route():
    from app.model import AgentFailure
    class Failed:
        async def decide(self,messages):raise AgentFailure('MODEL_REQUEST_FAILED','模型调用失败')
    tools=StockTools();events=await run('仓库ID 11当前库存',Failed(),tools)
    assert not tools.calls and next(v for k,v in events if k=='error')['code']=='MODEL_REQUEST_FAILED'

@pytest.mark.asyncio
async def test_loop_bound_does_not_emit_unapproved_report():
    model=Model(*[call('get_current_stock',{'warehouseId':'11'},'c'+str(i)) for i in range(9)])
    tools=StockTools();events=await run('仓库ID 11当前库存',model,tools,settings=Settings(max_tool_calls=2))
    assert len(tools.calls)<=2 and not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_customer_uses_real_backend_candidate_then_model_chooses_next_call():
    model=Model(call('resolve_sales_entity',{'dimension':'customer','query':'唯一公司'}),call('get_entity_sales',{'dimension':'customer','entityId':'11'},'c2'),final(reportIds=['r1']))
    tools=DimensionTools();events=await run('2026-10-01至2026-10-02客户唯一公司采购情况',model,tools)
    assert len(model.inputs)==3 and events[-1][1]['status']=='completed'
    assert result(events)['context']['confirmedOptions']['entityId']=='11'

@pytest.mark.asyncio
async def test_ambiguous_candidate_never_automatically_binds_model_selected_id():
    model=Model(call('resolve_sales_entity',{'dimension':'customer','query':'重名公司'}),call('get_entity_sales',{'dimension':'customer','entityId':'12'},'c2'),final('clarify',question='entity',content='请明确选择客户。'))
    tools=DimensionTools();events=await run('2026-10-01至2026-10-02客户重名公司采购',model,tools)
    assert [n for n,a in tools.calls]==['resolve_sales_entity'] and events[-1][1]['status']=='clarification'

@pytest.mark.asyncio
async def test_refresh_selection_requeries_same_candidates_before_entity_query():
    ctx={'version':1,'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales','question':'entity','dimension':'customer','entityQuery':'重名公司'}
    model=Model(call('resolve_sales_entity',{'dimension':'customer','query':'重名公司'}),call('get_entity_sales',{'dimension':'customer','entityId':'12'},'c2'),final(reportIds=['r1']))
    tools=DimensionTools();events=await run('选择客户ID 12',model,tools,ctx)
    assert [n for n,a in tools.calls]==['resolve_sales_entity','get_entity_sales']
    assert events[-1][1]['status']=='completed'

@pytest.mark.asyncio
async def test_sales_results_are_given_back_before_model_finishes():
    from test_evaluation import BaselineTools
    model=Model(call('get_sales_summary'),call('get_sales_trend',id='c2'),final(reportIds=['r1']))
    tools=BaselineTools();events=await run('2026-10-01至2026-10-02销售分析',model,tools)
    assert len(model.inputs)==3 and '120.01' in result(events)['content']

@pytest.mark.asyncio
async def test_invalid_date_cannot_reuse_old_context_or_accept_model_dates():
    model=Model(call('get_sales_summary'),final('clarify',question='dates',content='请提供有效日期。'))
    tools=StockTools();events=await run('2025-02-29销售',model,tools,{'startDate':'2026-10-01','endDate':'2026-10-03'})
    assert not tools.calls and events[-1][1]['status']=='clarification'
    assert 'startDate' not in next(v['context'] for k,v in events if k=='clarification')

@pytest.mark.asyncio
async def test_cancel_during_model_decision_never_executes_tools():
    import asyncio
    started=asyncio.Event()
    class Slow:
        async def decide(self,messages):started.set();await asyncio.Event().wait()
    tools=StockTools();task=asyncio.create_task(run('当前库存',Slow(),tools))
    await started.wait();task.cancel()
    with pytest.raises(asyncio.CancelledError):await task
    assert not tools.calls

@pytest.mark.asyncio
async def test_changed_warehouse_cannot_reuse_previous_verified_report():
    model=Model(call('get_current_stock',{'warehouseId':'11'}),call('get_current_stock',{'warehouseId':'12'},'c2'),final(reportIds=['r1']))
    events=await run('仓库ID 11和仓库ID 12当前库存',model)
    assert not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_date_without_metric_cannot_be_assumed_to_mean_sales():
    model=Model(call('get_sales_summary'),final('clarify',question='metric',content='是否采用已发货销售额和订单数？'))
    tools=StockTools();events=await run('2026-10-01至2026-10-02经营怎么样',model,tools)
    assert not tools.calls and events[-1][1]['status']=='clarification'

@pytest.mark.asyncio
async def test_lookup_clarification_persists_operation_and_query_not_candidates():
    model=Model(call('resolve_operations_entity',{'operation':'get_low_stock_alerts','dimension':'warehouse','query':'重名仓'}),final('clarify',question='operation',content='请明确仓库。'))
    events=await run('重名仓哪些物料库存不足',model)
    saved=next(v['context'] for k,v in events if k=='clarification')
    assert saved['operation']['name']=='get_low_stock_alerts' and saved['operationsLookup']['query']=='重名仓' and 'candidates' not in saved

@pytest.mark.asyncio
async def test_wrong_warehouse_in_tool_result_is_not_verified():
    model=Model(call('get_current_stock',{'warehouseId':'12'}),final(reportIds=['r1']))
    events=await run('仓库ID 12当前库存',model)
    assert not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_partial_failure_must_reach_model_and_cannot_end_as_complete_report():
    from test_evaluation import BaselineTools
    class Partial(BaselineTools):
        async def call(self,name,args,token):
            if name=='get_sales_trend':raise OSError('test unavailable')
            return await super().call(name,args,token)
    model=Model(call('get_sales_summary'),call('get_sales_trend',id='c2'),final('clarify',question='evidence',content='趋势尚未完成，是否重试？'))
    events=await run('2026-10-01至2026-10-02销售',model,Partial())
    assert events[-1][1]['status']=='clarification'
    assert any('"ok": false' in m.get('content','') for m in model.inputs[-1] if m['role']=='tool')

@pytest.mark.asyncio
async def test_candidate_selection_cannot_skip_backend_revalidation():
    ctx={'version':1,'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales','question':'entity','dimension':'customer','entityQuery':'重名公司'}
    model=Model(call('get_entity_sales',{'dimension':'customer','entityId':'12'}),final('clarify',question='entity',content='请重新确认对象。'))
    tools=DimensionTools();await run('选择客户ID 12',model,tools,ctx)
    assert not tools.calls

@pytest.mark.asyncio
async def test_explicit_new_id_removes_old_active_id_from_allowed_scope():
    ctx={'version':1,'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales','dimension':'customer','entityId':'11','lastCustomerId':'11'}
    model=Model(call('get_entity_sales',{'dimension':'customer','entityId':'11'}),final('clarify',question='entity',content='请确认对象。'))
    tools=DimensionTools();await run('客户ID 12',model,tools,ctx)
    assert not tools.calls

@pytest.mark.asyncio
async def test_new_ambiguous_lookup_cannot_fall_back_to_previous_warehouse():
    ctx={'version':1,'domain':'operations','operation':{'name':'get_current_stock'},'operationsArgs':{'warehouseId':'11'},'warehouseId':'11'}
    model=Model(call('resolve_operations_entity',{'operation':'get_current_stock','dimension':'warehouse','query':'重名仓'}),call('get_current_stock',{'warehouseId':'11'},'c2'),final('clarify',question='operation',content='请明确仓库。'))
    tools=StockTools();await run('换成重名仓',model,tools,ctx)
    assert [n for n,a in tools.calls]==['resolve_operations_entity']

@pytest.mark.asyncio
async def test_old_warehouse_report_cannot_answer_explicit_customer_question():
    ctx={'version':1,'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales','warehouseId':'11'}
    model=Model(call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    events=await run('客户ID 12销售情况',model,resume=ctx)
    assert not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_equivalent_template_success_clears_only_corresponding_failed_evidence():
    from test_evaluation import BaselineTools
    class Partial(BaselineTools):
        async def call(self,name,args,token):
            if name=='get_sales_trend':raise OSError('test unavailable')
            return await super().call(name,args,token)
    model=Model(call('get_sales_summary'),call('get_sales_trend',id='c2'),call('execute_safe_query',{'templateId':'sales_trend'},'c3'),final(reportIds=['r1']))
    events=await run('2026-10-01至2026-10-02销售',model,Partial())
    assert events[-1][1]['status']=='completed' and '120.01' in result(events)['content']

@pytest.mark.asyncio
async def test_date_clarification_preserves_structured_customer_request_across_instances():
    model=Model(final('clarify',question='dates',content='需要哪个日期范围？',conditions={'domain':'sales','metric':'sales','dimension':'customer','analysisOperation':'entity','entityQuery':'唯一公司'}))
    events=await run('分析客户唯一公司销售情况',model,DimensionTools())
    ctx=next(v['context'] for k,v in events if k=='clarification')
    assert ctx['metric']=='sales' and ctx['entityQuery']=='唯一公司'
    next_model=Model(call('resolve_sales_entity',{'dimension':'customer','query':'唯一公司'}),call('get_entity_sales',{'dimension':'customer','entityId':'11'},'c2'),final(reportIds=['r1']))
    following=await run('2026-10-01至2026-10-02',next_model,DimensionTools(),json.loads(json.dumps(ctx)))
    assert following[-1][1]['status']=='completed'
    assert json.loads(next_model.inputs[0][0]['content'])['context']['entityQuery']=='唯一公司'

@pytest.mark.asyncio
async def test_disappeared_selection_must_not_be_replaced_by_remaining_unique_candidate():
    ctx={'version':1,'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales','question':'entity','dimension':'customer','entityQuery':'唯一公司'}
    model=Model(call('resolve_sales_entity',{'dimension':'customer','query':'唯一公司'}),call('get_entity_sales',{'dimension':'customer','entityId':'11'},'c2'),final(reportIds=['r1']))
    tools=DimensionTools();events=await run('选择客户ID 12',model,tools,ctx)
    assert [n for n,a in tools.calls]==['resolve_sales_entity'] and not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_unverified_final_is_returned_to_model_for_bounded_repair():
    model=Model(final(reportIds=['r1']),call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    events=await run('仓库ID 11当前库存',model)
    assert events[-1][1]['status']=='completed' and len(model.inputs)==3
    assert 'UNVERIFIED_MODEL_ANSWER' in json.dumps(model.inputs[1])

@pytest.mark.asyncio
async def test_model_failure_retry_keeps_only_bounded_redacted_original_request():
    from app.model import AgentFailure
    class Failed:
        async def decide(self,messages):raise AgentFailure('MODEL_REQUEST_FAILED','模型调用失败')
    events=await run('2026-10-01至2026-10-02全厂销售分析',Failed())
    ctx=next(v['context'] for k,v in events if k=='error')
    assert ctx['retryRequest']=='2026-10-01至2026-10-02全厂销售分析'
    from test_evaluation import BaselineTools
    model=Model(call('get_sales_summary'),call('get_sales_trend',id='c2'),final(reportIds=['r1']))
    following=await run('重试',model,BaselineTools(),ctx)
    assert following[-1][1]['status']=='completed' and 'retryRequest' not in result(following)['context']

@pytest.mark.asyncio
async def test_native_model_protocol_uses_auto_choice_and_all_existing_tools(monkeypatch):
    from app.model import ModelClient
    from app.tools import TOOL_NAMES
    captured={}
    async def complete(_,messages,**kwargs):captured.update(messages=messages,**kwargs);return final('clarify',question='dates',content='请补充日期。')
    monkeypatch.setattr(ModelClient,'complete',complete)
    await ModelClient(Settings()).decide([{'role':'user','content':'question'}])
    assert captured['tool_choice']=='auto'
    assert {t['function']['name'] for t in captured['tools']}==TOOL_NAMES
    assert captured['messages'][0]['role']=='system' and captured['messages'][-1]['content']=='question'
    assert all('startDate' not in t['function']['parameters']['properties'] for t in captured['tools'])

@pytest.mark.asyncio
async def test_refresh_inventory_clarification_keeps_model_selected_operation():
    events=await run('当前库存',Model(final('clarify',question='operation',content='需要哪个仓库？',conditions={'domain':'operations','operation':{'name':'get_current_stock'}})))
    ctx=next(v['context'] for k,v in events if k=='clarification')
    model=Model(call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    following=await run('仓库ID 11',model,resume=json.loads(json.dumps(ctx)))
    assert following[-1][1]['status']=='completed'
    assert json.loads(model.inputs[0][0]['content'])['context']['operation']['name']=='get_current_stock'

@pytest.mark.asyncio
async def test_concurrent_requests_do_not_share_verified_reports_or_ids():
    import asyncio
    good=Model(call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    bad=Model(call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    a,b=await asyncio.gather(run('仓库ID 11当前库存',good),run('仓库ID 12当前库存',bad))
    assert a[-1][1]['status']=='completed' and not any(k=='result' for k,v in b)

@pytest.mark.asyncio
async def test_wrong_work_order_result_cannot_become_verified_evidence():
    class Wrong(StockTools):
        async def call(self,*args):return {**await super().call(*args),'productionOrderId':'12'}
    events=await run('工单ID 11缺料',Model(call('get_production_shortage',{'productionOrderId':'11'}),final(reportIds=['r1'])),Wrong())
    assert not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_profit_clarification_does_not_confirm_models_suggested_sales_metric():
    model=Model(final('clarify',question='profit',content='是否改看销售？',conditions={'domain':'sales','metric':'sales'}))
    events=await run('2026-10-01至2026-10-02利润情况',model)
    value=next(v for k,v in events if k=='clarification')
    assert '没有可核算利润' in value['content'] and 'metric' not in value['context']

@pytest.mark.asyncio
async def test_model_warehouse_clarification_alias_preserves_supported_operation():
    model=Model(final('clarify',question='warehouse',content='需要哪个仓库？',conditions={'domain':'operations','operation':{'name':'get_current_stock'}}))
    events=await run('当前库存',model)
    value=next(v for k,v in events if k=='clarification')
    assert value['context']['question']=='operation' and value['context']['operation']['name']=='get_current_stock'

@pytest.mark.asyncio
async def test_complete_warehouse_reply_cannot_reask_already_confirmed_purpose():
    ctx={'version':1,'domain':'operations','question':'operation','operation':{'name':'get_current_stock'}}
    model=Model(final('clarify',question='operation',content='查当前库存还是预警？',conditions={'operation':{'name':'get_current_stock'},'operationsArgs':{'warehouseId':'11'}}),call('get_current_stock',{'warehouseId':'11'}),final(reportIds=['r1']))
    events=await run('仓库ID 11',model,resume=ctx)
    assert events[-1][1]['status']=='completed' and len(model.inputs)==3
