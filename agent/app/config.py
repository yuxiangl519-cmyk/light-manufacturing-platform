"""Environment-only settings. Secrets are never included in repr/log output."""
import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    api_key: str = field(default='', repr=False)
    base_url: str = 'https://api.openai.com/v1'
    model: str = ''
    service_token: str = field(default='', repr=False)
    erp_base_url: str = 'http://127.0.0.1:8081'
    planner_model: str = ''
    reflection_model: str = ''
    model_timeout: float = 45
    tool_timeout: float = 15
    max_tool_calls: int = 4
    max_reflections: int = 2
    thinking_enabled: bool = False

    def __post_init__(self):
        if not 1 <= self.max_tool_calls <= 8 or not 1 <= self.max_reflections <= 3:
            raise ValueError('AI limits outside allowed hard caps')

    @classmethod
    def from_env(cls):
        return cls(api_key=os.getenv('AI_LLM_API_KEY', ''),
                   base_url=os.getenv('AI_LLM_BASE_URL', 'https://api.openai.com/v1'),
                   model=os.getenv('AI_LLM_MODEL', ''),
                   service_token=os.getenv('AI_SERVICE_TOKEN', ''),
                   erp_base_url=os.getenv('AI_ERP_BASE_URL', 'http://127.0.0.1:8081'),
                   planner_model=os.getenv('AI_PLANNER_MODEL', ''),
                   reflection_model=os.getenv('AI_REFLECTION_MODEL', ''),
                   max_tool_calls=int(os.getenv('AI_MAX_TOOL_CALLS', '4')),
                   max_reflections=int(os.getenv('AI_MAX_REFLECTIONS', '2')),
                   thinking_enabled=os.getenv('AI_LLM_THINKING_ENABLED', 'false').lower() == 'true')
