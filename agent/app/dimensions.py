"""Dimension intent, identity resolution and reports never delegate IDs to a model."""
import re
from datetime import date, timedelta
from decimal import Decimal

from .dates import DATE_TOKEN, MONTH_TOKEN
from .analytics import analyze, number
from .model import AgentFailure
from .evidence import EvidenceEngine, MissingEvidence, render_evidence
from langgraph.config import get_stream_writer


def safe(value):
    return str(value).replace('|','｜').replace('\n',' ').replace('\r',' ')[:160]


def intent(message, resume):
    quote=re.search(r'[“"「](.+?)[”"」]',message)
    outside=re.sub(r'[“"「].+?[”"」]', '', message)
    customer = '客户' in outside
    if customer and any(w in outside for w in ('集中度','客户依赖','集中风险')):
        remainder=MONTH_TOKEN.sub(' ',DATE_TOKEN.sub(' ',outside))
        remainder=re.sub(r'(?:最近|近|过去)\s*\d+\s*天|最近一个月|上个月|上月|本月|今天|昨天|本周|上周',' ',remainder)
        # Check the whole request: named/ID restrictions can follow the metric.
        remainder=re.sub(r'客户依赖度?|集中风险|集中度|高不高|怎么样|如何|是否|过高|偏高|高吗|有哪些|多少|给出|建议|解释|原因|风险|请|帮我|分析|查询|查看|评估|了解|一下|全厂|总体|客户|销售额|销售|经营|情况|的|至|到|[\s，,。？?：:（）()]','',remainder)
        scoped=bool(quote or remainder)
        return {'dimension':'customer','operation':'concentration','unsupportedScope':scoped}
    if any(w in outside for w in ('全厂','总体')):
        return None
    product = any(w in outside for w in ('商品','产品','台式机','办公电脑','型号','物料'))
    if customer and product:
        return {'dimension':'unsupported'}
    dimension = 'customer' if customer else 'product' if product else resume.get('dimension')
    if dimension not in ('customer','product'):
        return None
    ranking = any(w in outside.lower() for w in ('排行','排名','top ')) or bool(re.search(r'前\s*\d+|最高.*(?:客户|商品|产品).*(?:哪些|哪几)|(?:客户|商品|产品).*销售额最高',outside))
    if ranking:
        limit=re.search(r'(?:前|top\s*)(\d+)',message,re.I)
        return {'dimension':dimension,'operation':'ranking','limit':int(limit[1]) if limit else 10, 'unsupportedMetric':any(w in outside for w in ('销量','数量','订单数'))}
    selected = None if quote else re.match(r'\s*(?:选择\s*)?(?:客户|商品|产品)?\s*ID\s*[:：]?\s*([1-9]\d{0,18})(?![\w])',message,re.I)
    if selected:
        return {'dimension':dimension,'operation':'entity','entityId':selected[1],
                'query':resume.get('entityQuery','') if resume.get('question')=='entity' else '','explicitSelection':True}
    if quote:
        query=quote[1].strip()
    else:
        text=DATE_TOKEN.sub(' ',message)
        text=MONTH_TOKEN.sub(' ',text)
        text=re.sub(r'(?:最近|近|过去)\s*\d+\s*天|最近一个月|上个月|上月|本月|今天|昨天|本周|上周',' ',text)
        text=re.sub(r'至|到|[~～]',' ',text)
        text=re.sub(r'^(?:请|帮我|分析|指定|某个|某|客户|商品|产品|物料|的|\s)+','',text)
        query=re.split(r'销售|销量|卖了|采购|情况|如何|多少|怎么样',text)[0].strip(' 的？?，,。 ')
    if query in ('','客户','商品','产品','某个','某'):
        query=resume.get('entityQuery','') if not (customer or product) else ''
    result={'dimension':dimension,'operation':'entity','query':query}
    if query==resume.get('entityQuery') and resume.get('entityId'):
        result['entityId']=resume['entityId']
    return result


def units(value):
    if not isinstance(value,dict) or len(value)>10:
        raise ValueError('Invalid unit groups')
    for unit,qty in value.items():
        if not isinstance(unit,str) or not 0<len(unit)<=10 or number(qty)!=number(qty).to_integral_value():
            raise ValueError('Invalid unit quantity')
    return value


def unit_text(value):
    return '；'.join(f'{q} {safe(u)}' for u,q in units(value).items()) or '无已发货商品数量'


class DimensionAnalysis:
    def __init__(self,registry,settings,reporter,stage):
        self.registry,self.settings,self.reporter,self.stage=registry,settings,reporter,stage

    async def run(self,state):
        selected=state['dimension_intent'];dimension=selected['dimension'];period=state['period']
        pending={**period,'dimension':dimension,'metric':'sales','entityQuery':selected.get('query','')}
        if dimension=='unsupported':
            return {'question':'entity','entity_prompt':'请先选择客户或商品中的一个维度。','pending':{},'route':'clarify'}
        calls=0
        async def call(name,args,scope):
            nonlocal calls
            calls+=1
            if calls>self.settings.max_tool_calls:
                raise AgentFailure('UNSAFE_TOOL_PLAN','工具调用次数达到限制，未生成结论。')
            try:
                return await self.registry.execute(name,{**period,**args},period,state['gateway_token'],scope=scope)
            except Exception as exc:
                raise AgentFailure('ERP_TOOL_FAILED','维度查询失败或授权已过期，未生成未经核验的结论。') from exc
        def clarify(prompt,candidates=None):
            return {'question':'entity','entity_prompt':prompt,'pending':{**pending,'candidates':candidates or []},'route':'clarify'}
        self.stage('DimensionResolver','确定客户/商品范围，身份只取后端ID')
        try:
            if selected['operation']=='concentration':
                if selected.get('unsupportedScope'):return clarify('客户集中度比较的是同期间全厂客户结构，请明确查询全厂客户集中度，不按某一客户替代总体。')
                summary=await call('get_sales_summary',{},None)
                ranking=None
                for attempt in range(self.settings.max_reflections):
                    if calls>=self.settings.max_tool_calls:break
                    try:
                        ranking=await call('get_customer_sales_ranking',{'limit':4},{'dimension':'customer'})
                        break
                    except AgentFailure:
                        if attempt+1>=self.settings.max_reflections:break
                if ranking is None:
                    return clarify('已达到工具调用或补查上限；get_customer_sales_ranking未完成，客户集中度及客户对象建议仍未回答。已取得同期间销售汇总，但不能用汇总代替客户排行。是否在原范围重试？')
                try:dossier=EvidenceEngine().concentration(summary,ranking,period)
                except MissingEvidence:return clarify('客户集中度缺少完整排名、来源或查询时刻；尚未回答占比判断，请缩小范围或重试同期间查询。')
                content=(f'客户集中度：{period["startDate"]} 至 {period["endDate"]}（结束日期不含当天，Asia/Shanghai）\n\n'
                         f'口径：{summary["definition"]}。\n\n'+render_evidence(dossier))
                return {'content':content,'context':{**period,'metric':'sales'},'route':'completed'}
            if selected['operation']=='ranking':
                limit=selected['limit']
                if selected.get('unsupportedMetric'):
                    return clarify('当前排行仅按含税销售额排序，不支持订单数或销量排行。请明确改问销售额排行。')
                if not 1<=limit<=20:
                    return clarify('请指定1至20名的排行数量。')
                value=await call('get_'+dimension+'_sales_ranking',{'limit':limit},{'dimension':dimension})
                try:EvidenceEngine().metadata(value,period,'get_'+dimension+'_sales_ranking',dimension)
                except MissingEvidence:return clarify('排行缺少查询时刻、指标定义或来源，未据此生成异常结论，请在原范围重试。')
                rows=value['rows']
                if value.get('period')!=period or value.get('dimension')!=dimension or len(rows)>limit:
                    raise ValueError('Invalid ranking contract')
                seen=set();last=None
                content=(f'{"客户" if dimension=="customer" else "商品"}销售额排行：{period["startDate"]} 至 {period["endDate"]}（结束日期不含当天，Asia/Shanghai）\n\n'
                         '| ID | 历史销售快照名称 | 含税销售额（元） | 已发货订单数 | 数量（按单位） |\n|---|---|---:|---:|---|\n')
                for row in rows:
                    if not re.fullmatch(r'[1-9]\d{0,18}',row['id']) or row['id'] in seen:
                        raise ValueError('Invalid entity ID')
                    seen.add(row['id']);amount=number(row['salesAmount']);count=number(row['orderCount'])
                    if count!=count.to_integral_value() or (last is not None and amount>last):raise ValueError('Invalid ranking order/count')
                    last=amount
                    quantity=unit_text(row['quantitiesByUnit']) if dimension=='product' else '不跨商品合计数量'
                    content+=f'| {row["id"]} | {safe(row["name"])} | {amount:.2f} | {count} | {quantity} |\n'
                if not rows:content+='\n本期无已发货销售数据。\n'
                definition='销售单头total_amount' if dimension=='customer' else '销售明细line_amount（明细金额）'
                content+=(f'\n口径：{safe(value["definition"])}。金额定义：人民币含税{definition}；不是收入或利润。'
                          f'\n数据来源：{safe(value["source"])}；币种：CNY。'
                          '\n最多显示指定前N名，不将排行当成全厂总额。商品订单数跨商品不可相加；不同单位数量分别列示。'
                          '\n名称取区间内最近销售快照，身份按后端ID区分；停用资料可查历史。')
                content+=f'\n查询时刻：{safe(value["asOf"])}；工具：get_{dimension}_sales_ranking；计算：按{definition}求和降序，订单按ID去重；比较值：同范围其他对象的销售额，非上期。'
                content+='\n\n已查询确认的事实：上表为同期间、同口径排行；缺少全厂分母时不计算客户集中度。'
                content+='\n仍需核实的可能解释：当前排行不能确认客户流失或商品滞销原因。'
                if rows:content+=f'\n建议采取的行动：围绕{"客户" if dimension=="customer" else "商品"}ID {rows[0]["id"]} 的含税销售额{rows[0]["salesAmount"]}元核对本期已发货订单；未取得成本、需求和历史库存证据，不制定利润或采购目标。'
                return {'content':content,'context':{**period,'metric':'sales'},'route':'completed'}
            query=selected.get('query','');entity_id=selected.get('entityId')
            if query:
                value=await call('resolve_sales_entity',{'dimension':dimension,'query':query},{'dimension':dimension,'query':query})
                candidates=value['candidates']
                if value.get('dimension')!=dimension or len(candidates)>10:
                    raise ValueError('Invalid candidates')
                ids=[c['id'] for c in candidates]
                if any(not isinstance(id,str) or not re.fullmatch(r'[1-9]\d{0,18}',id) for id in ids) or len(ids)!=len(set(ids)):
                    raise ValueError('Invalid candidate IDs')
                if entity_id and entity_id not in ids:
                    entity_id=None
                    # Do not substitute a unique candidate when the user supplied an invalid ID.
                    if selected.get('explicitSelection'):
                        return clarify('该ID不在本次后端候选中，请重新选择或提供更准确名称。',candidates)
                if not entity_id and len(candidates)==1 and not value.get('truncated'):
                    entity_id=ids[0]
                if not entity_id:
                    prompt='请提供客户/商品的准确名称或编码。' if not candidates else '名称匹配多个对象，请明确回复“选择'+('客户' if dimension=='customer' else '商品')+'ID 数字”：\n'
                    for candidate in candidates:
                        prompt+=f'\n- ID {candidate["id"]} · {safe(candidate["code"])} · {safe(candidate["name"])} · {safe(candidate.get("unit",""))}'+(' · 停用，仍可查历史' if not candidate.get('enabled',True) else '')
                    if value.get('truncated'):prompt+='\n匹配超过10个，请缩小名称或使用编码。'
                    return clarify(prompt,candidates)
            elif not entity_id:
                return clarify('请提供要分析的客户或商品的名称、编码，或明确的后端ID。')
            self.stage('DimensionExecutor','按已确定的后端ID读取汇总与逐日趋势')
            get_stream_writer()(('context',{**period,'domain':'sales','metric':'sales','dimension':dimension,'entityId':entity_id,'analysisOperation':'entity'}))
            scope={'dimension':dimension,'entityId':entity_id}
            value=await call('get_entity_sales',scope,scope)
            summary,trend=value['summary'],value['trend']
            if value['entity']['id']!=entity_id or summary.get('period')!=period or trend.get('period')!=period:
                raise ValueError('Entity/date mismatch')
            start,end=map(date.fromisoformat,(period['startDate'],period['endDate']))
            points=trend['points']
            if [p['date'] for p in points]!=[str(start+timedelta(days=i)) for i in range((end-start).days)]:
                raise ValueError('Missing/altered trend days')
            if sum(number(p['orderCount']) for p in points)!=number(summary['current']['orderCount']):
                raise ValueError('Order count mismatch')
            if dimension=='product':
                actual={}
                for point in points:
                    for u,q in units(point['quantitiesByUnit']).items():actual[u]=actual.get(u,Decimal(0))+number(q)
                if actual!={u:number(q) for u,q in units(summary['current']['quantitiesByUnit']).items()}:raise ValueError('Quantity mismatch')
            metrics=analyze(summary,trend)
            report=await self.reporter({**state,'results':{'sales_summary':summary,'sales_trend':trend},'analysis':metrics,
                'dimension_scope':('客户' if dimension=='customer' else '商品')+'ID '+entity_id,
                'evidence_tools':('get_entity_sales[summary]','get_entity_sales[trend]')})
            content=f'分析范围：{"客户" if dimension=="customer" else "商品"} ID {entity_id} · {safe(value["entity"]["name"])}\n名称来源：{('本期无成交快照，显示当前资料名称' if value['entity'].get('nameSource')=='masterNoCurrentSales' else '本期最近销售快照')}\n\n'+report['content']
            if dimension=='product':
                content=content.replace('按创建时间筛选当前已发货销售单，人民币含税订单金额','按销售单创建时间筛选当前已发货的指定商品明细金额（line_amount），人民币含税')
                content+='\n本期数量：'+unit_text(summary['current']['quantitiesByUnit'])+'；上期数量：'+unit_text(summary['previous']['quantitiesByUnit'])+'。不同单位不相加。'
            content+='\n订单数按销售单ID去重；历史名称与单位沿用销售快照。'
            return {'content':content,'context':{**pending,'entityId':entity_id},'route':'completed'}
        except (ValueError,KeyError,TypeError,ArithmeticError) as exc:
            raise AgentFailure('INVALID_ERP_DATA','维度数据未通过日期、身份或金额/订单/单位核对，未生成报告。') from exc
