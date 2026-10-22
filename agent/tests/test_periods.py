"""Stage 2 fixed calendar and clarification regression requirements."""
from datetime import date, datetime, timezone
from decimal import Decimal
import pytest

from app.graph import RuleWorkflow as SalesAgent, resolve_period
from app.analytics import growth
from app.config import Settings
from test_evaluation import BaselineModel, BaselineTools, metadata, zero_trend


@pytest.mark.parametrize('today,message,start,end', [
    ('2026-10-20','最近7天销售','2026-10-14','2026-10-21'),
    ('2026-01-01','今天销售','2026-01-01','2026-01-02'),
    ('2026-01-01','昨天销售','2025-12-31','2026-01-01'),
    ('2026-01-01','本周销售','2025-12-29','2026-01-02'),
    ('2026-01-01','上周销售','2025-12-22','2025-12-29'),
    ('2026-10-25','本周销售','2026-10-19','2026-10-26'),
    ('2026-10-26','本周销售','2026-10-26','2026-10-27'),
    ('2024-03-31','上月销售','2024-02-01','2024-03-01'),
    ('2025-03-31','上月销售','2025-02-01','2025-03-01'),
    ('2026-01-31','上月销售','2025-12-01','2026-01-01'),
    ('2024-02-29','本月销售','2024-02-01','2024-03-01'),
    ('2026-10-20','本月销售','2026-10-01','2026-10-21'),
    ('2026-10-20','2024年2月销售','2024-02-01','2024-03-01'),
    ('2026-10-20','2025-02销售','2025-02-01','2025-03-01'),
    ('2026-10-20','2026年10月销售','2026-10-01','2026-10-21'),
    ('2026-10-20','2025年12月31日至2026年1月2日销售','2025-12-31','2026-01-03'),
    ('2026-10-20','2024-2-28至2024-2-29销售','2024-02-28','2024-03-01'),
])
def test_calendar_ranges(today,message,start,end):
    assert resolve_period(message, {}, today=date.fromisoformat(today)) == {'startDate':start,'endDate':end}


@pytest.mark.parametrize('message', ['2025-02-29销售','2026-13销售','2026年0月销售','2026-02-30至2026-03-01销售',
    '2026-10-03至2026-10-01销售','最近0天销售','最近367天销售','最近1000天销售',
    '今天和昨天销售','2026-10-01至销售','2月销售','2026-10-01至2026-10-02及2026-10-04销售'])
def test_invalid_or_ambiguous_new_dates_never_reuse_old_range(message):
    with pytest.raises(ValueError):
        resolve_period(message, {'startDate':'2026-09-01','endDate':'2026-09-03'}, today=date(2026,10,20))


def test_current_message_overrides_original_range():
    assert resolve_period('昨天销售', {'originalMessage':'2026-01-01至2026-01-02销售'}, today=date(2026,10,20)) == {
        'startDate':'2026-10-19','endDate':'2026-10-20'}


def test_zero_baseline_is_never_a_percentage_even_zero_to_zero():
    assert growth(Decimal('0'),Decimal('0')) == '不可比（上期为零）'


class RecordingModel(BaselineModel):
    def __init__(self): self.periods=[]
    async def plan(self,message,period,feedback=''):
        self.periods.append(period)
        return await super().plan(message,period,feedback)


async def collect(agent,message,resume=None):
    return [e async for e in agent.events(message,{},resume or {})]


def clarification(events,question):
    assert events[-1] == ('done',{'status':'clarification'})
    value=next(v for k,v in events if k=='clarification')
    assert value['context']['question'] == question
    assert not any(k=='result' for k,v in events)
    return value['context']


@pytest.mark.asyncio
async def test_dates_then_metric_clarification_preserves_explicit_range():
    model=RecordingModel()
    agent=SalesAgent(Settings(),model=model,tools=BaselineTools(),clock=lambda:date(2026,10,2))
    context=clarification(await collect(agent,'经营怎么样，最近'), 'dates')
    context=clarification(await collect(agent,'本月',context), 'metric')
    assert context['startDate']=='2026-10-01' and context['endDate']=='2026-10-03'
    assert model.periods==[]
    events=await collect(agent,'销售额和订单数',context)
    assert events[-1] == ('done',{'status':'completed'})
    assert model.periods==[{'startDate':'2026-10-01','endDate':'2026-10-03'}]
    report=next(v['content'] for k,v in events if k=='result')
    assert 'Asia/Shanghai' in report and '不是收入或利润' in report
    assert '2026-09-29 至 2026-10-01' in report


@pytest.mark.asyncio
async def test_income_requires_explicit_switch_to_order_amount():
    model=RecordingModel()
    agent=SalesAgent(Settings(),model=model,tools=BaselineTools(),clock=lambda:date(2026,10,2))
    context=clarification(await collect(agent,'2026-10-01至2026-10-02收入'), 'revenue')
    assert model.periods==[]
    events=await collect(agent,'可以，改分析已发货销售额',context)
    assert events[-1] == ('done',{'status':'completed'})


@pytest.mark.asyncio
async def test_invalid_new_date_asks_again_instead_of_silently_using_history():
    model=RecordingModel()
    agent=SalesAgent(Settings(),model=model,tools=BaselineTools(),clock=lambda:date(2026,10,2))
    events=await collect(agent,'2026-02-30销售',{'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales'})
    context=clarification(events,'dates')
    assert 'startDate' not in context
    assert model.periods==[]
    events=await collect(agent,'2026-10-01至2026-10-02',context)
    assert events[-1] == ('done',{'status':'completed'})


@pytest.mark.asyncio
async def test_datetime_clock_is_converted_to_shanghai_before_today_resolution():
    model=RecordingModel()
    class Empty:
        async def call(self,name,args,token):
            if name=='get_sales_summary':
                return {**metadata(args),'current':{'salesAmount':'0','orderCount':'0'},'previous':{'salesAmount':'0','orderCount':'0'}}
            return zero_trend(args)
    agent=SalesAgent(Settings(),model=model,tools=Empty(),clock=lambda:datetime(2026,12,31,16,1,tzinfo=timezone.utc))
    events=await collect(agent,'今天销售')
    assert events[-1] == ('done',{'status':'completed'})
    assert model.periods==[{'startDate':'2027-01-01','endDate':'2027-01-02'}]

@pytest.mark.asyncio
@pytest.mark.parametrize('question,decline', [
    ('本月经营怎么样','不可以'), ('本月利润','不要分析销售额'), ('本月客户利润排名','不可以，仍然按客户')])
async def test_declining_offered_metric_never_starts_analysis(question,decline):
    model=RecordingModel()
    agent=SalesAgent(Settings(),model=model,tools=BaselineTools(),clock=lambda:date(2026,10,2))
    initial=await collect(agent,question)
    context=next(v['context'] for k,v in initial if k=='clarification')
    events=await collect(agent,decline,context)
    assert events[-1] == ('done',{'status':'clarification'})
    assert model.periods==[]


@pytest.mark.asyncio
async def test_successful_sales_context_can_supply_both_missing_scope_and_metric():
    model=RecordingModel()
    agent=SalesAgent(Settings(),model=model,tools=BaselineTools(),clock=lambda:date(2026,10,2))
    events=await collect(agent,'最近经营怎么样',{'startDate':'2026-10-01','endDate':'2026-10-03','metric':'sales'})
    assert events[-1] == ('done',{'status':'completed'})
    assert model.periods==[{'startDate':'2026-10-01','endDate':'2026-10-03'}]

def test_shanghai_historical_dst_clock_uses_iana_zone_not_fixed_offset():
    from app.dates import shanghai_day
    assert shanghai_day(datetime(1990,6,1,15,30,tzinfo=timezone.utc)) == date(1990,6,2)

@pytest.mark.asyncio
async def test_planning_retry_keeps_one_clock_snapshot():
    calls=[]
    def clock():
        calls.append(1)
        return date(2026,10,2) if len(calls)==1 else date(2026,10,3)
    class RetryModel(RecordingModel):
        async def plan(self,message,period,feedback=''):
            plan=await super().plan(message,period,feedback)
            return plan[:1] if len(self.periods)==1 else plan
    model=RetryModel()
    agent=SalesAgent(Settings(),model=model,tools=BaselineTools(),clock=clock)
    events=await collect(agent,'最近2天销售')
    assert events[-1] == ('done',{'status':'completed'})
    assert len(calls)==1
    assert model.periods==[{'startDate':'2026-10-01','endDate':'2026-10-03'}]  # Supplement reuses resolved dates without model replanning.
