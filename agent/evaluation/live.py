"""Layer 3: opt-in HTTP E2E and explicitly labelled agent fault probes.
No credentials or response bodies are included in the report.
"""
import asyncio
import json
import os
import subprocess
import time
from dataclasses import replace
from datetime import date, datetime, time as day_time, timedelta, timezone
from pathlib import Path

import httpx
from app.config import Settings
from app.dates import SHANGHAI
from app.graph import SalesAgent
from evaluation.cases import CASES, SOURCE
from evaluation.checks import check_report

OUT = Path(__file__).resolve().parents[2]/'output/ai-evaluation'


def oracle(start, end):
    """Independent detail SELECT, Python grouping; no production aggregate SQL."""
    assert os.environ.get('AI_LIVE_TEST_DATABASE') == 'mini_mfg_test'
    assert '/mini_mfg_test?' in os.environ['MFG_TEST_DB_URL']
    a, b = date.fromisoformat(start), date.fromisoformat(end)
    previous = a-(b-a)
    def utc(day):
        return datetime.combine(day, day_time(), SHANGHAI).astimezone(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')
    sql = ("SELECT DATABASE(); SELECT created_at,total_amount FROM sales_order "
           "WHERE status='SHIPPED' AND deleted_at IS NULL AND created_at>='"+utc(previous)+"' AND created_at<'"+utc(b)+"' ORDER BY created_at,id;")
    env = dict(os.environ, MYSQL_PWD=os.environ['MFG_TEST_DB_PASSWORD'])
    proc = subprocess.run([os.environ['AI_EVAL_MYSQL'], '--no-defaults', '--host=127.0.0.1', '--port=13306',
                           '--user='+os.environ['MFG_TEST_DB_USERNAME'], '--database=mini_mfg_test',
                           '--batch', '--skip-column-names', '--execute', sql], env=env, capture_output=True, text=True, timeout=15)
    assert proc.returncode == 0, 'Independent SQL oracle failed (details suppressed)'
    lines = proc.stdout.splitlines()
    assert lines[0] == 'mini_mfg_test', 'Oracle database mismatch'
    from decimal import Decimal
    daily = {a+timedelta(days=i): [Decimal(0), 0] for i in range((b-a).days)}
    totals = [[Decimal(0), 0], [Decimal(0), 0]]
    for line in lines[1:]:
        stamp, amount = line.split('\t')
        day = datetime.fromisoformat(stamp).replace(tzinfo=timezone.utc).astimezone(SHANGHAI).date()
        bucket = 0 if a <= day < b else 1
        totals[bucket][0] += Decimal(amount)
        totals[bucket][1] += 1
        if bucket == 0:
            daily[day][0] += Decimal(amount)
            daily[day][1] += 1
    return [dict(salesAmount=str(v[0]), orderCount=str(v[1])) for v in totals], daily


async def run():
    assert os.environ.get('AI_EVAL_LIVE') == '1', 'Explicit AI_EVAL_LIVE=1 required'
    assert os.environ.get('AI_LIVE_TEST_DATABASE') == 'mini_mfg_test', 'Test guard required'
    settings = Settings.from_env()
    assert settings.api_key and settings.model and settings.service_token, 'Real model configuration required'
    records = []
    async def check(name, probe, scope='java-http-e2e', layer='real-model'):
        started = time.perf_counter()
        try:
            await probe()
            status, reason = 'passed', ''
        except Exception as exc:
            status = 'failed'
            reason = str(exc) if isinstance(exc, AssertionError) else type(exc).__name__
        records.append(dict(case=name, layer=layer, scope=scope, status=status,
                            seconds=round(time.perf_counter()-started, 3), reason=reason))
        print(name+': '+status, flush=True)

    async with httpx.AsyncClient(base_url='http://127.0.0.1:18081', timeout=175, trust_env=False) as client:
        csrf = (await client.get('/api/v1/auth/csrf')).json()['token']
        response = await client.post('/api/v1/auth/login', headers={'X-CSRF-TOKEN': csrf},
                                     json={'username': 'admin', 'password': os.environ['MFG_ADMIN_PASSWORD']})
        assert response.status_code == 200, 'Test login failed'
        headers = {'X-CSRF-TOKEN': (await client.get('/api/v1/auth/csrf')).json()['token']}
        async def create():
            response = await client.post('/api/v1/ai/conversations', headers=headers, json={'title': '固定评测基线v1'})
            assert response.status_code == 201, 'Conversation creation failed'
            return response.json()['id']
        async def ask(cid, message, cancel=False):
            events = []; event = ''; data = []
            async with client.stream('POST', f'/api/v1/ai/conversations/{cid}/messages', headers=headers, json={'message': message}) as stream:
                assert stream.status_code == 200, 'SSE HTTP status mismatch'
                async for line in stream.aiter_lines():
                    if line.startswith('event:'): event = line[6:].strip()
                    elif line.startswith('data:'): data.append(line[5:].lstrip())
                    elif not line and data:
                        value = json.loads('\n'.join(data)); data = []; events.append((event, value))
                        if cancel and event == 'stage':
                            response = await client.post(f'/api/v1/ai/conversations/{cid}/cancel', headers=headers)
                            assert response.status_code == 204, 'Cancel rejected'
                            break
            return events
        async def probe(case_id, question, expected, selected_range=None, prelude=None):
            cid = await create()
            for turn, expected_question in prelude or []:
                answer = await ask(cid, turn)
                assert answer[-1] == ('done', {'status':'clarification'}), 'Prelude clarification missing'
                assert next(v['context']['question'] for k,v in answer if k=='clarification') == expected_question, 'Prelude clarification type mismatch'
            if case_id == 'resume':
                initial = await ask(cid, '分析销售情况')
                assert initial[-1][1]['status'] == 'clarification', 'Initial clarification missing'
                question = '2026-10-01至2026-10-02'
            if case_id == 'cancel':
                await ask(cid, question, True)
                await asyncio.sleep(.2)
                detail = (await client.get(f'/api/v1/ai/conversations/{cid}')).json()
                assert detail['status'] == 'CANCELLED', 'Cancellation did not persist'
                return
            events = await ask(cid, question)
            codes = [v.get('code', 'UNKNOWN') for k,v in events if k == 'error']
            assert events and events[-1] == ('done', {'status': expected}), 'Terminal status mismatch; codes='+','.join(codes)
            if expected == 'clarification':
                value = next(v for k,v in events if k == 'clarification')
                assert value['context']['question'] == {'dates':'dates','profit':'profit','customer':'dimensions','metric':'metric','revenue':'revenue','invalid_date':'dates'}[case_id], 'Clarification type mismatch'
                assert not any(k == 'result' for k,v in events), 'Unexpected report'
                return
            result = next(v for k,v in events if k == 'result')
            start,end = selected_range or (('1990-01-01','1990-01-03') if case_id == 'empty' else ('2026-10-01','2026-10-03'))
            if case_id == 'customer':
                from evaluation.dimension_checks import check_ranking
                check_ranking(result['content'],start,end,'customer')
                return
            totals, daily = oracle(start,end)
            if case_id == 'empty':
                assert totals[0] == {'salesAmount':'0', 'orderCount':'0'}, 'Empty fixture interval contains orders'
            previous_start = str(date.fromisoformat(start)-(date.fromisoformat(end)-date.fromisoformat(start)))
            assert f'对比区间：{previous_start} 至 {start}（结束日期不含当天）' in result['content'], 'Previous period mismatch'
            check_report(result, start, end, *totals, SOURCE)
            from decimal import Decimal, ROUND_HALF_UP
            mean = (Decimal(totals[0]['salesAmount'])/len(daily)).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)
            assert f'日均销售额：{mean} 元' in result['content'], 'Daily mean mismatch'
            amounts = [v[0] for v in daily.values()]
            direction = '上升' if amounts[-1]>amounts[0] else '下降' if amounts[-1]<amounts[0] else '持平'
            assert f'首末日销售额比较：{direction}' in result['content'], 'Trend mismatch'
            assert oracle(start,end) == (totals, daily), 'Source changed during evaluation; rerun in quiet window'
        for case_id, question, expected in CASES:
            if case_id not in ('unauthorized','model_failure','tool_failure'):
                await check(case_id, lambda c=case_id,q=question,e=expected: probe(c,q,e),layer='real-model')
        if os.getenv('AI_EVAL_CALENDAR') == '1':
            from evaluation.calendar_cases import CALENDAR_CASES
            for name, question, start, end in CALENDAR_CASES:
                await check('calendar_'+name, lambda n=name,q=question,a=start,b=end: probe(
                    n,q,'completed',(a,b)), scope='java-http-e2e;fixed-clock=2026-10-20')
            for name, question in [('metric','本月经营怎么样'), ('revenue','本月收入'), ('invalid_date','2025-02-29销售')]:
                await check(name, lambda n=name,q=question: probe(n,q,'clarification'))
            await check('metric_resume', lambda: probe('metric_resume','销售额和订单数','completed',
                ('2026-10-01','2026-10-21'), [('最近经营怎么样','dates'),('本月','metric')]))
        if os.getenv('AI_EVAL_DIMENSIONS')=='1':
            from evaluation.dimension_live import scenarios
            await scenarios(client,headers,create,ask,check)
        async def denied():
            async with httpx.AsyncClient(base_url='http://127.0.0.1:18081', trust_env=False) as anonymous:
                assert (await anonymous.get('/api/v1/ai/conversations')).status_code == 401, 'Anonymous access allowed'
            # Roleless user, test DB only. Password never saved in report.
            import uuid
            username, password = 'eval-'+uuid.uuid4().hex[:10], uuid.uuid4().hex
            response = await client.post('/api/v1/system/users', headers=headers, json={
                'username':username,'displayName':'评测无权限用户','initialPassword':password,'enabled':True,'roleIds':[]})
            assert response.status_code == 201, 'Permission fixture creation failed'
            async with httpx.AsyncClient(base_url='http://127.0.0.1:18081', trust_env=False) as restricted:
                token = (await restricted.get('/api/v1/auth/csrf')).json()['token']
                response = await restricted.post('/api/v1/auth/login', headers={'X-CSRF-TOKEN':token}, json={'username':username,'password':password})
                assert response.status_code == 200, 'Restricted login failed'
                assert (await restricted.get('/api/v1/ai/conversations')).status_code == 403, 'Sales permission not enforced'
        await check('unauthorized', denied,layer='java-access-control')
        # Real external model error and real connection error, agent scope only.
        async def fault(kind):
            config = replace(settings, model='evaluation-nonexistent-model', planner_model='evaluation-nonexistent-model') if kind == 'model_failure' else replace(settings, erp_base_url='http://127.0.0.1:1')
            agent = SalesAgent(config)
            events = [e async for e in agent.events('2026-10-01至2026-10-02销售', {'gatewayToken':'invalid-evaluation-capability'}, {})]
            assert not any(k=='result' for k,v in events), 'Failure produced report'
            if kind=='model_failure':
                assert any(k=='error' and v['code']=='MODEL_REQUEST_FAILED' for k,v in events), 'Wrong real model fault outcome'
                assert events[-1] == ('done', {'status':'error'}), 'Wrong model fault terminal status'
            else:
                assert any(v.get('execution',{}).get('toolCalls',0)>0 for k,v in events),'Real tool failure was not attempted'
                assert events[-1][1]['status'] in ('error','clarification'),'Tool failure cannot complete as business success'
        for kind in ('model_failure','tool_failure'):
            await check(kind, lambda k=kind: fault(k), scope='agent-real-network-fault;not-java-relay')
    return {'suite':'ai-baseline-v3', 'database':'mini_mfg_test', 'model':settings.model,
            'calendarClock': '2026-10-20' if os.getenv('AI_EVAL_CALENDAR') == '1' else None, 'results':records}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    try:
        report = asyncio.run(run())
    except Exception as exc:
        report = {'suite':'ai-baseline-v3', 'results':[{'case':'preflight','layer':'real-model','status':'failed',
                   'seconds':0, 'reason':str(exc) if isinstance(exc,AssertionError) else type(exc).__name__}]}
    (OUT/'live.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    raise SystemExit(any(r['status']=='failed' for r in report['results']))

if __name__ == '__main__': main()
