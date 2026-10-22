import asyncio
from datetime import date, timedelta
from typing import TypedDict

from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph

from .analytics import AnalyticsEngine
from .evidence import EvidenceEngine, MissingEvidence, render_evidence
from .dimensions import DimensionAnalysis, intent as dimension_intent
from .operations import OperationsAnalysis, intent as operations_intent
from .dates import resolve_period, shanghai_day
from .config import Settings
from .memory import WorkingMemory
from .context import clean_context, prepare_turn, merge_context, describe_change
from .model import AgentFailure, ModelClient
from .tools import ErpTools, ToolArgs, ToolRegistry, validate_call
from .textsql import TemplateTextSQL
from .memories.episodic import EpisodicMemory


class AgentState(TypedDict, total=False):
    message: str
    resume: dict
    gateway_token: str
    period: dict
    today: date
    plan: list[dict]
    results: dict
    analysis: dict
    question: str
    attempts: int
    tool_calls_count: int
    feedback: str
    route: str
    content: str
    dimension_intent: dict
    operation_intent: dict
    entity_prompt: str
    pending: dict
    context: dict
    missing_tools: list
    tool_errors: dict
    tool_sources: dict
    evidence: dict


class RuleWorkflow:
    def __init__(self, settings: Settings, model=None, tools=None, *, clock=None):
        self.settings = settings
        self.clock = clock or shanghai_day
        self.model = model or ModelClient(settings)
        self.tools = ToolRegistry(tools or ErpTools(settings))
        self.analytics = AnalyticsEngine()
        self.evidence = EvidenceEngine()
        self.textsql = TemplateTextSQL()
        graph = StateGraph(AgentState)
        for name in ('Planner', 'Executor', 'Analyzer', 'Reflection', 'Reporter', 'Clarification'):
            graph.add_node(name, getattr(self, name.lower()))
        graph.add_node('TextToSQL', self.text_to_sql)
        graph.add_node('Dimensions', self.dimensions)
        graph.add_node('Operations', self.operations)
        graph.add_edge(START, 'Planner')
        graph.add_conditional_edges('Planner', lambda state: state['route'],
                                    {'execute': 'Executor', 'textsql': 'TextToSQL', 'clarify': 'Clarification', 'dimension': 'Dimensions', 'operations':'Operations'})
        graph.add_edge('TextToSQL', 'Executor')
        graph.add_conditional_edges('Dimensions', lambda state: state['route'], {'clarify':'Clarification','completed':END})
        graph.add_conditional_edges('Operations', lambda state: state['route'], {'clarify':'Clarification','completed':END})
        graph.add_edge('Executor', 'Analyzer')
        graph.add_conditional_edges('Analyzer', lambda state: state['route'],
                                    {'reflect': 'Reflection', 'retry': 'Planner'})
        graph.add_conditional_edges('Reflection', lambda state: state['route'],
                                    {'report': 'Reporter', 'retry': 'Planner', 'supplement':'Executor', 'clarify':'Clarification'})
        graph.add_edge('Reporter', END)
        graph.add_edge('Clarification', END)
        self.graph = graph.compile()

    @staticmethod
    def stage(node, message):
        get_stream_writer()(('stage', {'node': node, 'message': message}))

    @staticmethod
    def remember(context):
        get_stream_writer()(('context',context))

    async def planner(self, state):
        self.stage('Planner', '识别分析范围并规划只读工具调用')
        memory = WorkingMemory(state['message'], state.get('resume', {}))
        message = memory.effective_message()
        latest = state['message']
        resume = state.get('resume', {})
        if resume.get('referenceMissing'):
            return {'question':'entity','entity_prompt':'没有可唯一沿用的已确认客户或商品ID，请提供名称或后端ID。','route':'clarify'}
        if resume.get('scopeAmbiguous'):
            return {'question':'operation','entity_prompt':'仓库切换不能静默作为销售筛选。请明确要查询当前库存还是待入库单。','route':'clarify'}
        causal_sales=any(w in latest for w in ('销售','销量','卖得')) and any(w in latest for w in ('导致','原因','为什么','缺货'))
        operational = None if causal_sales else operations_intent(latest, resume)
        if operational:
            self.remember({'domain':'operations','operation':operational,'operationsArgs':resume.get('operationsArgs',{}) if operational==resume.get('operation') else operational.get('inheritedArguments',{})})
            return {'operation_intent': operational, 'route':'operations'}
        rejected = any(word in latest for word in ('不可以', '不要', '不行', '不同意', '不分析', '不想'))
        if resume.get('question')=='evidence' and (rejected or any(w in latest for w in ('不重试','停止','不用了'))):
            return {'question':'evidence','entity_prompt':'已停止补查；尚未回答：'+ '、'.join(resume.get('missingTools',['必要证据核对与异常解释']))+'。未执行新的工具查询。','route':'clarify'}
        offered = resume.get('question') in ('metric', 'revenue', 'profit', 'dimensions')
        accepted = offered and not rejected and (
            latest.strip().startswith(('可以', '好的', '好，', '同意', '是的', '按这个', '按此')) or
            any(word in latest for word in ('销售', '订单', '全厂', '总体')))
        if offered and rejected:
            return {'question': resume['question'], 'route': 'clarify'}
        # A response to the profit question can explicitly change the supported metric.
        switched = resume.get('question') == 'profit' and accepted
        if any(word in message.lower() for word in ('利润', '毛利', 'profit', '成本')) and not switched:
            return {'question': 'profit', 'route': 'clarify'}
        dimension_switch = resume.get('question') == 'dimensions' and accepted
        # Dates come from the latest reply first, never concatenated old/new ranges.
        try:
            period = resolve_period(latest, memory.short_term_context(), today=state['today'])
            if period:
                ToolArgs.model_validate(period)
        except ValueError:
            period = None
        if not period:
            return {'question': 'dates', 'route': 'clarify'}
        if any(word in latest.lower() for word in ('收入', '营收', 'revenue')) or (
                any(word in message.lower() for word in ('收入', '营收', 'revenue')) and not accepted):
            return {'question': 'revenue', 'period': period, 'route': 'clarify'}
        dimensional = None if dimension_switch else dimension_intent(latest, resume)
        if dimensional is None and not dimension_switch and not any(w in latest for w in ('全厂','总体')):
            dimensional = dimension_intent(resume.get('originalMessage',''), resume)
        if dimensional:
            self.remember({**period,'domain':'sales','metric':'sales','dimension':dimensional['dimension'],
                'analysisOperation':dimensional.get('operation','entity'),**{k:v for k,v in (('entityId',dimensional.get('entityId')),('entityQuery',dimensional.get('query')),('rankingLimit',dimensional.get('limit'))) if v is not None}})
            return {'period': period, 'dimension_intent': dimensional, 'route':'dimension'}
        has_sales = any(word in message.lower() for word in ('销售', '销量', '订单', 'sales', '卖得', '卖了', '卖出', '卖的'))
        if not (has_sales or accepted or resume.get('metric') == 'sales'):
            return {'question': 'metric', 'period': period, 'route': 'clarify'}
        self.remember({**period,'domain':'sales','metric':'sales','analysisOperation':'total'})
        plan = await self.model.plan(message, period, state.get('feedback', ''))
        try:
            validated = [{'name': call['name'], 'arguments': validate_call(call['name'], call['arguments'], period)}
                         for call in plan]
            total_calls = state.get('tool_calls_count', 0) + len(validated)
            if total_calls > self.settings.max_tool_calls:
                raise AgentFailure('UNSAFE_TOOL_PLAN','已达到工具调用上限，未回答：反思要求的补充核对与异常解释可信度；未执行越限调用。')
        except (ValueError, KeyError, TypeError) as exc:
            raise AgentFailure('UNSAFE_TOOL_PLAN', '工具计划未通过只读模板或日期校验，已拒绝执行。') from exc
        return {'period': period, 'plan': validated,
                'route': 'textsql' if any(call['name'] == 'execute_safe_query' for call in validated) else 'execute',
                'attempts': state.get('attempts', 0) + 1, 'tool_calls_count': total_calls, 'results': state.get('results',{})}

    async def dimensions(self, state):
        return await DimensionAnalysis(self.tools,self.settings,self.reporter,self.stage).run(state)

    async def operations(self, state):
        self.stage('Operations','调用WMS与MES公开只读服务；工单需求不预留库存')
        return await OperationsAnalysis(self.tools,self.settings.max_tool_calls).run(state)

    async def text_to_sql(self, state):
        self.stage('TextToSQL', '将查询意图映射为预审核销售模板，禁止自由SQL')
        return {'plan': self.textsql.validate_plan(state['plan'], state['period'])}

    async def executor(self, state):
        self.stage('Executor', '调用ERP受控销售查询，读取真实数据')
        results = dict(state.get('results',{}));errors=dict(state.get('tool_errors',{}));sources=dict(state.get('tool_sources',{}))
        for call in state['plan']:
            args = call['arguments']
            kind = args.get('templateId') or {'get_sales_summary': 'sales_summary',
                                             'get_sales_trend': 'sales_trend'}[call['name']]
            try:
                results[kind] = await self.tools.execute(call['name'], args, state['period'], state['gateway_token'])
                sources[kind]=call['name'] if call['name']!='execute_safe_query' else 'execute_safe_query['+kind+']'
                errors.pop(kind,None)
            except Exception as exc:
                errors[kind]='工具查询失败，未把失败替换成零值'
        if not results:
            raise AgentFailure('ERP_TOOL_FAILED','ERP只读查询失败，未回答：销售汇总、逐日趋势及异常解释；未生成报告。')
        return {'results': results,'tool_errors':errors,'tool_sources':sources}

    async def analyzer(self, state):
        self.stage('Analyzer', '以Decimal计算销售额、等长上期增长、趋势及异常提示')
        results = state['results']
        missing=[]
        try:
            for kind in ('sales_summary','sales_trend'):
                if kind not in results:missing.append(kind);continue
                data=results[kind]
                if not isinstance(data,dict) or (kind=='sales_summary' and not {'current','previous'}<=data.keys()) or (kind=='sales_trend' and 'points' not in data):raise ValueError('Unrelated tool payload')
                if kind=='sales_summary':self.evidence.totals(data)
                if kind in state.get('tool_errors',{}):missing.append(kind);continue
                try:self.evidence.metadata(data,state['period'],'get_'+kind)
                except MissingEvidence:missing.append(kind)
            if missing:return {'analysis':{},'missing_tools':missing,'route':'reflect'}
            metrics = self.analytics.analyze(results['sales_summary'], results['sales_trend'])
            dossier=self.evidence.sales(results['sales_summary'],results['sales_trend'],state['period'],tools=tuple(state.get('tool_sources',{}).get(k,'get_'+k) for k in ('sales_summary','sales_trend')))
        except (ValueError, KeyError, TypeError, ArithmeticError) as exc:
            raise AgentFailure('INVALID_ERP_DATA', 'ERP返回数据不符合销售指标契约，未生成报告。') from exc
        return {'analysis': metrics, 'evidence':dossier,'missing_tools':[], 'route': 'reflect'}

    async def reflection(self, state):
        self.stage('Reflection', '检查证据完整性与分析边界，按配置限制重规划次数')
        missing=state.get('missing_tools',[])
        if missing:
            remaining=self.settings.max_tool_calls-state.get('tool_calls_count',0)
            if remaining>0 and state['attempts']<self.settings.max_reflections:
                plan=[{'name':'get_'+k,'arguments':state['period']} for k in missing[:remaining]]
                return {'plan':plan,'tool_calls_count':state.get('tool_calls_count',0)+len(plan),'attempts':state['attempts']+1,'route':'supplement'}
            text='已达到本轮工具调用或补查次数上限。未回答：'+ '、'.join('get_'+k for k in missing)+'，以及依赖它们的交叉核对和异常原因。'
            summary=state['results'].get('sales_summary')
            if summary and 'sales_summary' not in missing:
                tool=state.get('tool_sources',{}).get('sales_summary','get_sales_summary')
                text+=f'\n已查询汇总：本期含税已发货销售额{summary["current"]["salesAmount"]}元、{summary["current"]["orderCount"]}单；范围{state["period"]["startDate"]}至{state["period"]["endDate"]}（结束日排除，Asia/Shanghai）；查询时刻{summary["asOf"]}；来源{tool} / {summary["source"]}。口径：{summary["definition"]}；方法：销售单头金额求和、订单按ID计数；比较结论暂不提供。趋势核对未完成，不能据此生成完整异常报告。'
            text+='\n是否在原日期范围重新查询？'
            return {'question':'evidence','entity_prompt':text,'pending':{**state['period'],'metric':'sales','missingTools':['get_'+k for k in missing]},'route':'clarify'}
        decision = await self.model.reflect({'period': state['period'], 'metrics': state['analysis'],
                                            'sources': list(state['results'])})
        if decision == 'retry' and state['attempts'] >= self.settings.max_reflections:
            raise AgentFailure('REFLECTION_LIMIT', '达到反思上限；未回答：反思要求的额外业务核对、异常解释可信度；现有证据不能确认原因，已停止分析。')
        return {'route': decision, 'feedback': '反思要求补齐销售汇总和趋势证据。'}

    async def reporter(self, state):
        self.stage('Reporter', '根据已验证指标生成可核对报告')
        m, period = state['analysis'], state['period']
        summary = state['results']['sales_summary']
        dossier=state.get('evidence') or self.evidence.sales(summary,state['results']['sales_trend'],period,
            dimension=state.get('dimension_intent',{}).get('dimension'),scope=state.get('dimension_scope','全厂'),
            tools=state.get('evidence_tools',('get_sales_summary','get_sales_trend')))
        content = (f'销售分析：{period["startDate"]} 至 {period["endDate"]}（结束日期不含当天，上海时间）\n\n'
                   '| 指标 | 本期 | 等长上期 | 增长率 |\n|---|---:|---:|---:|\n'
                   f'| 已发货销售额（元） | {m["salesAmount"]} | {m["previousSalesAmount"]} | {m["amountGrowth"]} |\n'
                   f'| 已发货订单数 | {m["orderCount"]} | {m["previousOrderCount"]} | {m["orderGrowth"]} |\n\n'
                   f'日均销售额：{m["dailyMean"]} 元；首末日销售额比较：{m["trendDirection"]}。\n\n'
                   '异常提示：' + ('；'.join(m['anomalies']) if m['anomalies'] else '未触发日销售额超过均值两倍或零销售日规则。') +
                   '\n\n口径：按创建时间筛选当前已发货销售单，人民币含税订单金额；不是收入或利润。'
                   '\n异常为规则提示，不能推断业务原因。金额与增长率由确定性Decimal计算。')
        content += ('\n时区：Asia/Shanghai。自然周为周一至周日；最近N天包含今天。'
                    '\n本周、本月及指定的当前月份只统计到今天（含），今日数据截至读取时刻，尚未结束。'
                    '\n比较规则：紧邻本期之前的相同天数，不替换为上一个自然月或自然周；上期为零时不可比。')
        if summary.get('asOf'):
            content += f'\n数据读取时间：{summary["asOf"]}'
        start, end = date.fromisoformat(period['startDate']), date.fromisoformat(period['endDate'])
        previous_period = {'startDate': str(start-(end-start)), 'endDate': str(start)}
        content += (f'\n数据来源：{summary.get("source", "ERP已发货销售订单受控查询")}；'
                    f'币种：{summary.get("currency", "CNY")}。')
        if previous_period:
            content += f'\n对比区间：{previous_period.get("startDate")} 至 {previous_period.get("endDate")}（结束日期不含当天）。'
        if m['orderCount']=='0':content+='\n\n本期无已发货销售订单数据。'
        content+='\n\n增长率为非零基数下的算术描述；少样本不判断明显变化。\n\n'+render_evidence(dossier)
        return {'content': content}

    async def clarification(self, state):
        self.stage('Clarification', '需要补充分析范围')
        question = state['question']
        text = {'profit': '当前系统没有可核算利润的成本口径，不能用销售额代替利润。是否改为分析已发货销售额和订单趋势？',
                'dimensions': '当前仅支持全厂总体的已发货销售额和订单趋势，尚不支持按客户、产品或物料筛选与排名。是否改为分析全厂总体？',
                'revenue': '当前工具没有收入确认口径，不能把含税已发货订单金额称为收入。是否改为分析已发货销售额和订单数？',
                'metric': '当前支持已发货订单的含税金额和订单数，是否按这个销售口径分析？',
                'entity': state.get('entity_prompt', '请明确客户或商品对象。'),
                'operation': state.get('entity_prompt', '请明确仓库、物料或工单。'),
                'evidence': state.get('entity_prompt','缺少必要证据，请明确要补充查询的范围。'),
                'dates': '请明确一个日期范围，例如“最近30天”“本月”或“2026-10-01至2026-10-19”（最多366天）。'}[question]
        return {'content': text, 'question': question}

    async def events(self, message, context, resume):
        from .telemetry import begin,finish
        scope=begin()
        try:
            async for event,data in self._events(message,context,resume):
                if event in ('result','clarification','error'):data={**data,'execution':finish()}
                yield event,data
        finally:scope.close()

    async def _events(self, message, context, resume):
        today=shanghai_day(self.clock())
        rewritten,initial,scope_note=prepare_turn(message,resume or {},today)
        state = {'message': rewritten, 'resume': initial, 'today': today,
                 'gateway_token': context.get('gatewayToken', ''), 'attempts': 0}
        active_context=clean_context(initial,today)
        status = 'error'
        resolved_period = {}
        dimension_pending = {}
        episodes = EpisodicMemory()
        try:
            async with asyncio.timeout(150):
                async for mode, value in self.graph.astream(state, stream_mode=['custom', 'updates'],
                                                          config={'recursion_limit': 18}):
                    if mode == 'custom':
                        if value[0]=='context':
                            previous=active_context;active_context=merge_context(active_context,value[1],today=today)
                            yield 'stage',{'node':'Context','message':describe_change(previous,active_context),'context':active_context}
                            continue
                        if value[0] == 'stage':
                            episodes.record(value[1]['node'], 'started')
                        yield value
                    elif mode == 'updates':
                        for node, update in value.items():
                            if node in ('Dimensions','Operations'):
                                dimension_pending = update.get('pending', {})
                                if update.get('content'):
                                    active_context=merge_context(active_context,update['context'],completed=True,today=today)
                                    result = {'content': update['content'], 'context': active_context}
                                    yield 'result', result
                                    status = 'completed'
                            if node=='Reflection' and update.get('pending'):
                                dimension_pending=update['pending']
                            if node == 'Planner' and update.get('period'):
                                resolved_period = update['period']
                            if node == 'Clarification':
                                status = 'clarification'
                                pending = {'question': update['question'],'originalMessage':WorkingMemory(message,initial).effective_message()[:4000]}
                                if update['question'] != 'dates':
                                    try:
                                        pending.update(resolve_period(message, initial, today=state['today']) or {})
                                    except ValueError:
                                        pass
                                if update['question'] in ('entity','operation','evidence'):
                                    pending.update(dimension_pending)
                                if (resume or {}).get('metric') == 'sales':
                                    pending['metric'] = 'sales'
                                if update['question'] == 'dates':
                                    selected=dimension_intent(message,initial)
                                    if selected and selected.get('dimension') in ('customer','product'):
                                        pending.update(dimension=selected['dimension'],analysisOperation=selected.get('operation','entity'),entityQuery=selected.get('query',''))
                                        if selected.get('entityId'):pending['entityId']=selected['entityId']
                                        if selected.get('limit'):pending['rankingLimit']=selected['limit']
                                    for key in ('dimension','entityQuery','entityId'):
                                        if key in initial and key not in pending: pending[key]=initial[key]
                                    active_context={k:v for k,v in active_context.items() if k not in ('startDate','endDate')}
                                active_context=merge_context(active_context,pending,today=today)
                                yield 'clarification', {'content': update['content'], 'context': active_context}
                            elif node == 'Reporter':
                                status = 'completed'
                                active_context=merge_context(active_context,{**resolved_period,'metric':'sales'},completed=True,today=today)
                                yield 'result', {'content': update['content'],'context':active_context}
        except AgentFailure as exc:
            yield 'error', {'code': exc.code, 'message': exc.message,'context':merge_context(active_context,{'question':'retry'},today=today)}
        except TimeoutError:
            yield 'error', {'code': 'ANALYSIS_TIMEOUT', 'message': '分析超过时间限制，请缩小范围后重试。','context':active_context}
        except asyncio.CancelledError:
            raise
        except Exception:
            yield 'error', {'code': 'ANALYSIS_FAILED', 'message': '分析失败，未生成未经核验的结论。'}
        yield 'done', {'status': status}


# Public production entry: every turn starts with model decision.
from .decision_loop import ModelDrivenAgent as SalesAgent
