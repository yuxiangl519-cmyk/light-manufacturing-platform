"""Fixed evidence-quality cases; no model/network/database calls."""
from copy import deepcopy
from decimal import Decimal
import pytest
from app.evidence import EvidenceEngine, render_evidence, MissingEvidence
from app.analysis_rules import AnalysisRules

PERIOD={'startDate':'2026-10-01','endDate':'2026-10-03'}
def metadata():return {'period':PERIOD.copy(),'asOf':'2026-10-20T01:00:00Z','source':'sales_order.status=SHIPPED;created_at;deleted_at IS NULL','definition':'人民币含税已发货订单金额；不是收入或利润','currency':'CNY'}
def sales(amount='10000',previous='20000',count='20',old_count='20'):
    summary={**metadata(),'current':{'salesAmount':amount,'orderCount':count},'previous':{'salesAmount':previous,'orderCount':old_count}}
    first=Decimal(amount)/2 if int(count)>=2 else Decimal(0)
    trend={**metadata(),'points':[{'date':'2026-10-01','salesAmount':str(first),'orderCount':str(int(count)//2)}, {'date':'2026-10-02','salesAmount':str(Decimal(amount)-first),'orderCount':str(int(count)-int(count)//2)}]}
    return summary,trend

def test_sales_finding_carries_recomputable_evidence_and_no_causal_claim():
    dossier=EvidenceEngine().sales(*sales(),PERIOD)
    fact=next(f for f in dossier['facts'] if f['id']=='salesAmount')
    assert fact['current']=='10000.00' and fact['comparison']=='20000.00'
    assert fact['rate']=='-50.00%' and fact['flagged'] is True
    assert fact['period']==PERIOD and fact['asOf']['get_sales_summary']=='2026-10-20T01:00:00Z'
    assert fact['tools']==['get_sales_summary','get_sales_trend'] and fact['formula']
    assert dossier['actions'][0]['evidence'] in [f['id'] for f in dossier['facts']]
    assert '待核实' in render_evidence(dossier) and '缺货导致' not in fact['statement']

@pytest.mark.parametrize('count,previous,expected',[('1','20000','样本不足'),('0','0','上期为零')])
def test_small_sample_and_zero_base_do_not_become_reliable_anomalies(count,previous,expected):
    amount='0' if count=='0' else '10000'
    dossier=EvidenceEngine().sales(*sales(amount,previous,count,'0' if previous=='0' else '1'),PERIOD)
    fact=next(f for f in dossier['facts'] if f['id']=='salesAmount')
    assert not fact['flagged'] and expected in fact['assessment']

@pytest.mark.parametrize('mutation',['wrong_period','missing_day','wrong_orders','foreign_dimension','wrong_sum','stale_time'])
def test_contradictory_or_unrelated_evidence_is_rejected(mutation):
    summary,trend=sales()
    if mutation=='wrong_period':trend['period']={'startDate':'2026-09-01','endDate':'2026-09-03'}
    if mutation=='missing_day':trend['points']=trend['points'][:1]
    if mutation=='wrong_orders':trend['points'][0]['orderCount']='9'
    if mutation=='foreign_dimension':trend['dimension']='product'
    if mutation=='wrong_sum':trend['points'][0]['salesAmount']='0'
    if mutation=='stale_time':trend['asOf']='2026-10-20T02:00:00Z'
    with pytest.raises(ValueError):EvidenceEngine().sales(summary,trend,PERIOD)

def test_missing_timestamp_is_missing_evidence_not_fabricated_clock():
    summary,trend=sales();del summary['asOf']
    with pytest.raises(MissingEvidence):EvidenceEngine().sales(summary,trend,PERIOD)

def ranking():
    return {**metadata(),'dimension':'customer','rows':[{'id':str(i),'name':'客户'+str(i),'salesAmount':amount,'orderCount':'4','quantitiesByUnit':{}} for i,amount in enumerate(['6000','2000','1000','500'],1)]}

def test_customer_concentration_uses_all_sales_denominator_not_top_n_sum():
    summary,_=sales(previous='10000')
    dossier=EvidenceEngine().concentration(summary,ranking(),PERIOD)
    first=next(f for f in dossier['facts'] if f['id']=='customerTop1')
    assert first['current']=='60.00%' and first['comparison']=='50.00%'
    assert first['entityId']=='1' and first['flagged']
    assert '10000.00' in first['formula']
    assert next(f for f in dossier['facts'] if f['id']=='customerTop3')['current']=='90.00%'

def test_top_three_of_only_two_customers_is_not_a_concentration_warning():
    summary,_=sales(amount='8000');r=ranking();r['rows']=r['rows'][:2]
    fact=next(f for f in EvidenceEngine().concentration(summary,r,PERIOD)['facts'] if f['id']=='customerTop3')
    assert not fact['flagged'] and '不足4' in fact['assessment']

@pytest.mark.parametrize('mutation',['ranking_exceeds_total','empty_with_nonzero_total','zero_total_with_rows','wrong_dimension'])
def test_concentration_conflicts_never_produce_percentage(mutation):
    summary,_=sales();r=ranking()
    if mutation=='ranking_exceeds_total':r['rows'][0]['salesAmount']='20000'
    if mutation=='empty_with_nonzero_total':r['rows']=[]
    if mutation=='zero_total_with_rows':summary['current']={'salesAmount':'0','orderCount':'0'}
    if mutation=='wrong_dimension':r['dimension']='product'
    with pytest.raises(ValueError):EvidenceEngine().concentration(summary,r,PERIOD)

def test_empty_concentration_has_no_ratio_or_recommendation_to_a_fake_customer():
    summary,_=sales('0','0','0','0');r=ranking();r['rows']=[]
    dossier=EvidenceEngine().concentration(summary,r,PERIOD)
    assert not dossier['facts'] and dossier['unanswered'] and not dossier['actions']

def test_rules_are_injectable_and_stated():
    dossier=EvidenceEngine(AnalysisRules(change_percent=Decimal('60'))).sales(*sales(),PERIOD)
    assert not next(f for f in dossier['facts'] if f['id']=='salesAmount')['flagged']
    assert '60%' in dossier['rules']

@pytest.mark.parametrize('current,previous,count,old_count,expected',[
    ('13000','10000','5','5',True),('12999','10000','5','5',False),
    ('1300','1000','5','5',False),('13000','10000','4','5',False)])
def test_change_threshold_rate_amount_and_sample_boundaries(current,previous,count,old_count,expected):
    fact=next(f for f in EvidenceEngine().sales(*sales(current,previous,count,old_count),PERIOD)['facts'] if f['id']=='salesAmount')
    assert fact['flagged']==expected

def test_order_change_has_its_own_absolute_threshold():
    fact=next(f for f in EvidenceEngine().sales(*sales('10000','10000','10','5'),PERIOD)['facts'] if f['id']=='orderCount')
    assert fact['flagged'] and fact['rate']=='100.00%'

def test_single_customer_with_enough_orders_is_identified_as_concentrated():
    summary,_=sales();r=ranking();r['rows']=r['rows'][:1];r['rows'][0].update(salesAmount='10000',orderCount='20')
    fact=next(f for f in EvidenceEngine().concentration(summary,r,PERIOD)['facts'] if f['id']=='customerTop1')
    assert fact['current']=='100.00%' and fact['flagged']
