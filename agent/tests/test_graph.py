import pytest

from app.graph import RuleWorkflow as SalesAgent
from app.config import Settings


@pytest.mark.asyncio
async def test_profit_asks_clarification_without_model_or_tools():
    events = [event async for event in SalesAgent(Settings()).events('利润多少', {}, {})]
    assert any(name == 'clarification' and '利润' in data['content'] for name, data in events)
    assert events[-1] == ('done', {'status': 'clarification'})


@pytest.mark.asyncio
async def test_missing_model_is_explicit():
    events = [event async for event in SalesAgent(Settings()).events('最近30天销售额', {}, {})]
    assert any(name == 'error' and data['code'] == 'MODEL_NOT_CONFIGURED' for name, data in events)

@pytest.mark.asyncio
async def test_colloquial_sales_reaches_real_model_planner():
    events=[event async for event in SalesAgent(Settings()).events('最近30天卖得怎么样？',{}, {})]
    assert any(name=='error' and data['code']=='MODEL_NOT_CONFIGURED' for name,data in events)


@pytest.mark.asyncio
async def test_ambiguous_dates_ask_instead_of_inventing():
    events = [event async for event in SalesAgent(Settings()).events('分析销售', {}, {})]
    assert any(name == 'clarification' and '日期' in data['content'] for name, data in events)


@pytest.mark.asyncio
async def test_customer_product_filters_are_not_silently_ignored():
    from datetime import date
    class Tools:
        def __init__(self): self.names=[]
        async def call(self,name,args,token):
            self.names.append(name)
            if name=='get_customer_sales_ranking':
                from test_evaluation import metadata
                return {**metadata(args,'customer'),'source':'test','rows':[]}
            return {'dimension':'product','candidates':[],'truncated':False}
    for message in ('最近30天按客户销售排名', '最近30天标准办公台式机销量', '最近30天产品销售分析'):
        tools=Tools()
        events = [event async for event in SalesAgent(Settings(),tools=tools,clock=lambda:date(2026,10,2)).events(message, {}, {})]
        assert not any(name in ('get_sales_summary','get_sales_trend') for name in tools.names)
        if '排名' in message:
            assert events[-1]==('done',{'status':'completed'})
        else:
            clarification=next(data for name,data in events if name=='clarification')
            assert clarification['context']['question']=='entity'
