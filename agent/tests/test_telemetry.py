import asyncio
import pytest
from app.telemetry import begin,finish,record_tool,record_model,record_usage

def test_usage_is_provider_reported_only_and_context_does_not_include_secrets():
    token=begin()
    try:
        record_tool();record_model();record_usage({'prompt_tokens':12,'completion_tokens':3,'total_tokens':15,'secret':'never'})
        result=finish();assert result['toolCalls']==1 and result['modelCalls']==1
        assert result['usage']=={'prompt_tokens':12,'completion_tokens':3,'total_tokens':15}
        assert result['elapsedMs']>=0 and 'secret' not in str(result)
    finally:token.close()

@pytest.mark.asyncio
async def test_concurrent_runs_never_share_counters_and_missing_usage_is_null():
    async def run(n):
        token=begin()
        try:
            for _ in range(n):record_tool()
            await asyncio.sleep(0)
            return finish()
        finally:token.close()
    a,b=await asyncio.gather(run(2),run(3))
    assert a['toolCalls']==2 and b['toolCalls']==3 and a['usage'] is None

@pytest.mark.asyncio
async def test_model_client_records_only_actual_response_usage(monkeypatch):
    import httpx
    from app.model import ModelClient
    from app.config import Settings
    client_type=httpx.AsyncClient
    transport=httpx.MockTransport(lambda request:httpx.Response(200,json={'choices':[{'message':{'content':'ok','reasoning_content':'hidden'}}],'usage':{'prompt_tokens':7,'completion_tokens':2,'total_tokens':9,'details':'hidden'}}))
    monkeypatch.setattr(httpx,'AsyncClient',lambda **kwargs:client_type(transport=transport))
    scope=begin()
    try:
        await ModelClient(Settings(api_key='test-only',model='test-model')).complete([])
        stats=finish();assert stats['modelCalls']==1 and stats['usage']=={'prompt_tokens':7,'completion_tokens':2,'total_tokens':9}
    finally:scope.close()
