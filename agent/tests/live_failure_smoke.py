"""Run only against explicit test resources during controlled upstream fault injection."""
import asyncio,json,os
from pathlib import Path
import httpx
assert os.getenv('AI_LIVE_TEST_DATABASE')=='mini_mfg_test'
expected=os.environ['AI_EXPECT_FAILURE_CODE']
assert expected in {'MODEL_REQUEST_FAILED','ERP_TOOL_FAILED'}
async def main():
 async with httpx.AsyncClient(base_url='http://127.0.0.1:18081',timeout=170,trust_env=False) as c:
  csrf=(await c.get('/api/v1/auth/csrf')).json()['token']
  r=await c.post('/api/v1/auth/login',json={'username':'admin','password':os.environ['MFG_ADMIN_PASSWORD']},headers={'X-CSRF-TOKEN':csrf});assert r.status_code==200
  csrf=(await c.get('/api/v1/auth/csrf')).json()['token'];headers={'X-CSRF-TOKEN':csrf}
  id=(await c.post('/api/v1/ai/conversations',json={'title':'受控故障验收'},headers=headers)).json()['id']
  response=await c.post(f'/api/v1/ai/conversations/{id}/messages',json={'message':'分析最近30天的销售情况'},headers=headers)
  assert response.status_code==200
  frames=[json.loads(line[5:].strip()) for line in response.text.splitlines() if line.startswith('data:')]
  assert any(frame.get('code')==expected for frame in frames), 'Expected safe failure was not returned'
  assert not any('数据来源' in frame.get('content','') for frame in frames), 'Failure must not fabricate a report'
  detail=(await c.get(f'/api/v1/ai/conversations/{id}')).json();assert detail['status']=='ERROR'
  Path(f'output/ai/{expected.lower()}.json').write_text(json.dumps({'database':'mini_mfg_test','code':expected,'status':detail['status'],'passed':True}),encoding='utf-8')
  print('EXPECTED FAILURE VERIFIED '+expected)
if __name__=='__main__':
 try:asyncio.run(main())
 except Exception as exc:print('Fault validation failed: '+type(exc).__name__);raise SystemExit(1)
