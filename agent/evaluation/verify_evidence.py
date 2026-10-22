"""Java integration invokes this with real tool projections and an independent SQL oracle.

No model, database connection or credentials are accepted here.
"""
import json
import sys
from decimal import Decimal, ROUND_HALF_UP
from app.evidence import EvidenceEngine

def verify(value):
    e=EvidenceEngine();expected=value['expected'];period=value['period']
    dossier=e.sales(value['summary'],value['trend'],period)
    facts={f['id']:f for f in dossier['facts']}
    assert Decimal(facts['salesAmount']['current'])==Decimal(expected['currentAmount'])
    assert Decimal(facts['salesAmount']['comparison'])==Decimal(expected['previousAmount'])
    assert Decimal(facts['orderCount']['current'])==Decimal(expected['currentOrders'])
    assert Decimal(facts['orderCount']['comparison'])==Decimal(expected['previousOrders'])
    dossier=e.concentration(value['summary'],value['ranking'],period)
    for n in (1,3):
        fact=next(f for f in dossier['facts'] if f['id']=='customerTop'+str(n))
        ratio=(Decimal(expected['top'+str(n)+'Amount'])/Decimal(expected['currentAmount'])*100).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
        assert fact['current']==str(ratio)+'%'
        assert fact['flagged'] is True
    fact=e.operations('get_low_stock_alerts',value['stock'])['facts'][0]
    assert fact['current']==expected['currentStock'] and fact['comparison']==expected['minimumStock']
    return {'sales':'passed','customerConcentration':'passed','stock':'passed','layer':'mysql-real-tools-python-evidence-no-model'}

if __name__=='__main__':
    try:print(json.dumps(verify(json.load(sys.stdin))))
    except Exception as exc:
        print('Evidence check failed: '+type(exc).__name__)
        raise SystemExit(1)
