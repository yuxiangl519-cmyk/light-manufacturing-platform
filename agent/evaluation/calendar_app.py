"""Fixed-clock HTTP evaluation app only. Never use this entry point for development."""
import os
from datetime import date
from app.config import Settings
from app.graph import SalesAgent
from app.main import create_app

if os.getenv('AI_EVAL_LIVE') != '1' or os.getenv('AI_LIVE_TEST_DATABASE') != 'mini_mfg_test':
    raise RuntimeError('Calendar evaluation requires explicit live/test guards')
settings = Settings.from_env()
if settings.erp_base_url != 'http://127.0.0.1:18081' or not settings.api_key or not settings.model:
    raise RuntimeError('Calendar evaluation requires isolated ERP and real model configuration')
app = create_app(settings, SalesAgent(settings, clock=lambda: date(2026, 10, 20)))
