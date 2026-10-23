"""Deterministic decisions and evidence gates; no model/provider calls."""
from copy import deepcopy
import pytest
from app.decision_loop import sales_basis
from app.context import clean_context
from app.evidence import EvidenceEngine, MissingEvidence
from test_decision_loop import Model, call, final, run, result
from test_contract import FakeTools


@pytest.mark.parametrize('text,basis', [
    ('这个月实际发货销售情况', 'shipped_at'),
    ('本月发货金额', 'shipped_at'),
    ('按发货日看最近30天销售', 'shipped_at'),
    ('按订单创建时间统计', 'created_at'),
    ('按创建日统计', 'created_at'),
    ('最近30天已发货销售额', 'created_at'),
])
def test_user_explicit_definition_controls_date_column(text, basis):
    assert sales_basis(text, {}) == basis


def test_followup_and_refresh_preserve_basis_and_conflicting_definition_rejected():
    saved = clean_context({'version': 1, 'dateBasis': 'shipped_at', 'metric': 'sales'})
    assert sales_basis('那上个月呢？', saved) == 'shipped_at'
    assert sales_basis('改为按创建时间', saved) == 'created_at'
    for text in ('按发货日期和按创建日期比较', '按付款日期统计'):
        with pytest.raises(ValueError):
            sales_basis(text, saved)
    assert 'dateBasis' not in clean_context({'version': 1, 'dateBasis': 'arbitrary_sql'})


def test_evidence_rejects_different_snapshot_even_with_identical_timestamps():
    period = {'startDate': '2026-10-01', 'endDate': '2026-10-02'}
    one = dict(source='backend', asOf='2026-10-22T00:00:00Z', definition='sales', currency='CNY',
               period=period, snapshotId='snapshot-a', dateBasis='created_at')
    two = deepcopy(one)
    engine = EvidenceEngine()
    engine.paired(one, two, period, ('summary', 'trend'))
    two['snapshotId'] = 'snapshot-b'
    with pytest.raises(MissingEvidence):
        engine.paired(one, two, period, ('summary', 'trend'))

    two.pop('snapshotId')
    with pytest.raises(MissingEvidence):
        engine.paired(one, two, period, ('summary', 'trend'))
    two['snapshotId'] = one['snapshotId']
    two['dateBasis'] = 'shipped_at'
    with pytest.raises(MissingEvidence):
        engine.paired(one, two, period, ('summary', 'trend'))


@pytest.mark.asyncio
async def test_production_loop_preserves_actual_shipment_basis_on_first_domain_binding_and_followup():
    class Tools(FakeTools):
        async def call(self,name,arguments,token):
            data=await super().call(name,arguments,token)
            data.update(dateBasis=arguments['dateBasis'],snapshotId='same-transaction',snapshotScope='sales-total-trend-rankings')
            return data
    def model():return Model(call('get_sales_summary'),call('get_sales_trend',id='c2'),final(reportIds=['r1']))
    tools=Tools();events=await run('最近2天实际发货销售情况',model(),tools)
    answer=result(events)
    assert answer['context']['dateBasis']=='shipped_at'
    assert '按实际发货时间' in answer['content']
    assert all(arguments['dateBasis']=='shipped_at' for _,arguments,_ in tools.calls)
    followup=Tools();events=await run('那上个月呢？',model(),followup,resume=answer['context'])
    assert result(events)['context']['dateBasis']=='shipped_at'
    assert all(arguments['dateBasis']=='shipped_at' for _,arguments,_ in followup.calls)
