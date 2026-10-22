import pytest
from app.config import Settings
from evaluation.acceptance_probe import select_probe

def test_live_failure_probe_uses_real_provider_with_only_invalid_model_override():
    settings=Settings(model='configured-model',planner_model='configured-planner',api_key='test-only')
    selected,message=select_probe(settings,'验收模型失败：2026-10-01销售',True)
    assert selected.base_url==settings.base_url and selected.api_key==settings.api_key
    assert selected.model==selected.planner_model=='evaluation-nonexistent-model'
    assert message=='2026-10-01销售'
    normal,retry=select_probe(settings,'重试',True)
    assert normal is settings and retry=='重试'

def test_failure_probe_requires_explicit_live_flag():
    with pytest.raises(ValueError):select_probe(Settings(),'验收模型失败：销售',False)
