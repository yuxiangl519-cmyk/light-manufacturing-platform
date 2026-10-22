"""Layer 1: real graph, deterministic doubles; absolutely no model/network calls."""
import asyncio
from datetime import date

import pytest
from app.config import Settings
from app.graph import RuleWorkflow as SalesAgent
from app.model import AgentFailure
from evaluation.cases import CASES, SOURCE
from evaluation.checks import check_report

def metadata(args,dimension=None):
    value={'period':{k:args[k] for k in ('startDate','endDate')},'asOf':'2026-10-20T01:00:00Z',
           'source':SOURCE,'definition':'按创建时间筛选当前已发货销售单的人民币含税订单金额，不是收入或利润','currency':'CNY'}
    if dimension:
        value['dimension']=dimension
        if dimension=='product':value['definition']='按销售单创建时间筛选当前已发货销售明细line_amount的人民币含税金额，不是收入或利润'
    return value

def zero_trend(args):
    from datetime import timedelta
    start,end=map(date.fromisoformat,(args['startDate'],args['endDate']))
    return {**metadata(args),'points':[{'date':str(start+timedelta(days=i)),'salesAmount':'0.00','orderCount':'0'} for i in range((end-start).days)]}


class BaselineModel:
    async def plan(self, message, period, feedback=''):
        return [{'name': name, 'arguments': period} for name in ('get_sales_summary', 'get_sales_trend')]

    async def reflect(self, evidence):
        return 'report'


class BaselineTools:
    async def call(self, name, args, token):
        if name == 'get_customer_sales_ranking':
            return {**metadata(args,'customer'),
                    'rows':[{'id':'11','name':'测试销售快照','code':'','salesAmount':'120.01','orderCount':'2','quantitiesByUnit':{}}]}
        empty = args['startDate'].startswith('1990')
        current = {'salesAmount': '0.00' if empty else '120.01', 'orderCount': '0' if empty else '2'}
        previous = {'salesAmount': '0.00' if empty else '100.00', 'orderCount': '0' if empty else '1'}
        if name == 'get_sales_summary':
            return {**metadata(args),'current': current, 'previous': previous}
        value=zero_trend(args);value['points'][-1].update(current)
        return value


@pytest.mark.asyncio
@pytest.mark.parametrize('case_id,message,expected', CASES, ids=[c[0] for c in CASES])
async def test_fixed_baseline(case_id, message, expected):
    class Model(BaselineModel):
        async def plan(self, *args, **kwargs):
            if case_id == 'model_failure':
                raise AgentFailure('MODEL_REQUEST_FAILED', 'Injected model failure')
            if case_id == 'cancel':
                started.set()
                await asyncio.Event().wait()
            return await super().plan(*args, **kwargs)

    class Tools(BaselineTools):
        async def call(self, *args):
            if case_id in ('tool_failure', 'unauthorized'):
                raise PermissionError('Injected denied tool' if case_id == 'unauthorized' else 'Injected failure')
            return await super().call(*args)

    agent = SalesAgent(Settings(), model=Model(), tools=Tools(), clock=lambda: date(2026, 10, 2))
    resume = {}
    if case_id == 'resume':
        initial = [e async for e in agent.events('分析销售情况', {}, {})]
        resume = next(v['context'] for k, v in initial if k == 'clarification')
    async def collect():
        return [e async for e in agent.events(message, {}, resume)]
    if case_id == 'cancel':
        started = asyncio.Event()
        task = asyncio.create_task(collect())
        await asyncio.wait_for(started.wait(), 2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        return
    events = await collect()
    if expected in ('error', 'denied'):
        code = 'MODEL_REQUEST_FAILED' if case_id == 'model_failure' else 'ERP_TOOL_FAILED'
        assert next(v['code'] for k, v in events if k == 'error') == code
        assert not any(k == 'result' for k, _ in events)
        assert events[-1] == ('done', {'status': 'error'})
    else:
        assert events[-1] == ('done', {'status': expected})
        if expected == 'clarification':
            assert next(v['context']['question'] for k, v in events if k == 'clarification') == {
                'dates': 'dates', 'profit': 'profit', 'customer': 'dimensions'}[case_id]
            assert not any(k == 'result' for k, _ in events)
        else:
            empty = case_id == 'empty'
            start, end = ('1990-01-01', '1990-01-03') if empty else ('2026-10-01', '2026-10-03')
            result = next(v for k, v in events if k == 'result')
            if case_id == 'customer':
                assert '| 11 | 测试销售快照 | 120.01 | 2 |' in result['content']
                assert 'Asia/Shanghai' in result['content'] and SOURCE in result['content']
                return
            check_report(result, start, end,
                         {'salesAmount': '0' if empty else '120.01', 'orderCount': '0' if empty else '2'},
                         {'salesAmount': '0' if empty else '100', 'orderCount': '0' if empty else '1'}, SOURCE)


def test_report_checker_rejects_wrong_numeric_date_or_source():
    from evaluation.checks import check_report
    # Each mutation must be rejected, even if all usual answer keywords remain.
    result = {'context': {'startDate': '2026-10-01', 'endDate': '2026-10-03'}, 'content':
        '2026-10-01 至 2026-10-03（结束日期不含当天\n时区：Asia/Shanghai\n'
        '口径：按创建时间筛选当前已发货销售单，人民币含税订单金额；不是收入或利润\n'
        '| 已发货销售额（元） | 120.01 | 100 | 20.01% |\n'
        '| 已发货订单数 | 2 | 1 | 100.00% |\n数据来源：'+SOURCE+'；币种：CNY'}
    args = ('2026-10-01', '2026-10-03', {'salesAmount': '120.01', 'orderCount': '2'},
            {'salesAmount': '100', 'orderCount': '1'}, SOURCE)
    check_report(result, *args)
    for old, new in [('120.01', '120.02'), ('| 2 |', '| 3 |'), ('20.01%', '20.02%'),
                     ('2026-10-01 至', '2026-10-02 至'), (SOURCE, 'unknown'), ('Asia/Shanghai', 'UTC'), ('含税订单金额', '收入')]:
        with pytest.raises(AssertionError):
            check_report({**result, 'content': result['content'].replace(old, new)}, *args)
    # Java's public boundary converts internal source expressions into a fixed business source.
    check_report({**result,'content':result['content'].replace(SOURCE,'已发货销售订单受控统计')},*args)
