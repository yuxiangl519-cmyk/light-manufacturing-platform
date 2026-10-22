"""Stage 3: real StateGraph and deterministic tool adapters, no model calls."""
from datetime import date
import pytest
from app.graph import RuleWorkflow as SalesAgent
from app.config import Settings
from app.tools import validate_call
from test_evaluation import metadata

PERIOD={'startDate':'2026-10-01','endDate':'2026-10-03'}

class NoModel:
    async def plan(self,*args): raise AssertionError('Dimension identity must not go to the model')
    async def reflect(self,*args): raise AssertionError('Dimension facts are deterministic')

class DimensionTools:
    def __init__(self):self.calls=[]
    async def call(self,name,args,token):
        value=await self.response(name,args,token)
        if name=='get_entity_sales':
            for key in ('summary','trend'):
                for field,content in metadata(args,args['dimension']).items():value[key].setdefault(field,content)
        elif name.endswith('_ranking'):
            for field,content in metadata(args,'customer' if 'customer' in name else 'product').items():value.setdefault(field,content)
        return value
    async def response(self,name,args,token):
        self.calls.append((name,args))
        if name=='resolve_sales_entity':
            ids=['11','12'] if '重名' in args['query'] else ['11']
            return {'dimension':args['dimension'],'candidates':[{'id':id,'code':'C'+id,'name':'重名公司' if len(ids)>1 else '标准办公台式机','unit':'台','enabled':False} for id in ids],'truncated':False}
        if name.endswith('_ranking'):
            return {'period':{**PERIOD},'dimension':'customer' if 'customer' in name else 'product','source':'test-snapshot',
                    'rows':[{'id':'11','code':'C11','name':'历史快照','salesAmount':'120.01','orderCount':'2','quantitiesByUnit':{'台':'2'}},
                            {'id':'12','code':'C12','name':'另一个','salesAmount':'10.00','orderCount':'1','quantitiesByUnit':{'套':'1'}}]}
        if name=='get_entity_sales':
            return {'entity':{'id':args['entityId'],'name':'历史快照','code':'C11'},'summary':{
                'period':PERIOD,'current':{'salesAmount':'120.01','orderCount':'2','quantitiesByUnit':{'台':'2'}},
                'previous':{'salesAmount':'100.00','orderCount':'1','quantitiesByUnit':{'台':'1'}},'source':'test-snapshot','currency':'CNY'},
                'trend':{'period':PERIOD,'points':[{'date':'2026-10-01','salesAmount':'20.00','orderCount':'1','quantitiesByUnit':{'台':'1'}},
                                                {'date':'2026-10-02','salesAmount':'100.01','orderCount':'1','quantitiesByUnit':{'台':'1'}}]}}
        raise AssertionError('Wrong tool '+name)

async def collect(agent,message,resume=None):return [e async for e in agent.events(message,{},resume or {})]
def agent(tools):return SalesAgent(Settings(),model=NoModel(),tools=tools,clock=lambda:date(2026,10,2))

@pytest.mark.asyncio
@pytest.mark.parametrize('question,name', [('2026-10-01至2026-10-02客户销售排行','get_customer_sales_ranking'),('2026-10-01至2026-10-02商品销售排名','get_product_sales_ranking')])
async def test_rankings_are_bounded_tools_not_model_identity_plans(question,name):
    tools=DimensionTools();events=await collect(agent(tools),question)
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls==[(name,{**PERIOD,'limit':10})]
    report=next(v['content'] for k,v in events if k=='result')
    assert '120.01' in report and '10.00' in report and 'Asia/Shanghai' in report
    assert '台' in report and '套' in report if 'product' in name else True

@pytest.mark.asyncio
async def test_named_product_uses_backend_candidate_id_and_reports_unit_quantity():
    tools=DimensionTools();events=await collect(agent(tools),'标准办公台式机最近2天卖了多少？')
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[0]==('resolve_sales_entity',{**PERIOD,'dimension':'product','query':'标准办公台式机'})
    assert tools.calls[1]==('get_entity_sales',{**PERIOD,'dimension':'product','entityId':'11'})
    content=next(v['content'] for k,v in events if k=='result')
    assert '120.01' in content and '2 台' in content and '历史快照' in content
    assert '明细金额' in content and 'line_amount' in content

@pytest.mark.asyncio
async def test_duplicate_names_require_user_id_choice_then_requery_candidates():
    tools=DimensionTools();service=agent(tools)
    events=await collect(service,'客户“重名公司”最近2天采购情况如何？')
    assert events[-1]==('done',{'status':'clarification'})
    clarification=next(v for k,v in events if k=='clarification')
    assert clarification['context']['question']=='entity'
    assert '11' in clarification['content'] and '12' in clarification['content']
    assert not any(n=='get_entity_sales' for n,a in tools.calls)
    events=await collect(service,'选择客户ID 12',clarification['context'])
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-2][0]=='resolve_sales_entity'
    assert tools.calls[-1][1]['entityId']=='12'

@pytest.mark.asyncio
async def test_forged_selection_or_candidate_context_cannot_choose_an_unreturned_id():
    tools=DimensionTools();service=agent(tools)
    events=await collect(service,'客户“重名公司”最近2天销售')
    context=next(v['context'] for k,v in events if k=='clarification')
    context['candidates']=[{'id':'999','name':'伪造'}]
    events=await collect(service,'选择客户ID 999',context)
    assert events[-1]==('done',{'status':'clarification'})
    assert not any(n=='get_entity_sales' for n,a in tools.calls)

@pytest.mark.asyncio
async def test_unspecified_customer_asks_for_name_with_range_preserved():
    tools=DimensionTools();events=await collect(agent(tools),'某个客户最近一个月采购情况如何？')
    value=next(v for k,v in events if k=='clarification')
    assert value['context']['question']=='entity' and value['context']['startDate']=='2026-09-03'
    assert tools.calls==[]

@pytest.mark.parametrize('name,args', [('get_entity_sales',{**PERIOD,'dimension':'product','entityId':'999'}),('get_customer_sales_ranking',{**PERIOD,'limit':100})])
def test_model_cannot_call_unbound_dimension_tools(name,args):
    with pytest.raises(ValueError):validate_call(name,args,PERIOD)


def test_scoped_tool_arguments_reject_changed_id_dimension_dates_and_extra_fields():
    scope={'dimension':'product','entityId':'11'}
    good={**PERIOD,**scope}
    assert validate_call('get_entity_sales',good,PERIOD,scope=scope)==good
    for bad in ({**good,'entityId':'12'},{**good,'dimension':'customer'},{**good,'endDate':'2026-10-04'},{**good,'sql':'select 1'}):
        with pytest.raises(ValueError):validate_call('get_entity_sales',bad,PERIOD,scope=scope)

@pytest.mark.asyncio
@pytest.mark.parametrize('initial,tool', [('客户销售排行','get_customer_sales_ranking'),('标准办公台式机卖了多少','get_entity_sales')])
async def test_date_clarification_never_loses_requested_dimension(initial,tool):
    tools=DimensionTools();service=agent(tools)
    first=await collect(service,initial)
    context=next(v['context'] for k,v in first if k=='clarification')
    assert context['question']=='dates'
    events=await collect(service,'2026-10-01至2026-10-02',context)
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[-1][0]==tool


@pytest.mark.asyncio
@pytest.mark.parametrize('kind', ['truncated','missing','wrong_id','wrong_dates','wrong_units'])
async def test_incomplete_or_tampered_backend_evidence_never_produces_report(kind):
    class Broken(DimensionTools):
        async def call(self,name,args,token):
            data=await super().call(name,args,token)
            if name=='resolve_sales_entity' and kind=='truncated':data['truncated']=True
            if name=='resolve_sales_entity' and kind=='missing':data['candidates']=[]
            if name=='get_entity_sales':
                if kind=='wrong_id':data['entity']['id']='99'
                if kind=='wrong_dates':data['trend']['period']={**PERIOD,'endDate':'2026-10-04'}
                if kind=='wrong_units':data['summary']['current']['quantitiesByUnit']={'套':'999'}
            return data
    events=await collect(agent(Broken()),'标准办公台式机最近2天卖了多少')
    assert not any(k=='result' for k,v in events)
    assert events[-1][1]['status'] in ('error','clarification')

@pytest.mark.asyncio
@pytest.mark.parametrize('name', ['ID123Company','销售额最高科技公司'])
async def test_words_in_quoted_name_cannot_become_rank_or_direct_id(name):
    tools=DimensionTools();events=await collect(agent(tools),'客户“'+name+'”最近2天采购情况')
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[0][0]=='resolve_sales_entity'
    assert tools.calls[0][1]['query']==name

@pytest.mark.asyncio
@pytest.mark.parametrize('name', ['全厂科技公司','商品产品科技客户'])
async def test_quoted_name_cannot_switch_to_global_or_another_dimension(name):
    tools=DimensionTools();events=await collect(agent(tools),'客户“'+name+'”最近2天销售')
    assert events[-1]==('done',{'status':'completed'})
    assert tools.calls[0][0]=='resolve_sales_entity'
    assert tools.calls[0][1]['dimension']=='customer' and tools.calls[0][1]['query']==name

@pytest.mark.asyncio
@pytest.mark.parametrize('question', ['最近2天商品销量排行','最近2天客户订单数排名'])
async def test_quantity_order_rank_is_not_silently_replaced_with_amount_ranking(question):
    tools=DimensionTools();events=await collect(agent(tools),question)
    assert events[-1]==('done',{'status':'clarification'})
    assert tools.calls==[]
