import json
from datetime import date
from typing import Protocol, Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .config import Settings


class ToolArgs(BaseModel):
    model_config = ConfigDict(extra='forbid')
    dateBasis: Literal["created_at", "shipped_at"] = "created_at"
    startDate: date
    endDate: date
    templateId: str | None = None

    @model_validator(mode='after')
    def valid_period(self):
        if not 0 < (self.endDate - self.startDate).days <= 366:
            raise ValueError('日期跨度须在1至366天内，结束日期不含当天')
        if self.templateId not in (None, 'sales_summary', 'sales_trend'):
            raise ValueError('Only approved sales templates are allowed')
        return self


LEGACY_TOOLS = {'get_sales_summary', 'get_sales_trend', 'execute_safe_query'}
DIMENSION_TOOLS = {'resolve_sales_entity', 'get_entity_sales', 'get_customer_sales_ranking', 'get_product_sales_ranking'}
OPERATION_TOOLS = {'get_current_stock','get_low_stock_alerts','get_production_shortage','get_pending_production_receipts','resolve_operations_entity'}
TOOL_NAMES = LEGACY_TOOLS | DIMENSION_TOOLS | OPERATION_TOOLS


class OperationsArgs(BaseModel):
    model_config = ConfigDict(extra='forbid')
    warehouseId: str | None = Field(default=None, pattern=r'^[1-9][0-9]{0,18}$')
    materialId: str | None = Field(default=None, pattern=r'^[1-9][0-9]{0,18}$')
    productionOrderId: str | None = Field(default=None, pattern=r'^[1-9][0-9]{0,18}$')
    dimension: str | None = None
    query: str | None = Field(default=None, min_length=1, max_length=100)
    limit: int | None = Field(default=None, ge=1, le=20, strict=True)


class RankingArgs(BaseModel):
    model_config = ConfigDict(extra='forbid')
    dateBasis: Literal["created_at", "shipped_at"] = "created_at"
    startDate: date
    endDate: date
    limit: int = Field(default=10, ge=1, le=20, strict=True)


class ResolveArgs(BaseModel):
    model_config = ConfigDict(extra='forbid')
    dateBasis: Literal["created_at", "shipped_at"] = "created_at"
    startDate: date
    endDate: date
    dimension: str
    query: str = Field(min_length=1, max_length=120)


class EntityArgs(BaseModel):
    model_config = ConfigDict(extra='forbid')
    dateBasis: Literal["created_at", "shipped_at"] = "created_at"
    startDate: date
    endDate: date
    dimension: str
    entityId: str = Field(pattern=r'^[1-9][0-9]{0,18}$')



def validate_call(name: str, arguments: dict, period: dict, *, scope=None):
    if name not in TOOL_NAMES:
        raise ValueError('Unknown tool')
    if name in OPERATION_TOOLS:
        if not scope or scope.get('operation') != name or scope.get('arguments') != arguments:
            raise ValueError('Operational identity must come from deterministic routing')
        args = OperationsArgs.model_validate(arguments).model_dump(mode='json', exclude_none=True)
        for key in ('warehouseId','materialId','productionOrderId'):
            if key in args and int(args[key]) > 9223372036854775807:
                raise ValueError('ID outside backend range')
        allowed = {'warehouseId','materialId','limit'}
        if name == 'resolve_operations_entity':
            allowed={'dimension','query'}
            if args.get('dimension') not in ('warehouse','receiptWarehouse','material','production') or not args.get('query'):
                raise ValueError('Invalid entity lookup')
        elif name == 'get_production_shortage':
            allowed={'productionOrderId'}
            if not args.get('productionOrderId'): raise ValueError('Work order required')
        elif name == 'get_pending_production_receipts': allowed={'warehouseId','limit'}
        if args.keys()-allowed: raise ValueError('Unexpected operational parameters')
        return args
    if name in DIMENSION_TOOLS:
        if not scope or scope.get('dimension') not in ('customer', 'product'):
            raise ValueError('Dimension tool requires deterministic scope')
        if name.endswith('_ranking'):
            if name != 'get_'+scope['dimension']+'_sales_ranking':
                raise ValueError('Ranking dimension mismatch')
            args = RankingArgs.model_validate(arguments)
        else:
            args = (ResolveArgs if name == 'resolve_sales_entity' else EntityArgs).model_validate(arguments)
            if args.dimension != scope['dimension']:
                raise ValueError('Dimension mismatch')
            key = 'query' if name == 'resolve_sales_entity' else 'entityId'
            if getattr(args, key) != scope.get(key):
                raise ValueError('Model may not choose entity identity')
            if key == 'entityId' and int(args.entityId) > 9223372036854775807:
                raise ValueError('ID outside backend range')
        result = args.model_dump(mode='json')
        if 'dateBasis' not in arguments:result.pop('dateBasis',None)
        ToolArgs.model_validate({k:result[k] for k in ('startDate','endDate')})
        if any(result[k] != period[k] for k in ('startDate','endDate')):
            raise ValueError('Date mismatch')
        return result
    args = ToolArgs.model_validate(arguments)
    if name == 'execute_safe_query' and args.templateId is None:
        raise ValueError('Template is required')
    if name != 'execute_safe_query' and args.templateId is not None:
        raise ValueError('Unexpected template')
    result = args.model_dump(mode='json', exclude_none=True)
    if 'dateBasis' not in arguments:result.pop('dateBasis',None)
    if any(result[key] != period[key] for key in ('startDate', 'endDate')):
        raise ValueError('Model may not alter resolved dates')
    return result


def schemas():
    common = {'startDate': {'type': 'string', 'description': 'Shanghai ISO date inclusive'},
              'endDate': {'type': 'string', 'description': 'Shanghai ISO date exclusive'}}
    tools = []
    for name in sorted(LEGACY_TOOLS):
        properties = dict(common)
        if name == 'execute_safe_query':
            properties['templateId'] = {'type': 'string', 'enum': ['sales_summary', 'sales_trend']}
        tools.append({'type': 'function', 'function': {'name': name,
                      'description': '只读已发货销售汇总' if name == 'get_sales_summary' else
                                     '只读逐日销售趋势' if name == 'get_sales_trend' else
                                     'TextSQL受控查询：只选预审核模板，不接受SQL文本',
                      'parameters': {'type': 'object', 'properties': properties,
                                     'required': list(properties), 'additionalProperties': False}}})
    return tools


class ErpTools:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def call(self, name: str, arguments: dict, gateway_token: str):
        if not self.settings.service_token:
            raise ValueError('ERP tool service authentication is not configured')
        async with httpx.AsyncClient(timeout=self.settings.tool_timeout, trust_env=False) as client:
            response = await client.post(f'{self.settings.erp_base_url.rstrip("/")}/internal/ai/tools/{name}',
                headers={'Authorization': f'Bearer {self.settings.service_token}',
                         'X-AI-Context': gateway_token}, json=arguments)
            response.raise_for_status()
            data = response.json()
            # ERP uses the standard API envelope on successful responses.
            result = data['data'] if isinstance(data, dict) and 'data' in data else data
            if name not in OPERATION_TOOLS and name != 'resolve_sales_entity':
                if not isinstance(result, dict):raise ValueError('Invalid sales evidence')
                expected = arguments.get('dateBasis', 'created_at')
                parts = [result] + ([result.get('summary', {}), result.get('trend', {})] if name == 'get_entity_sales' else [])
                if any(not isinstance(p, dict) or not isinstance(p.get('snapshotId'), str) or not p['snapshotId'] or p.get('dateBasis') != expected for p in parts):
                    raise ValueError('Sales evidence lacks a verified snapshot/date basis')
                if len({p['snapshotId'] for p in parts}) != 1:raise ValueError('Entity snapshot IDs differ')
            return result


class ToolAdapter(Protocol):
    async def call(self, name: str, arguments: dict, gateway_token: str) -> dict: ...


class ToolRegistry:
    """Explicit dispatch and validation boundary shared by every tool adapter."""
    def __init__(self, adapter: ToolAdapter):
        self._adapters = {name: adapter for name in TOOL_NAMES}

    async def execute(self, name: str, arguments: dict, period: dict, gateway_token: str, *, scope=None):
        validated = validate_call(name, arguments, period, scope=scope)
        from .telemetry import record_tool
        record_tool()
        return await self._adapters[name].call(name, validated, gateway_token)
