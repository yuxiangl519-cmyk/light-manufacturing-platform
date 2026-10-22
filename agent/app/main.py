import hmac
import json
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .config import Settings
from .graph import SalesAgent


class GatewayContext(BaseModel):
    model_config = ConfigDict(extra='forbid')
    gatewayToken: str = Field(min_length=1, max_length=256)


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    requestId: str = Field(min_length=1, max_length=128)
    conversationId: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=4000)
    context: GatewayContext
    resume: dict[str, Any] | None = None
    @field_validator('resume')
    @classmethod
    def bounded_resume(cls,value):
        if value is not None and len(json.dumps(value,ensure_ascii=False).encode('utf-8'))>4096:raise ValueError('Structured context exceeds4096 bytes')
        return value


def create_app(settings=None, agent=None):
    settings = settings or Settings.from_env()
    service = agent or SalesAgent(settings)
    application = FastAPI(title='Light Manufacturing Sales Agent', docs_url=None, redoc_url=None)

    @application.get('/health')
    async def health():
        return {'status': 'ok', 'serviceConfigured': bool(settings.service_token),
                'modelConfigured': bool(settings.api_key and settings.model)}

    @application.post('/v1/analyze')
    async def analyze_request(body: AnalyzeRequest, request: Request, authorization: str = Header(default='')):
        if not settings.service_token:
            raise HTTPException(503, 'AI service is not configured')
        if not hmac.compare_digest(authorization, f'Bearer {settings.service_token}'):
            raise HTTPException(401, 'Unauthorized')

        async def stream():
            async for event, data in service.events(body.message, body.context.model_dump(), body.resume or {}):
                if await request.is_disconnected():
                    return
                yield f'event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n'

        return StreamingResponse(stream(), media_type='text/event-stream',
                                 headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'})

    return application


app = create_app()
