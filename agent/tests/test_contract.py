import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.graph import RuleWorkflow as SalesAgent
from app.main import create_app
from app.tools import validate_call
from test_evaluation import metadata, zero_trend


class FakeModel:
    """Test double only: never used in production or claimed as a live model."""
    def __init__(self, incomplete=False, retry=False):
        self.calls = 0
        self.incomplete = incomplete
        self.retry = retry

    async def plan(self, message, period, feedback=''):
        self.calls += 1
        names = ['get_sales_summary'] if self.incomplete and self.calls == 1 else [
            'get_sales_summary', 'get_sales_trend']
        return [{'name': name, 'arguments': period} for name in names]

    async def reflect(self, evidence):
        return 'retry' if self.retry else 'report'


class FakeTools:
    def __init__(self):
        self.calls = []

    async def call(self, name, arguments, gateway_token):
        self.calls.append((name, arguments, gateway_token))
        if name == 'get_sales_summary':
            return {**metadata(arguments),'current': {'salesAmount': '120.01', 'orderCount': '2'},
                    'previous': {'salesAmount': '100.00', 'orderCount': '1'}}
        value=zero_trend(arguments);value['points'][-1].update(salesAmount='120.01',orderCount='2');return value


@pytest.mark.asyncio
async def test_real_graph_executes_nodes_and_decimal_report_with_test_doubles():
    model, tools = FakeModel(), FakeTools()
    agent = SalesAgent(Settings(), model=model, tools=tools)
    events = [event async for event in agent.events('2026-10-01至2026-10-02销售',
                                                    {'gatewayToken': 'opaque-capability'}, {})]
    stages = [data['node'] for name, data in events if name == 'stage']
    assert stages == ['Planner', 'Context', 'Executor', 'Analyzer', 'Reflection', 'Reporter']
    content = next(data['content'] for name, data in events if name == 'result')
    assert '120.01' in content and '20.01%' in content and '不是收入或利润' in content
    assert tools.calls[0][1] == {'startDate': '2026-10-01', 'endDate': '2026-10-03'}
    assert tools.calls[0][2] == 'opaque-capability'
    assert events[-1] == ('done', {'status': 'completed'})
    assert 'gatewayToken' not in json.dumps(events)


@pytest.mark.asyncio
async def test_incomplete_evidence_supplements_only_missing_query():
    model = FakeModel(incomplete=True)
    events = [event async for event in SalesAgent(Settings(), model=model, tools=FakeTools()).events(
        '2026-10-01至2026-10-02销售', {'gatewayToken': 'opaque'}, {})]
    assert model.calls == 1  # Reflection supplements the missing trend without replanning identities/dates.
    assert events[-1] == ('done', {'status': 'completed'})


@pytest.mark.asyncio
async def test_reflection_cannot_loop_forever():
    model = FakeModel(retry=True)
    events = [event async for event in SalesAgent(Settings(), model=model, tools=FakeTools()).events(
        '2026-10-01至2026-10-02销售', {'gatewayToken': 'opaque'}, {})]
    assert model.calls == 2
    assert any(name == 'error' and data['code'] == 'REFLECTION_LIMIT' for name, data in events)
    assert not any(name == 'result' for name, _ in events)


def test_raw_sql_unknown_tools_and_changed_dates_are_rejected():
    period = {'startDate': '2026-10-01', 'endDate': '2026-10-03'}
    for name, arguments in [('execute_safe_query', {**period, 'templateId': 'sales_summary', 'sql': 'select 1'}),
                            ('execute_safe_query', {**period, 'templateId': 'all_users'}),
                            ('get_sales_summary', {**period, 'tenantId': 'another'}),
                            ('delete_stock', period),
                            ('get_sales_summary', {**period, 'endDate': '2026-10-04'})]:
        with pytest.raises(ValueError):
            validate_call(name, arguments, period)


def test_http_auth_and_reject_caller_identity():
    client = TestClient(create_app(Settings(service_token='test-secret')))
    body = {'requestId': 'r', 'conversationId': 'c', 'message': '利润',
            'context': {'gatewayToken': 'opaque'}}
    assert client.post('/v1/analyze', json=body).status_code == 401
    headers = {'Authorization': 'Bearer test-secret'}
    response = client.post('/v1/analyze', json=body, headers=headers)
    assert response.status_code == 200
    # The production entry is now model-first, including profit clarification.
    # Without a model, authentication succeeds but analysis must fail honestly.
    assert 'event: error' in response.text and 'MODEL_NOT_CONFIGURED' in response.text
    assert 'event: result' not in response.text
    assert 'test-secret' not in response.text
    assert client.post('/v1/analyze', json={**body, 'tenantId': 'forged'}, headers=headers).status_code == 422
    assert client.post('/v1/analyze', json={**body, 'context': {'gatewayToken': 'x', 'userId': 'forged'}},
                       headers=headers).status_code == 422


@pytest.mark.asyncio
async def test_template_plan_passes_independent_textsql_node_and_result_keeps_period():
    class TemplateModel(FakeModel):
        async def plan(self, message, period, feedback=''):
            return [{'name': 'execute_safe_query', 'arguments': {**period, 'templateId': template}}
                    for template in ('sales_summary', 'sales_trend')]

    class TemplateTools(FakeTools):
        async def call(self, name, arguments, gateway_token):
            mapped = 'get_sales_summary' if arguments['templateId'] == 'sales_summary' else 'get_sales_trend'
            return await super().call(mapped, arguments, gateway_token)

    events = [event async for event in SalesAgent(Settings(), model=TemplateModel(), tools=TemplateTools()).events(
        '2026-10-01至2026-10-02销售', {'gatewayToken': 'opaque'}, {})]
    assert 'TextToSQL' in [data['node'] for name, data in events if name == 'stage']
    result = next(data for name, data in events if name == 'result')
    assert result['context']['endDate'] == '2026-10-03'
    assert '建议' in result['content']


@pytest.mark.asyncio
async def test_profit_clarification_resumes_to_sales_using_original_dates():
    events = [event async for event in SalesAgent(Settings(), model=FakeModel(), tools=FakeTools()).events(
        '改分析销售额', {'gatewayToken': 'opaque'},
        {'question': 'profit', 'originalMessage': '2026-10-01至2026-10-02利润'})]
    assert events[-1] == ('done', {'status': 'completed'})


@pytest.mark.asyncio
async def test_request_tool_budget_applies_across_replanning():
    model = FakeModel(retry=True)
    tools = FakeTools()
    events = [event async for event in SalesAgent(Settings(max_tool_calls=2), model=model, tools=tools).events(
        '2026-10-01至2026-10-02销售', {'gatewayToken': 'opaque'}, {})]
    assert len(tools.calls) == 2
    assert any(name == 'error' and data['code'] == 'UNSAFE_TOOL_PLAN' for name, data in events)


def test_limit_hardcaps_cannot_be_overridden():
    with pytest.raises(ValueError):
        Settings(max_tool_calls=9)
    with pytest.raises(ValueError):
        Settings(max_reflections=4)


@pytest.mark.asyncio
async def test_successful_context_supports_followup_and_empty_data_is_explicit():
    class EmptyTools:
        async def call(self, name, arguments, gateway_token):
            if name == 'get_sales_summary':
                return {**metadata(arguments),'current': {'salesAmount': '0.00', 'orderCount': '0'},
                        'previous': {'salesAmount': '0.00', 'orderCount': '0'},
                        'source': 'sales_order.status=SHIPPED', 'currency': 'CNY'}
            return zero_trend(arguments)
    events = [event async for event in SalesAgent(Settings(), model=FakeModel(), tools=EmptyTools()).events(
        '继续分析销售', {'gatewayToken': 'opaque'},
        {'startDate': '2026-10-01', 'endDate': '2026-10-03', 'metric': 'sales'})]
    result = next(data for name, data in events if name == 'result')
    assert '无已发货销售订单数据' in result['content']
    assert 'sales_order.status=SHIPPED' in result['content']
    assert result['context']['startDate'] == '2026-10-01'


@pytest.mark.asyncio
async def test_dimension_clarification_can_resume_with_global_scope():
    events = [event async for event in SalesAgent(Settings(), model=FakeModel(), tools=FakeTools()).events(
        '可以，分析全厂总体', {'gatewayToken': 'opaque'},
        {'question': 'dimensions', 'originalMessage': '2026-10-01至2026-10-02按客户销售排名'})]
    assert events[-1] == ('done', {'status': 'completed'})
