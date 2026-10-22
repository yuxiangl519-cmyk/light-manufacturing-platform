"""Reflection evaluates real graph state; all adapters below are deterministic doubles."""
from datetime import date
from copy import deepcopy
import pytest
from app.graph import RuleWorkflow as SalesAgent
from app.config import Settings
from test_evidence import sales, metadata, ranking, PERIOD

class Model:
    def __init__(self,only_summary=False):self.only_summary=only_summary;self.plans=0;self.reflections=0
    async def plan(self,message,period,feedback=''):
        self.plans+=1
        return [{'name':name,'arguments':dict(period)} for name in (['get_sales_summary'] if self.only_summary else ['get_sales_summary','get_sales_trend'])]
    async def reflect(self,evidence):self.reflections+=1;return 'report'

class Tools:
    def __init__(self,fail=None):self.calls=[];self.fail=fail
    async def call(self,name,args,token):
        self.calls.append(name)
        if name==self.fail:raise OSError('private upstream data must never appear')
        if name=='get_customer_sales_ranking':return deepcopy(ranking())
        return deepcopy(sales()[0 if name=='get_sales_summary' else 1])

async def collect(model,tools,question='2026-10-01至2026-10-02销售分析',limit=4):
    a=SalesAgent(Settings(max_tool_calls=limit),model=model,tools=tools,clock=lambda:date(2026,10,20))
    return [e async for e in a.events(question,{}, {})]

@pytest.mark.asyncio
async def test_missing_trend_supplements_only_missing_tool_without_model_replanning():
    model=Model(True);tools=Tools();events=await collect(model,tools)
    assert events[-1][1]['status']=='completed'
    assert model.plans==1
    assert tools.calls==['get_sales_summary','get_sales_trend']
    content=next(v['content'] for k,v in events if k=='result')
    assert '已查询确认的事实' in content and 'get_sales_trend' in content and '仍需核实' in content

@pytest.mark.asyncio
async def test_partial_failure_keeps_verified_summary_and_clarifies_unanswered_at_limit():
    model=Model();tools=Tools('get_sales_trend');events=await collect(model,tools,limit=3)
    assert tools.calls==['get_sales_summary','get_sales_trend','get_sales_trend']
    assert events[-1][1]['status']=='clarification'
    assert not any(k=='result' for k,v in events)
    text=next(v['content'] for k,v in events if k=='clarification')
    assert 'get_sales_trend' in text and '10000' in text and '上限' in text and '未回答' in text
    assert 'private upstream' not in text

@pytest.mark.asyncio
async def test_conflicting_data_is_rejected_even_when_model_wants_report():
    class Conflict(Tools):
        async def call(self,name,args,token):
            data=await super().call(name,args,token)
            if name=='get_sales_trend':data['points'][0]['orderCount']='0'
            return data
    model=Model();events=await collect(model,Conflict())
    assert next(v['code'] for k,v in events if k=='error')=='INVALID_ERP_DATA'
    assert not any(k=='result' for k,v in events) and model.reflections==0

@pytest.mark.asyncio
async def test_unrelated_inventory_payload_cannot_support_sales():
    class Foreign(Tools):
        async def call(self,name,args,token):
            if name=='get_sales_summary':return {'rows':[{'quantity':'10000'}],'asOf':'2026-10-20T01:00:00Z'}
            return await super().call(name,args,token)
    events=await collect(Model(),Foreign())
    assert not any(k=='result' for k,v in events)

@pytest.mark.asyncio
async def test_customer_concentration_queries_real_tool_names_without_model_identity():
    model=Model();tools=Tools();events=await collect(model,tools,'2026-10-01至2026-10-02客户集中度如何？')
    assert events[-1][1]['status']=='completed'
    assert set(tools.calls)=={'get_sales_summary','get_customer_sales_ranking'}
    assert model.plans==0
    text=next(v['content'] for k,v in events if k=='result')
    assert '60.00%' in text and '10000.00' in text and '客户ID 1' in text

@pytest.mark.asyncio
async def test_sales_decline_and_stock_question_does_not_assume_stock_causality():
    events=await collect(Model(),Tools(),'2026-10-01至2026-10-02销售下降是否由缺货导致？')
    text=next(v['content'] for k,v in events if k=='result')
    assert '因果关系未回答' in text and '历史缺货' in text

@pytest.mark.asyncio
async def test_transient_partial_failure_recovers_without_requerying_summary():
    class Transient(Tools):
        async def call(self,name,args,token):
            if name=='get_sales_trend' and name not in self.calls:
                self.calls.append(name);raise OSError('transient')
            return await super().call(name,args,token)
    tools=Transient();events=await collect(Model(),tools,limit=3)
    assert tools.calls==['get_sales_summary','get_sales_trend','get_sales_trend']
    assert events[-1][1]['status']=='completed'

@pytest.mark.asyncio
async def test_missing_metadata_at_limit_clarifies_instead_of_inventing_source():
    class NoTimestamp(Tools):
        async def call(self,name,args,token):
            value=await super().call(name,args,token);value.pop('asOf',None);return value
    events=await collect(Model(),NoTimestamp(),limit=2)
    assert events[-1][1]['status']=='clarification'
    text=next(v['content'] for k,v in events if k=='clarification')
    assert '未回答' in text and '已查询汇总' not in text

@pytest.mark.asyncio
async def test_user_can_decline_evidence_retry_without_new_model_or_tools():
    model=Model();tools=Tools()
    a=SalesAgent(Settings(),model=model,tools=tools,clock=lambda:date(2026,10,20))
    events=[e async for e in a.events('不重试',{}, {'question':'evidence','originalMessage':'2026-10-01至2026-10-02销售分析',**PERIOD,'metric':'sales','missingTools':['get_sales_trend']})]
    assert not tools.calls and not model.plans
    assert 'get_sales_trend' in next(v['content'] for k,v in events if k=='clarification')

@pytest.mark.asyncio
async def test_partial_template_query_keeps_actual_source_and_timezone():
    class TemplateModel(Model):
        async def plan(self,message,period,feedback=''):
            return [{'name':'execute_safe_query','arguments':{**period,'templateId':kind}} for kind in ('sales_summary','sales_trend')]
    class TemplateTools(Tools):
        async def call(self,name,args,token):
            if args.get('templateId')=='sales_trend':raise OSError('failed trend')
            return await super().call('get_'+args.get('templateId',name.removeprefix('get_')),args,token)
    events=await collect(TemplateModel(),TemplateTools(),limit=2)
    text=next(v['content'] for k,v in events if k=='clarification')
    assert 'execute_safe_query[sales_summary]' in text
    assert 'Asia/Shanghai' in text and '口径' in text

@pytest.mark.asyncio
@pytest.mark.parametrize('scope',['全厂','总体'])
async def test_explicit_global_customer_concentration_stays_on_concentration_tools(scope):
    model=Model();tools=Tools();events=await collect(model,tools,'2026-10-01至2026-10-02'+scope+'客户集中度如何？')
    assert events[-1][1]['status']=='completed'
    assert set(tools.calls)=={'get_sales_summary','get_customer_sales_ranking'} and not model.plans

@pytest.mark.asyncio
@pytest.mark.parametrize('scope',['客户ID 1的销售','客户华为公司的销售','客户“全厂科技公司”的销售'])
async def test_scoped_customer_concentration_never_silently_becomes_global(scope):
    model=Model();tools=Tools();events=await collect(model,tools,'2026-10-01至2026-10-02'+scope+'集中度如何？')
    assert events[-1][1]['status']=='clarification'
    assert not tools.calls and not model.plans
    assert '总体' in next(v['content'] for k,v in events if k=='clarification')

@pytest.mark.asyncio
@pytest.mark.parametrize('suffix',['如何，仅分析华为公司？','如何，针对客户ID 1？','华为公司怎么样？'])
async def test_customer_scope_in_suffix_also_requires_clarification(suffix):
    tools=Tools();model=Model();events=await collect(model,tools,'2026-10-01至2026-10-02客户集中度'+suffix)
    assert events[-1][1]['status']=='clarification' and not tools.calls and not model.plans
