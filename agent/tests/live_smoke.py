"""Opt-in live model + Java + LangGraph validation. Never collected by pytest."""
import asyncio
import json
import os
import re
from datetime import datetime,timedelta,timezone
from pathlib import Path
import httpx

assert os.getenv('AI_LIVE_TEST_DATABASE') == 'mini_mfg_test', 'Explicit isolated-test guard required'
BASE='http://127.0.0.1:18081'
results=[]

async def main():
 async with httpx.AsyncClient(base_url=BASE,timeout=175,trust_env=False) as client:
  csrf=(await client.get('/api/v1/auth/csrf')).json()['token']
  response=await client.post('/api/v1/auth/login',headers={'X-CSRF-TOKEN':csrf},json={'username':'admin','password':os.environ['MFG_ADMIN_PASSWORD']})
  assert response.status_code==200, 'Test login failed; response suppressed'
  csrf=(await client.get('/api/v1/auth/csrf')).json()['token'];headers={'X-CSRF-TOKEN':csrf}
  async def create():
   response=await client.post('/api/v1/ai/conversations',headers=headers,json={'title':'真实模型联调'})
   assert response.status_code==201
   return response.json()['id']
  async def ask(id,message):
   events=[];event='';data=[]
   async with client.stream('POST',f'/api/v1/ai/conversations/{id}/messages',headers=headers,json={'message':message}) as stream:
    assert stream.status_code==200, f'Unexpected stream status {stream.status_code}'
    async for line in stream.aiter_lines():
     if line.startswith('event:'):event=line[6:].strip()
     elif line.startswith('data:'):data.append(line[5:].lstrip())
     elif not line and data:
      value=json.loads('\n'.join(data));data=[];events.append((event,value))
      if event=='stage':print('LIVE NODE '+value.get('node',''),flush=True)
   failures=[item['code'] for name,item in events if name=='error']
   assert not failures, f'Live agent error codes: {failures}'
   assert events[-1][0]=='done', 'Stream ended without done'
   return events
  id=await create();events=await ask(id,'分析最近30天的销售情况，并给出经营建议。')
  nodes={v['node'] for e,v in events if e=='stage'}
  assert {'Planner','Executor','Analyzer','Reflection','Reporter'} <= nodes
  report=next(v['content'] for e,v in events if e=='result')
  now=datetime.now(timezone(timedelta(hours=8)));end=(now.date()+timedelta(days=1));start=end-timedelta(days=30)
  begin=datetime.combine(start,datetime.min.time(),tzinfo=now.tzinfo).isoformat();finish=datetime.combine(end,datetime.min.time(),tzinfo=now.tzinfo).isoformat()
  expected=(await client.get('/api/v1/workbench',params={'from':begin,'to':finish})).json()['cards']['shippedSalesAmount']
  actual=re.search(r'\| 已发货销售额（元） \| ([0-9.]+)',report)
  assert actual and actual.group(1)==expected,'Report amount differs from ERP aggregate'
  assert '建议' in report and '数据来源' in report
  detail=(await client.get(f'/api/v1/ai/conversations/{id}')).json()
  assert detail['status']=='COMPLETED' and len(detail['messages'])==2
  results.append({'check':'Live DeepSeek StateGraph + ERP exact amount + MySQL history','passed':True,'conversationId':id,'salesAmount':expected,'nodes':sorted(nodes)})
  id2=await create();events=await ask(id2,'帮我分析最近的利润情况。')
  assert any(e=='clarification' and '不能' in v['content'] for e,v in events)
  detail=(await client.get(f'/api/v1/ai/conversations/{id2}')).json();assert detail['status']=='CLARIFICATION'
  continued=await ask(id2,'可以，改为最近30天全厂销售分析')
  assert any(e=='result' for e,v in continued)
  results.append({'check':'Profit clarification persisted and real model continuation completed','passed':True})
  empty=await ask(await create(),'分析1990-01-01至1990-01-07的销售情况')
  assert any(e=='result' and '无已发货销售订单数据' in v['content'] for e,v in empty)
  results.append({'check':'Live empty data reports zero with no fabricated business facts','passed':True})
  bad=await client.post('/api/v1/ai/conversations',headers=headers,json={'tenantId':'other'})
  assert bad.status_code==400
  assert (await httpx.AsyncClient(trust_env=False).get(BASE+'/api/v1/ai/conversations')).status_code==401
  results.append({'check':'Forged tenant rejected and anonymous API denied','passed':True})
  Path('output/ai').mkdir(parents=True,exist_ok=True)
  Path('output/ai/live-results.json').write_text(json.dumps({'database':'mini_mfg_test','model':os.getenv('AI_LLM_MODEL'),'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
  print('LIVE COMPLETE: '+str(len(results))+' checks',flush=True)

if __name__=='__main__':
 try:asyncio.run(main())
 except Exception as exc:
  # Third party exception strings can contain URLs; never print request headers/body or secrets.
  print('Live verification failed: '+type(exc).__name__+(' '+str(exc) if isinstance(exc,AssertionError) else ''),flush=True)
  raise SystemExit(1)
