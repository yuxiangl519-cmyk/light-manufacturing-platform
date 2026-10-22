"""Opt-in real HTTP/LLM regression. Conversations only in mini_mfg_test.

Full answers are saved under ignored output/; no credentials or headers are saved.
"""
import asyncio
import json
import os
import re
import sys
from pathlib import Path
import httpx
from evaluation.dimension_checks import query, check_ranking
from evaluation.live import oracle


async def run():
    assert os.getenv('AI_LIVE_TEST_DATABASE') == 'mini_mfg_test'
    assert '/mini_mfg_test?' in os.environ['MFG_TEST_DB_URL']
    warehouses=query("SELECT id,code,name FROM warehouse WHERE code LIKE 'DM261020-%' ORDER BY id")
    assert warehouses, 'Demo warehouses missing in test DB'
    raw=next(w for w in warehouses if w[1].endswith('RAW'))
    finished=next(w for w in warehouses if w[1].endswith('FG'))
    material=query("SELECT id,code,name FROM material WHERE code LIKE 'DM261020-%' AND type='RAW_MATERIAL' ORDER BY id LIMIT 1")[0]
    order=query("SELECT id,order_no FROM production_order WHERE status='READY_TO_ISSUE' ORDER BY id DESC LIMIT 1")[0]
    cases=[
        ('sales','分析最近30天的销售情况，并给出经营建议。','sales'),
        ('sales_synonym','最近30天卖得怎么样？','sales'),
        ('customers','上个月销售额最高的客户有哪些？','customer'),
        ('products','上个月商品销售排行','product'),
        ('profit','帮我分析最近的利润情况。','clarification'),
        ('empty','分析1990-01-01至1990-01-07的销售情况','empty'),
        ('stock_alias','原料仓哪些物料库存不足？','low'),
        ('stock_alias2','零部件仓库存预警','low'),
        ('stock_name',raw[2]+'哪些物料库存不足？','low'),
        ('stock_code','仓库「'+raw[1]+'」当前库存','stock'),
        ('stock_id','仓库ID '+raw[0]+' 当前库存','stock'),
        ('stock_natural','原料仓还剩多少库存？','stock'),
        ('stock_short','原料仓有哪些缺货物料？','low'),
        ('stock_item','仓库ID '+raw[0]+' 物料ID '+material[0]+' 当前库存','item'),
        ('stock_quoted_item','仓库ID '+raw[0]+' 物料「'+material[1]+'」库存','item'),
        ('stock_both_names','原料仓物料「'+material[1]+'」库存','item'),
        ('stock_unquoted','原料仓的CPU库存','scoped'),
        ('pending','目前有哪些成品等待入库？','pending'),
        ('pending_alias','成品仓有哪些成品等待入库？','pending'),
        ('pending_synonym','有哪些整机还没入库？','pending'),
        ('shortage','工单ID '+order[0]+'还缺哪些零部件？','shortage'),
        ('shortage_number','工单 '+order[1]+' 缺料情况','shortage'),
        ('history','昨天原料仓库存是多少？','clarification'),
        ('write','帮我创建采购单补料','clarification'),
        ('unknown','不存在仓哪些物料库存不足？','clarification'),
    ]
    records=[]
    async with httpx.AsyncClient(base_url='http://127.0.0.1:18081',timeout=175,trust_env=False) as client:
        token=(await client.get('/api/v1/auth/csrf')).json()['token']
        login=await client.post('/api/v1/auth/login',headers={'X-CSRF-TOKEN':token},json={'username':'admin','password':os.environ['MFG_ADMIN_PASSWORD']})
        assert login.status_code==200,'Test login failed; details suppressed'
        headers={'X-CSRF-TOKEN':(await client.get('/api/v1/auth/csrf')).json()['token']}
        async def ask(cid,text):
            events=[];name='';parts=[]
            async with client.stream('POST',f'/api/v1/ai/conversations/{cid}/messages',headers=headers,json={'message':text}) as response:
                assert response.status_code==200,'HTTP '+str(response.status_code)
                async for line in response.aiter_lines():
                    if line.startswith('event:'):name=line[6:].strip()
                    elif line.startswith('data:'):parts.append(line[5:].strip())
                    elif not line and parts:events.append((name,json.loads('\n'.join(parts))));parts=[]
            return events
        for key,question,expected in cases:
            record={'case':key,'question':question,'expected':expected}
            try:
                response=await client.post('/api/v1/ai/conversations',headers=headers,json={'title':'批量只读回归 '+key})
                assert response.status_code==201
                cid=response.json()['id'];events=await ask(cid,question)
                record['events']=list(events)
                status=events[-1][1]['status'];record['status']=status
                if status=='clarification' and key in ('stock_alias','stock_alias2','stock_natural','stock_short','stock_both_names','pending_alias'):
                    # Real test DB contains many historical fixture warehouses.
                    # Ambiguity is correct: explicitly simulate the human selection;
                    # a generic "not found" must still fail this check.
                    warehouse=finished if key=='pending_alias' else raw
                    prompt=next(v['content'] for k,v in events if k=='clarification')
                    assert warehouse[2] in prompt and 'ID '+warehouse[0] in prompt,'Real warehouse absent from candidates'
                    record['followup']='仓库ID '+warehouse[0]
                    events=await ask(cid,record['followup']);record['events']+=events
                    status=events[-1][1]['status'];record['status']=status
                if expected=='clarification':assert status=='clarification','Expected safe clarification'
                elif expected=='scoped':
                    assert status in ('completed','clarification'),'Unexpected failure'
                    if status=='completed':
                        content=next(v['content'] for k,v in events if k=='result')
                        assert 'CPU' in content,'Material filter lost'
                        assert len(re.findall(r'^\| .*（\d+）',content,re.M))<=1,'Material filter widened'
                else:
                    assert status=='completed','Expected answer, got '+status
                    content=next(v['content'] for k,v in events if k=='result')
                    if expected in ('sales','empty'):
                        a,b=('1990-01-01','1990-01-08') if expected=='empty' else ('2026-09-21','2026-10-21')
                        totals,_=oracle(a,b)
                        got=re.search(r'\| 已发货销售额（元） \| ([0-9.]+)',content)
                        from decimal import Decimal
                        assert got and Decimal(got[1])==Decimal(totals[0]['salesAmount']),'Sales amount differs from SQL'
                    elif expected in ('customer','product'):check_ranking(content,'2026-09-01','2026-10-01',expected)
                    elif expected in ('stock','low','item'):
                        assert ('StockQueryService' in content or '库存查询服务' in content),'Wrong source/tool'
                        if expected=='low':assert '最低库存阈值' in content
                        rows=re.findall(r'^\| .*（(\d+)） \| (\d+) / .*? \| (\d+) \| (\d+) \|',content,re.M)
                        where=' AND m.id='+material[0] if expected=='item' else ''
                        if expected=='low':where+=' AND COALESCE(b.quantity,0)<m.min_stock'
                        expected_rows=query('SELECT '+raw[0]+',m.id,COALESCE(b.quantity,0),m.min_stock FROM material m LEFT JOIN stock_balance b ON b.material_id=m.id AND b.warehouse_id='+raw[0]+' WHERE m.enabled=1'+where+' ORDER BY m.code,m.id LIMIT 20')
                        assert [list(row) for row in rows]==expected_rows,'Stock row set differs from independent SQL'
                        for wid,mid,qty,threshold in rows:
                            actual=query('SELECT COALESCE((SELECT quantity FROM stock_balance WHERE warehouse_id='+wid+' AND material_id='+mid+'),0),min_stock FROM material WHERE id='+mid)[0]
                            assert [qty,threshold]==actual,'Stock differs from SQL'
                            assert wid==raw[0],'Wrong warehouse'
                            if expected=='low':assert int(qty)<int(threshold),'Not a low stock row'
                        if expected=='item':assert len(rows)==1 and rows[0][1]==material[0],'Material scope lost'
                    elif expected=='pending':
                        assert 'PENDING / PRODUCTION_RECEIPT' in content,'Wrong pending tool'
                        where=' AND warehouse_id='+finished[0] if key=='pending_alias' else ''
                        expected_docs=query("SELECT id FROM stock_document WHERE status='PENDING' AND kind='PRODUCTION_RECEIPT'"+where+' ORDER BY id DESC LIMIT 20')
                        got_docs=list(dict.fromkeys(re.findall(r'^\| (\d+) / .*? \|',content,re.M)))
                        assert got_docs==[r[0] for r in expected_docs],'Pending document IDs differ from SQL'
                    elif expected=='shortage':
                        assert order[1] in content and '缺料=max' in content,'Wrong production scope'
                        want=query('SELECT s.material_id,s.required_quantity,COALESCE(b.quantity,0),GREATEST(s.required_quantity-COALESCE(b.quantity,0),0) FROM production_material_snapshot s JOIN production_order o ON o.id=s.production_order_id LEFT JOIN stock_balance b ON b.material_id=s.material_id AND b.warehouse_id=o.issue_warehouse_id WHERE o.id='+order[0]+' ORDER BY s.material_id')
                        got=re.findall(r'^\| (\d+) / .*? \| (\d+) \| (\d+) \| (\d+) \|',content,re.M)
                        assert sorted([list(r) for r in got],key=lambda r:int(r[0]))==want,'Shortage differs from snapshot/current balance SQL'
                record['passed']=True
            except Exception as exc:
                record['passed']=False;record['reason']=str(exc) if isinstance(exc,AssertionError) else type(exc).__name__
            records.append(record)
            print(key+': '+('PASS' if record['passed'] else 'FAIL '+record['reason']),flush=True)
        # Regression for failed entity lookup followed by an explicit correction.
        response=await client.post('/api/v1/ai/conversations',headers=headers,json={'title':'批量回归 身份纠正'})
        cid=response.json()['id'];first=await ask(cid,'不存在仓哪些物料库存不足？')
        second=await ask(cid,'仓库ID '+raw[0])
        records.append({'case':'correct_id','question':'不存在仓 → 仓库ID '+raw[0], 'events':first+second,
                        'passed':second[-1]==('done',{'status':'completed'})})
        for key,turns in [
            ('correct_name',['不存在仓哪些物料库存不足？',raw[2]]),
            ('switch_sales',['当前库存','最近30天销售情况']),
            ('clarify_order',['这张工单还缺哪些零部件？','工单ID '+order[0]]),
            ('clarify_warehouse',['当前库存','仓库ID '+raw[0]]),
            ('completed_followup',['仓库ID '+raw[0]+' 物料ID '+material[0]+' 当前库存','那'+finished[2]+'呢？']),
            ('profit_to_sales',['最近30天利润情况','可以，改为销售额和订单数'])]:
            record={'case':key,'questions':turns,'events':[]}
            try:
                cid=(await client.post('/api/v1/ai/conversations',headers=headers,json={'title':'多轮回归 '+key})).json()['id']
                for text in turns:
                    events=await ask(cid,text);record['events']+=events
                assert events[-1]==('done',{'status':'completed'}),'Continuation did not complete'
                if key=='completed_followup':
                    content=next(v['content'] for k,v in events if k=='result')
                    assert '（'+finished[0]+'）' in content and ' / '+material[1]+' / ' in content,'Followup changed or lost scope'
                record['passed']=True
            except Exception as exc:
                record['passed']=False;record['reason']=str(exc) if isinstance(exc,AssertionError) else type(exc).__name__
            records.append(record);print(key+': '+('PASS' if record['passed'] else 'FAIL '+record['reason']),flush=True)
        async with httpx.AsyncClient(trust_env=False) as anonymous:
            response=await anonymous.get('http://127.0.0.1:18081/api/v1/ai/conversations')
            records.append({'case':'anonymous','passed':response.status_code==401,'httpStatus':response.status_code})
    # These are real network faults at agent scope, explicitly not Java SSE E2E.
    from dataclasses import replace
    from app.config import Settings
    from app.graph import SalesAgent
    settings=Settings.from_env()
    for key,config,message,code in [
        ('model_failure',replace(settings,model='evaluation-nonexistent-model',planner_model='evaluation-nonexistent-model'),'最近30天销售情况','MODEL_REQUEST_FAILED'),
        ('tool_failure',replace(settings,erp_base_url='http://127.0.0.1:1'),'仓库ID '+raw[0]+' 当前库存','ERP_TOOL_FAILED')]:
        events=[e async for e in SalesAgent(config).events(message,{'gatewayToken':'evaluation-invalid-capability'}, {})]
        records.append({'case':key,'scope':'agent-real-network-fault;not-java-relay','events':events,
                        'passed':events[-1]==('done',{'status':'error'}) and any(k=='error' and v['code']==code for k,v in events) and not any(k=='result' for k,v in events)})
    path=Path('output/ai-evaluation');path.mkdir(parents=True,exist_ok=True)
    label=sys.argv[1] if len(sys.argv)>1 else 'batch'
    (path/(label+'.json')).write_text(json.dumps({'database':'mini_mfg_test','results':records},ensure_ascii=False,indent=2),encoding='utf-8')
    print('TOTAL '+str(sum(r['passed'] for r in records))+'/'+str(len(records)),flush=True)
    return 1 if any(not r['passed'] for r in records) else 0

if __name__=='__main__':raise SystemExit(asyncio.run(run()))
