"""Isolated fixed-clock browser acceptance entry; never use for development.

The cancellation probe adds latency only, never supplies business data.
All real reads and current permissions still go through Java.
"""
import asyncio
import os
from dataclasses import replace
from datetime import date
from app.config import Settings
from app.graph import SalesAgent
from app.main import create_app
from app.tools import ErpTools
from evaluation.acceptance_probe import select_probe

if os.getenv('AI_LIVE_TEST_DATABASE')!='mini_mfg_test' or os.getenv('AI_ACCEPTANCE')!='1':raise RuntimeError('Acceptance requires explicit isolated test guards')
settings=Settings.from_env()
if settings.erp_base_url!='http://127.0.0.1:18081':raise RuntimeError('Acceptance ERP must use isolated test port')
live=os.getenv('AI_EVAL_LIVE')=='1'
if not live:settings=replace(settings,api_key='',model='',planner_model='',reflection_model='')
elif not settings.api_key or not settings.model:raise RuntimeError('Explicit live acceptance requires real model configuration')

class DelayedReads(ErpTools):
    async def call(self,*args,**kwargs):
        await asyncio.sleep(4)
        return await super().call(*args,**kwargs)

class Acceptance:
    async def events(self,message,context,resume):
        delayed=message.startswith('验收取消：')
        if delayed:message=message.removeprefix('验收取消：')
        selected,message=select_probe(settings,message,live)
        agent=SalesAgent(selected,tools=DelayedReads(selected) if delayed else ErpTools(selected),clock=lambda:date(2026,10,20))
        async for item in agent.events(message,context,resume):yield item

app=create_app(settings,Acceptance())
