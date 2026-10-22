"""Explicit live failure probe for the guarded acceptance entry only."""
from dataclasses import replace

def select_probe(settings,message,live):
    if message.startswith('验收模型失败：'):
        if not live:raise ValueError('Provider failure probe requires explicit live mode')
        return replace(settings,model='evaluation-nonexistent-model',planner_model='evaluation-nonexistent-model'),message.removeprefix('验收模型失败：')
    return settings,message
