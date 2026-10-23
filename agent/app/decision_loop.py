"""Model -> validated tools -> model. No rule-based production intent router."""
import asyncio
import json
import re
from dataclasses import dataclass,field
from datetime import date
from typing import TypedDict
from langgraph.graph import StateGraph,START,END
from langgraph.config import get_stream_writer
from .config import Settings
from .model import ModelClient,AgentFailure
from .tools import ToolRegistry,ErpTools,TOOL_NAMES,OPERATION_TOOLS
from .dates import resolve_period,shanghai_day
from .context import clean_context,merge_context,valid_id,query_text,describe_change
from .analytics import AnalyticsEngine
from .evidence import EvidenceEngine,MissingEvidence,render_evidence
from .dimensions import DimensionAnalysis
from .operations import OperationsAnalysis
from .privacy import redact_question
from .decision_protocol import decision_schemas
from .telemetry import begin,finish

MAX_MESSAGES_BYTES=48000
MAX_ROUNDS=8
CAPABILITY='支持只读销售总览、客户/商品销售分析、当前库存、低库存、已确认工单缺料与待入库查询。金额为含税已发货订单金额，不是收入或利润；不支持历史库存、预测或业务写操作。'


def sales_basis(message, context):
    """Only explicit user wording may change the persisted date definition."""
    shipped = bool(re.search(r'按(?:实际)?发货(?:时间|日期|日)|实际发货|(?<!已)发货销售|发货金额|发货日期口径|shipped_at', message, re.I))
    created = bool(re.search(r'按(?:订单)?创建(?:时间|日期|日)|创建日期口径|created_at', message, re.I))
    if shipped and created:raise ValueError('Choose one sales date basis')
    if re.search(r'按(?:确认|下单|成交|付款|收款|签收)(?:时间|日期)', message):raise ValueError('Unsupported or ambiguous date basis')
    return 'shipped_at' if shipped else 'created_at' if created else context.get('dateBasis', 'created_at')

def evidence_key(name,args):
    return (args.get('templateId') or name) if name=='execute_safe_query' else {'get_sales_summary':'sales_summary','get_sales_trend':'sales_trend'}.get(name,name)

def object_scope(name,args):
    fields={'warehouseId':'warehouse','materialId':'material','productionOrderId':'production','entityId':'entity:'+str(args.get('dimension'))}
    return {kind:{args[key]} for key,kind in fields.items() if key in args}

def public_data(value):
    if isinstance(value,dict):return {k:('既有业务应用服务受控查询' if k=='source' else public_data(v)) for k,v in value.items() if k not in ('gatewayToken','authorization','password','phone','email','contact','sql')}
    if isinstance(value,list):return [public_data(x) for x in value]
    if isinstance(value,str):
        value=re.sub(r'sales_order\.status=SHIPPED;(?:created_at|shipped_at);deleted_at IS NULL(?:;sales_order_item\.line_amount;material_id;unit)?','已发货销售订单受控统计',value)
        value=re.sub(r'\b(?:stock_balance|production_material_snapshot|stock_document_item|stock_document)\b','业务查询',value)
        return redact_question(re.sub(r'<think>.*?</think>','',value,flags=re.S))
    return value

@dataclass
class Run:
    message:str
    context:dict
    gateway:str
    today:date
    period:dict=field(default_factory=dict)
    messages:list=field(default_factory=list)
    calls:list=field(default_factory=list)
    ids:dict=field(default_factory=dict)
    user_ids:dict=field(default_factory=dict)
    reports:dict=field(default_factory=dict)
    latest:dict=field(default_factory=dict)
    sales:dict=field(default_factory=dict)
    sources:dict=field(default_factory=dict)
    failures:dict=field(default_factory=dict)
    candidates:list=field(default_factory=list)
    initial:dict=field(default_factory=dict)
    seen_calls:set=field(default_factory=set)
    rounds:int=0
    repairs:int=0
    tool_attempts:int=0
    outcome:dict|None=None
    status:str='error'

class LoopState(TypedDict):
    run:Run
    route:str

class ModelDrivenAgent:
    def __init__(self,settings:Settings,model=None,tools=None,*,clock=None):
        self.settings=settings;self.model=model or ModelClient(settings);self.clock=clock or shanghai_day
        self.tools=ToolRegistry(tools or ErpTools(settings));self.analytics=AnalyticsEngine();self.evidence=EvidenceEngine()
        graph=StateGraph(LoopState);graph.add_node('Decision',self.decision);graph.add_node('Tools',self.execute)
        graph.add_edge(START,'Decision');graph.add_conditional_edges('Decision',lambda s:s['route'],{'tools':'Tools','again':'Decision','done':END});graph.add_edge('Tools','Decision')
        self.graph=graph.compile()

    @staticmethod
    def stage(node,message):get_stream_writer()(('stage',{'node':node,'message':message}))
    def save(self,r,update,completed=False):
        if update.get('domain')=='sales' or update.get('metric')=='sales' or update.get('dimension') in ('customer','product'):
            update={'dateBasis':r.context.get('dateBasis','created_at'),**update}
        before=r.context;r.context=merge_context(before,update,completed=completed,today=r.today)
        get_stream_writer()(('stage',{'node':'Context','message':describe_change(before,r.context),'context':r.context}))

    async def reporter(self,state):
        # Reuse the existing deterministic report, never its rule-based planner.
        from .graph import RuleWorkflow
        return await RuleWorkflow.reporter(self,state)

    async def decision(self,state):
        r=state['run'];r.rounds+=1
        if r.rounds>MAX_ROUNDS:raise AgentFailure('DECISION_LIMIT','模型决策达到轮次上限，尚未回答的问题需缩小范围后重试。')
        if len(json.dumps(r.messages,ensure_ascii=False).encode())>MAX_MESSAGES_BYTES:raise AgentFailure('CONTEXT_LIMIT','本轮工具上下文达到上限，请缩小查询范围。')
        self.stage('Decision','模型根据问题、必要条件和工具结果决定下一步')
        reply=await self.model.decide(r.messages)
        if not isinstance(reply,dict):raise AgentFailure('INVALID_MODEL_REPLY','模型没有返回有效决策。')
        calls=reply.get('tool_calls') or []
        if calls:
            if not isinstance(calls,list) or len(calls)>self.settings.max_tool_calls-r.tool_attempts:raise AgentFailure('AI_TOOL_LIMIT','模型请求工具超过本轮上限，未执行超限调用。')
            clean=[]
            for call in calls:
                id=call.get('id');fn=call.get('function',{})
                if not isinstance(id,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',id) or id in r.seen_calls or not isinstance(fn.get('name'),str) or not isinstance(fn.get('arguments'),str) or len(fn['arguments'].encode())>4096:raise AgentFailure('INVALID_MODEL_PLAN','模型工具调用格式或调用ID无效。')
                r.seen_calls.add(id);clean.append({'id':id,'type':'function','function':{'name':fn['name'],'arguments':fn['arguments']}})
            r.calls=clean;r.messages.append({'role':'assistant','content':None,'tool_calls':clean})
            return {'route':'tools'}
        try:
            action=json.loads(reply.get('content') or '')
            if not isinstance(action,dict) or set(action)-{'action','reportIds','answerKind','question','content','conditions'}:raise ValueError()
            if action.get('action')=='clarify':
                question=action.get('question')
                question={'warehouse':'operation','material':'operation','production':'operation','customer':'entity','product':'entity'}.get(question,question)
                if question not in ('dates','metric','profit','revenue','entity','operation','evidence'):raise ValueError()
                text=action.get('content');text=text if isinstance(text,str) and 0<len(text)<=800 else ''
                if question=='profit':text='当前系统没有可核算利润的成本口径，不能用销售额代替利润。是否改为分析已发货销售额和订单趋势？'
                if question=='revenue':text='没有收入确认口径，不能把含税已发货订单金额称为收入。是否改为分析销售额和订单数？'
                if r.failures:question='evidence'
                if not text or re.search(r'\d+(?:\.\d+)?\s*(?:元|%)|<think>|\bSELECT\b',text,re.I):text='请补充日期、指标或明确对象；当前没有形成完整分析结论。'
                allowed={x for ids in r.ids.values() for x in ids}|{c['id'] for c in r.candidates}
                if any(id not in allowed for id in re.findall(r'\bID\s*([1-9]\d*)',text,re.I)):raise ValueError()
                conditions=action.get('conditions',{})
                allowed_fields={'domain','metric','dimension','analysisOperation','entityQuery','rankingLimit','entityId','operation','operationsArgs','operationsLookup'}
                if not isinstance(conditions,dict) or conditions.keys()-allowed_fields or len(json.dumps(conditions,ensure_ascii=False).encode())>1600:raise ValueError()
                if question in ('profit','revenue','metric'):
                    conditions.pop('metric',None);r.context.pop('metric',None)
                if question=='operation' and not (conditions.get('operation') or r.context.get('operation')):raise ValueError()
                # A pure ID reply completes the model's persisted operation, rather
                # than reopening its already-confirmed purpose. This validates a
                # decision; it never dispatches a tool without a model request.
                if question=='operation' and re.fullmatch(r'\s*(?:仓库|物料|工单)\s*ID\s*[:：]?\s*[1-9]\d*\s*',r.message,re.I) and r.initial.get('operation') and not r.initial.get('operationsLookup'):
                    name=r.initial['operation']['name']
                    needed=next(t['function']['parameters']['required'] for t in decision_schemas() if t['function']['name']==name)
                    kinds={'warehouseId':'warehouse','materialId':'material','productionOrderId':'production'}
                    if all(r.ids.get(kinds[k]) for k in needed):raise ValueError()
                if conditions.get('metric')=='sales' and r.context.get('metric')!='sales' and not any(w in r.message for w in ('销售','订单','卖','采购','发货金额')):raise ValueError()
                for kind,ids in object_scope('',{**conditions,**(conditions.get('operationsArgs') or {})}).items():
                    if not ids<=r.ids.get(kind,set()):raise ValueError()
                self.save(r,{**r.period,**conditions,'question':question})
                if question in ('entity','operation') and r.candidates:
                    text+='\n后端候选：'+'；'.join('ID '+c['id']+' / '+str(c.get('code',''))+' / '+str(c.get('name','')) for c in r.candidates)
                if question=='evidence' and r.failures:text+='\n尚未回答：'+'、'.join(r.failures)+'。失败结果不是零。'
                r.status='clarification';r.outcome={'content':redact_question(text),'context':r.context}
            elif action.get('action')=='answer':
                refs=action.get('reportIds')
                if refs:
                    if not isinstance(refs,list) or len(refs)>4 or len(refs)!=len(set(refs)) or any(not isinstance(x,str) or x not in r.reports or r.latest.get(r.reports[x]['family'])!=x for x in refs):raise ValueError()
                    # Explicit objects in this question must be represented in its answer,
                    # even when another historical object still has valid query permissions.
                    for kind,ids in r.user_ids.items():
                        answered=set().union(*(r.reports[x].get('scope',{}).get(kind,set()) for x in refs))
                        if ids and not ids<=answered:raise ValueError()
                    if r.failures:raise AgentFailure('INCOMPLETE_EVIDENCE','仍有工具或证据未完成，不能以完整报告结束，请重试或缩小范围。')
                    self.save(r,{},completed=True);r.outcome={'content':'\n\n'.join(r.reports[x]['content'] for x in refs),'context':r.context}
                elif action.get('answerKind') in ('capability','greeting','unsupported') and not r.tool_attempts:
                    r.outcome={'content':CAPABILITY,'context':r.context}
                else:raise ValueError()
                r.status='completed'
            else:raise ValueError()
        except (ValueError,TypeError,KeyError):
            if r.repairs>=2:raise AgentFailure('UNVERIFIED_MODEL_ANSWER','模型未引用本轮已核验证据或未返回有效澄清，未发布未经核验的回答。')
            r.repairs+=1
            r.messages.append({'role':'user','content':json.dumps({'executorFeedback':'UNVERIFIED_MODEL_ANSWER','message':'本轮回答未通过核验。报告ID不能跨轮复用，必须先调用相关工具并引用verifiedReports。澄清question只允许dates/metric/profit/revenue/entity/operation/evidence；操作澄清须conditions保存operation={name:实际工具名}。用户只补ID且已保存操作目的时，不得重复询问当前库存还是预警，应请求工具。缺日期须保存已明确指标、维度和名称查询。','currentOperation':r.context.get('operation'),'groundedIds':{k:sorted(v) for k,v in r.ids.items()},'availableReportIds':list(r.reports)},ensure_ascii=False)})
            return {'route':'again'}
        return {'route':'done'}

    def arguments(self,r,name,args):
        definitions={x['function']['name']:x['function']['parameters'] for x in decision_schemas()}
        if name not in definitions or not isinstance(args,dict):raise ValueError('Unknown tool')
        schema=definitions[name]
        if args.keys()-schema['properties'].keys() or any(k not in args for k in schema['required']):raise ValueError('Invalid arguments')
        # Execution policy, not intent routing: unsupported financial definitions cannot be silently changed.
        if name not in OPERATION_TOOLS:
            if not r.period:raise ValueError('Sales period is missing/invalid')
            basis=sales_basis(r.message,r.context)
            r.context['dateBasis']=basis
            if any(w in r.message for w in ('利润','毛利','收入','营收')):raise ValueError('Unsupported metric must be clarified')
            accepted=r.initial.get('question') in ('metric','revenue','profit') and bool(re.match(r'^(?:可以|同意|是的|好的|好[，。])',r.message))
            if r.context.get('metric')!='sales' and not accepted and not any(w in r.message for w in ('销售','订单','卖','采购','发货金额')):raise ValueError('Metric requires explicit confirmation')
            if r.context.get('entityId') and name in ('get_sales_summary','get_sales_trend','execute_safe_query') and not any(w in r.message for w in ('全厂','总体','全部')):raise ValueError('Cannot widen entity scope')
        else:
            if name!='resolve_operations_entity' and re.search(r'昨天|历史|上个?月|上周|截至|\d{4}[-年]',r.message):raise ValueError('Historical stock unsupported')
        mapping={'entityId':'entity:'+str(args.get('dimension')),'warehouseId':'warehouse','materialId':'material','productionOrderId':'production'}
        for key,kind in mapping.items():
            if key in args and (not valid_id(args[key]) or args[key] not in r.ids.get(kind,set())):raise ValueError('ID not grounded')
        if name in ('get_current_stock','get_low_stock_alerts') and r.context.get('materialId') and 'materialId' not in args and not any(w in r.message for w in ('全仓','所有','全部','不限物料')):raise ValueError('Cannot drop material scope')
        if 'limit' in args and (type(args['limit'])!=int or not 1<=args['limit']<=20):raise ValueError('Invalid limit')
        if 'query' in args and not query_text(args['query']):raise ValueError('Invalid lookup query')
        arguments={**(r.period if name not in OPERATION_TOOLS else {}),**{k:v for k,v in args.items() if k!='operation'}}
        if name not in OPERATION_TOOLS:arguments['dateBasis']=basis
        if name=='resolve_operations_entity' and args.get('operation') not in OPERATION_TOOLS-{'resolve_operations_entity'}:raise ValueError('Invalid requested operation')
        scope=({'operation':name,'arguments':arguments} if name in OPERATION_TOOLS else {'dimension':args.get('dimension'),'entityId':args.get('entityId'),'query':args.get('query')} if name in ('resolve_sales_entity','get_entity_sales') else {'dimension':'customer' if name=='get_customer_sales_ranking' else 'product'} if name.endswith('_ranking') else None)
        return arguments,scope

    async def render(self,r,name,args,data):
        content=None;family=name
        if name in ('get_sales_summary','get_sales_trend','execute_safe_query'):
            kind=('sales_summary' if name=='get_sales_summary' or args.get('templateId')=='sales_summary' else 'sales_trend')
            self.evidence.metadata(data,r.period,name)
            if kind=='sales_summary':self.evidence.totals(data)
            r.sales[kind]=data;r.sources[kind]=name
            if {'sales_summary','sales_trend'}<=r.sales.keys():
                metrics=self.analytics.analyze(r.sales['sales_summary'],r.sales['sales_trend'])
                dossier=self.evidence.sales(r.sales['sales_summary'],r.sales['sales_trend'],r.period,tools=tuple(r.sources[k] for k in ('sales_summary','sales_trend')))
                content=(await self.reporter({'period':r.period,'results':r.sales,'analysis':metrics,'evidence':dossier}))['content'];family='sales_total'
        elif name in ('get_entity_sales','get_customer_sales_ranking','get_product_sales_ranking'):
            # Existing analyzer is replayed strictly as a validator/renderer. No hidden network reads.
            class Replay:
                async def execute(_,requested,arguments,period,token,scope=None):
                    if requested!=name:raise ValueError('Unexpected renderer query')
                    return data
            dim=args.get('dimension') or ('customer' if name=='get_customer_sales_ranking' else 'product')
            selected={'dimension':dim,'operation':'entity' if name=='get_entity_sales' else 'ranking','limit':args.get('limit',10),'entityId':args.get('entityId'),'query':''}
            rendered=await DimensionAnalysis(Replay(),self.settings,self.reporter,self.stage).run({'dimension_intent':selected,'period':r.period,'gateway_token':''})
            if rendered.get('route')!='completed':raise MissingEvidence('Dimension result incomplete')
            content=rendered['content'];family='entity:'+dim if name=='get_entity_sales' else name
        elif name not in ('resolve_sales_entity','resolve_operations_entity'):
            if name=='get_production_shortage' and str(data.get('productionOrderId'))!=args['productionOrderId']:raise ValueError('Work order scope mismatch')
            for row in data.get('rows',[]):
                for key in ('warehouseId','materialId'):
                    if key in args and str(row.get(key))!=args[key]:raise ValueError('Tool scope mismatch')
            content=OperationsAnalysis(None).render(name,data)
        reports=[]
        if content:
            id='r'+str(len(r.reports)+1);r.reports[id]={'family':family,'content':content,'scope':object_scope(name,args)};r.latest[family]=id
            reports.append({'id':id,'content':public_data(content)})
        if name=='get_customer_sales_ranking' and args.get('limit',10)==4:r.sales['customer_ranking']=data
        if name in ('get_sales_summary','get_customer_sales_ranking') and {'sales_summary','customer_ranking'}<=r.sales.keys():
            dossier=self.evidence.concentration(r.sales['sales_summary'],r.sales['customer_ranking'],r.period)
            content=f'客户集中度：{r.period["startDate"]} 至 {r.period["endDate"]}（结束日期不含当天，Asia/Shanghai）\n\n口径：含税已发货订单金额，不是收入或利润。\n'+render_evidence(dossier)
            id='r'+str(len(r.reports)+1);r.reports[id]={'family':'customer_concentration','content':content};r.latest['customer_concentration']=id
            reports.append({'id':id,'content':public_data(content)})
        return reports

    async def execute(self,state):
        r=state['run'];self.stage('Tools','校验模型工具请求并调用现有业务服务')
        for call in r.calls:
            name=call['function']['name'];r.tool_attempts+=1;args={}
            try:
                args=json.loads(call['function']['arguments']);arguments,scope=self.arguments(r,name,args)
                if name=='resolve_sales_entity':self.save(r,{**r.period,'domain':'sales','metric':'sales','dimension':args['dimension'],'entityQuery':args['query'],'analysisOperation':'entity'})
                elif name=='resolve_operations_entity':
                    field={'warehouse':'warehouseId','receiptWarehouse':'warehouseId','material':'materialId','production':'productionOrderId'}[args['dimension']]
                    held={k:v for k,v in r.context.get('operationsArgs',{}).items() if k!=field}
                    self.save(r,{'domain':'operations','operation':{'name':args['operation']},'operationsArgs':held,'operationsLookup':{'dimension':args['dimension'],'query':args['query'],'field':field}})
                elif name in OPERATION_TOOLS:self.save(r,{'domain':'operations','operation':{'name':name},'operationsArgs':{k:v for k,v in args.items() if k.endswith('Id')}})
                else:
                    update={**r.period,'domain':'sales','metric':'sales','analysisOperation':'entity' if name=='get_entity_sales' else 'ranking' if name.endswith('_ranking') else 'total',**{k:v for k,v in args.items() if k in ('dimension','entityId')}}
                    if name.endswith('_ranking'):
                        for key in ('entityId','entityQuery','confirmedOptions'):r.context.pop(key,None)
                        update.update(dimension='customer' if name=='get_customer_sales_ranking' else 'product',rankingLimit=args.get('limit',10))
                    self.save(r,update)
                data=await self.tools.execute(name,arguments,r.period,r.gateway,scope=scope)
                if name.startswith('resolve_'):
                    candidates=data.get('candidates');dimension=args['dimension']
                    if not isinstance(candidates,list) or len(candidates)>10 or any(not isinstance(c,dict) or not valid_id(c.get('id')) for c in candidates) or len({c['id'] for c in candidates})!=len(candidates):raise ValueError('Invalid candidates')
                    r.candidates=candidates
                    kind='entity:'+dimension if name=='resolve_sales_entity' else 'warehouse' if dimension=='receiptWarehouse' else dimension
                    # A fresh name lookup supersedes the previous active object in this namespace.
                    r.ids[kind]=set(r.user_ids.get(kind,set()))
                    selected=re.search(r'(?:选择|选)\s*(?:客户|商品|产品|仓库|物料|工单)?\s*ID\s*([1-9]\d{0,18})(?!\d)',r.message,re.I)
                    pending=(r.initial.get('entityQuery')==args['query'] and r.initial.get('dimension')==dimension) if name=='resolve_sales_entity' else (r.initial.get('operationsLookup',{}).get('query')==args['query'] and r.initial.get('operationsLookup',{}).get('dimension')==dimension)
                    if selected:
                        if pending and not data.get('truncated') and selected[1] in {c['id'] for c in candidates}:r.ids.setdefault(kind,set()).add(selected[1])
                    elif not data.get('truncated') and len(candidates)==1:r.ids.setdefault(kind,set()).add(candidates[0]['id'])
                reports=await self.render(r,name,args,data)
                r.failures.pop(evidence_key(name,args),None)
                projected=dict(data)
                if isinstance(projected.get('points'),list) and len(projected['points'])>31:projected={'period':projected.get('period'),'pointCount':len(projected['points']),'dailyPointsOmitted':True}
                payload={'ok':True,'data':public_data(projected),'verifiedReports':reports,'remainingToolCalls':self.settings.max_tool_calls-r.tool_attempts}
            except asyncio.CancelledError:raise
            except Exception as error:
                if getattr(getattr(error,'response',None),'status_code',None) in (401,403):raise AgentFailure('FORBIDDEN','当前用户没有该工具权限或分析授权已失效，请核对权限后重试。') from error
                code='INVALID_TOOL_REQUEST' if isinstance(error,(ValueError,TypeError,KeyError)) else 'ERP_TOOL_FAILED'
                r.failures[evidence_key(name,args if isinstance(args,dict) else {})]=code
                payload={'ok':False,'code':code,'message':'工具请求未通过参数、日期、对象范围、权限或数据校验；未生成该项结论。请修正请求或澄清。','remainingToolCalls':self.settings.max_tool_calls-r.tool_attempts}
            r.messages.append({'role':'tool','tool_call_id':call['id'],'content':json.dumps(payload,ensure_ascii=False)})
        return {'route':'decision'}

    async def events(self,message,context,resume):
        meter=begin();today=shanghai_day(self.clock());initial=clean_context(resume or {},today)
        retry=message.strip() in ('重试','再试一次','继续分析')
        original=initial.get('retryRequest') if retry else None
        if not retry:initial.pop('retryRequest',None)
        r=Run(original or message,initial,context.get('gatewayToken',''),today,initial=dict(initial))
        try:
            try:r.period=resolve_period(message,initial,today=today) or {};period_error=None
            except ValueError:r.period={};period_error='用户日期无效或不明确；不得回退旧日期。'
            if retry and original and not r.period:
                try:r.period=resolve_period(original,{},today=today) or {}
                except ValueError:pass
            try:r.context['dateBasis']=sales_basis(r.message,r.context)
            except ValueError:period_error='日期口径含混或不支持；请澄清按创建时间还是实际发货时间。'
            if r.period:r.context.update(r.period)
            else:
                for k in ('startDate','endDate'):r.context.pop(k,None)
            labels={'客户':'entity:customer','商品':'entity:product','产品':'entity:product','仓库':'warehouse','物料':'material','工单':'production'}
            for label,kind in labels.items():
                selecting=bool(re.match(r'^\s*(?:选择|选)',r.message)) and bool(initial.get('entityQuery') or initial.get('operationsLookup'))
                if not selecting:r.user_ids.setdefault(kind,set()).update(id for id in re.findall(label+r'\s*ID\s*[:：]?\s*([1-9]\d{0,18})(?!\d)',r.message,re.I) if valid_id(id))
            r.ids={k:set(v) for k,v in r.user_ids.items()}
            for key,kind in (('lastCustomerId','entity:customer'),('lastProductId','entity:product'),('warehouseId','warehouse'),('materialId','material'),('productionOrderId','production')):
                if valid_id(initial.get(key)):r.ids.setdefault(kind,set()).add(initial[key])
            if initial.get('dimension') and valid_id(initial.get('entityId')):r.ids.setdefault('entity:'+initial['dimension'],set()).add(initial['entityId'])
            for kind,ids in r.user_ids.items():
                if ids:r.ids[kind]=set(ids)
            r.messages=[{'role':'user','content':json.dumps({'question':redact_question(r.message),'context':r.context,'today':str(today),'timezone':'Asia/Shanghai','period':r.period,'periodError':period_error,'userIds':{k:sorted(v) for k,v in r.user_ids.items()}},ensure_ascii=False)}]
            async with asyncio.timeout(150):
                async for mode,value in self.graph.astream({'run':r,'route':'decision'},stream_mode=['custom','updates'],config={'recursion_limit':20}):
                    if mode=='custom' and value[0] in ('stage',):yield value
            if not r.outcome:raise AgentFailure('INCOMPLETE_DECISION','模型没有完成有效回答或澄清。')
            yield ('result' if r.status=='completed' else 'clarification'),{**r.outcome,'execution':finish()}
        except asyncio.CancelledError:raise
        except AgentFailure as error:yield 'error',{'code':error.code,'message':error.message,'context':merge_context(r.context,{'question':'retry','retryRequest':redact_question(r.message)[:400]},today=today),'execution':finish()}
        except TimeoutError:yield 'error',{'code':'ANALYSIS_TIMEOUT','message':'分析超过时间上限，请缩小范围后重试。','context':r.context,'execution':finish()}
        except Exception:yield 'error',{'code':'ANALYSIS_FAILED','message':'模型决策或数据校验失败，未发布未经核验的报告。','context':r.context,'execution':finish()}
        finally:meter.close()
        yield 'done',{'status':r.status}
