"""Read-only operational routes. No model chooses identities or computes shortages."""
import re
from datetime import datetime
from decimal import Decimal, InvalidOperation
from .model import AgentFailure
from .dates import SHANGHAI
from .evidence import EvidenceEngine, render_evidence
from langgraph.config import get_stream_writer

WAREHOUSE_PATTERN=r'仓库\s*[“"「]([^”"」]{1,100})[”"」]|([\w·\u4e00-\u9fff]{1,60}?仓(?:库(?!存))?)(?!\s*(?:ID|id))'

def intent(message, resume):
    if any(w in message for w in ('执行入库','创建采购','自动采购','执行领料','帮我领料','自动补料')):
        return {'blocked':'只提供只读查询和补料参考，不创建采购单、不领料、不执行入库。'}
    related=any(w in message for w in ('库存','缺料','缺货','零部件','工单','待入库','等待入库','待执行成品入库','还没入库','尚未入库'))
    if related and re.search(r'昨天|前天|明天|后天|[上下本]周|上星期|上个?月|本月|下月|去年|今年|年初|月初|期初|期末|历史|最近|近\s*\d+\s*天|过去|\d{4}年|\d{4}[-/]\d|\d+月\d*|截至|截止',message):
        return {'blocked':'只支持查询时刻的当前库存、工单需求与待入库单；尚不支持历史库存还原。请明确是否查看当前数据。'}
    if any(w in message for w in ('待入库','等待入库','待执行成品入库','还没入库','尚未入库')):
        operation='get_pending_production_receipts'
    elif '工单' in message and any(w in message for w in ('缺','用料','需求','零部件','数量')):
        operation='get_production_shortage'
    elif (resume.get('domain')=='operations' and resume.get('operation',{}).get('name') in ('get_current_stock','get_low_stock_alerts')
          and re.match(r'^(?:那|那么|再看|换成)',message.strip()) and re.search(WAREHOUSE_PATTERN,message)
          and not any(w in message for w in ('销售','订单','客户','利润'))):
        operation=('get_low_stock_alerts' if any(w in message for w in ('不足','预警','低库存','低于','缺货')) else 'get_current_stock') if any(w in message for w in ('库存','缺货')) else resume['operation']['name']
        return {'name':operation,'request':message,
                'inheritedArguments':{k:v for k,v in resume.get('operationsArgs',{}).items() if k=='materialId'},
                'pendingLookups':[item for item in [resume.get('operationsLookup'),*resume.get('operationsRemaining',[])] if item and item['field']!='warehouseId']}
    elif any(w in message for w in ('库存','缺货')):
        operation='get_low_stock_alerts' if any(w in message for w in ('不足','预警','低库存','低于','缺货')) else 'get_current_stock'
    elif resume.get('question')=='operation' and resume.get('operation') and not any(w in message.lower() for w in ('销售','订单','客户','利润','卖得','卖了','排行','sales')):
        return resume['operation']
    else: return None
    return {'name':operation,'request':message}


def cell(value):
    return str(value).replace('|','／').replace('\n',' ').replace('\r',' ')


def quantity(value):
    if not isinstance(value,str): raise ValueError('Quantity must be a string')
    n=Decimal(value)
    if not n.is_finite() or n<0 or n!=n.to_integral_value(): raise ValueError('Invalid quantity')
    return value


class OperationsAnalysis:
    def __init__(self, tools, max_calls=4): self.tools=tools; self.max_calls=max_calls; self.calls=0

    async def call(self,name,args,state):
        self.calls+=1
        if self.calls>self.max_calls:raise AgentFailure('AI_TOOL_LIMIT','只读工具调用达到本轮上限，请直接提供后端ID。')
        try:
            return await self.tools.execute(name,args,{},state['gateway_token'],scope={'operation':name,'arguments':args})
        except Exception as exc:
            status=getattr(getattr(exc,'response',None),'status_code',None)
            if status==403: raise AgentFailure('FORBIDDEN','没有此项库存、生产或单据查询权限，或分析授权已过期。') from exc
            if status==409: raise AgentFailure('INVALID_STATE','该工单尚未确认或已取消，不能提供已确认用料需求。') from exc
            if status==404: raise AgentFailure('NOT_FOUND','未找到该对象，请核对后端ID或单号。') from exc
            if status==422: raise AgentFailure('RESULT_LIMIT','结果明细超过上限，请按仓库缩小范围。') from exc
            raise AgentFailure('ERP_TOOL_FAILED','ERP只读查询失败；未生成库存或缺料结论。') from exc

    def clarify(self,text,op,pending=None):
        return {'route':'clarify','question':'operation','entity_prompt':text,
                'pending':{'operation':op,**(pending or {})}}

    async def run(self,state):
        op=state['operation_intent'];resume=state.get('resume',{})
        if op.get('blocked'):
            return self.clarify(op['blocked'],{})
        name=op['name'];text=state['message'];request=op.get('request',text)
        same_operation=resume.get('operation')==op
        args=dict(resume.get('operationsArgs',{})) if same_operation else dict(op.get('inheritedArguments',{}))
        # Only explicit user IDs, or revalidated backend candidates, can become identities.
        for label,key in [('仓库','warehouseId'),('物料','materialId'),('工单','productionOrderId')]:
            found=re.search(label+r'\s*(?:ID|id)\s*[:：#]?\s*([1-9]\d{0,18})(?!\d)',text)
            if not found and text!=request:found=re.search(label+r'\s*(?:ID|id)\s*[:：#]?\s*([1-9]\d{0,18})(?!\d)',request)
            if found:args[key]=found[1]
        lookup=resume.get('operationsLookup') if same_operation else None
        # An explicitly labelled ID corrects an old failed lookup. A bare candidate
        # selection still has to be checked against freshly queried candidates.
        if lookup and re.search({'warehouseId':'仓库','materialId':'物料','productionOrderId':'工单'}[lookup['field']]+r'\s*(?:ID|id)',text):
            lookup=None
        scope_text=text if text!=request and not re.search(r'选择|选|ID|id',text) else request
        wh=re.search(WAREHOUSE_PATTERN,scope_text)
        warehouse_query=next((g for g in wh.groups() if g),None) if wh else None
        if lookup and text!=request and not re.search(r'选择|选|ID|id',text) and (wh or re.search(r'物料[“"「]',text)):
            lookup=None
        lookups=[]
        if lookup and lookup['field'] not in args:lookups.append(lookup)
        if same_operation:lookups.extend(item for item in resume.get('operationsRemaining',[]) if item not in lookups and item['field'] not in args)
        if not lookup:
            if wh and 'warehouseId' not in args:
                query=re.sub(r'^(?:(?:目前|现在|当前|查询|请问|请查询|那么|那|再看|换成)\s*)+','',warehouse_query)
                lookups.append({'dimension':'receiptWarehouse' if name=='get_pending_production_receipts' else 'warehouse','query':query,'field':'warehouseId'})
            order=re.search(r'工单\s*(?:单号\s*)?[“"「]?([A-Za-z][A-Za-z0-9-]{2,63})',scope_text)
            if name=='get_production_shortage' and order and not order[1].upper().startswith('ID') and 'productionOrderId' not in args:
                lookups.append({'dimension':'production','query':order[1],'field':'productionOrderId'})
        material=re.search(r'物料[“"「]([^”"」]{1,100})[”"」]',scope_text)
        if material and 'materialId' not in args and not any(item['field']=='materialId' for item in lookups):
            lookups.append({'dimension':'material','query':material[1],'field':'materialId'})
        for item in op.get('pendingLookups',[]):
            if item['field'] not in args and not any(x['field']==item['field'] for x in lookups):lookups.append(item)
        for index,lookup in enumerate(lookups):
            get_stream_writer()(('context',{'domain':'operations','operation':op,'operationsArgs':args,'operationsLookup':lookup,'operationsRemaining':lookups[index+1:]}))
            data=await self.call('resolve_operations_entity',{k:lookup[k] for k in ('dimension','query')},state)
            selecting=text!=request and resume.get('operationsLookup',{}).get('field')==lookup['field']
            candidates=data['candidates'];selected=re.search(r'(?:选择|选|ID|id)\s*([1-9]\d{0,18})(?!\d)',text) if selecting else None
            match=[c for c in candidates if selected and c['id']==selected[1]]
            if data.get('truncated') or not candidates or (len(candidates)>1 and len(match)!=1) or (selected and not match):
                choices='；'.join(f"ID {cell(c['id'])} / {cell(c['code'])} / {cell(c['name'])}" for c in candidates)
                prompt=('候选过多，请提供更完整名称或编码。' if data.get('truncated') else '请明确对象并选择候选ID：' if candidates else '未找到对象，请提供正确名称、编码或后端ID。')+choices
                return self.clarify(prompt,op,{'operationsLookup':lookup,'operationsArgs':args,'operationsRemaining':lookups[index+1:]})
            args[lookup['field']]=(match[0] if match else candidates[0])['id']
        if name=='get_production_shortage' and 'productionOrderId' not in args:
            return self.clarify('请提供要分析的工单ID或完整工单号。',op,{'operationsArgs':args})
        if name in ('get_current_stock','get_low_stock_alerts') and not args.get('warehouseId'):
            return self.clarify('请提供仓库名称或仓库ID，以确定当前库存查询范围。',op,{'operationsArgs':args})
        if name in ('get_current_stock','get_low_stock_alerts') and not args.get('materialId'):
            # Never silently widen an unparsed material filter to all warehouse materials.
            remaining=re.sub(r'仓库\s*(?:ID|id)\s*\d+','',request)
            remaining=re.sub(WAREHOUSE_PATTERN,'',remaining)
            before=remaining.split('库存')[0]
            before=re.sub(r'^(?:请查询|查询|当前|目前|现在|的)+','',before).strip(' ，,？?：:')
            if before and not re.fullmatch(r'(?:目前|当前|现在|的|请问|哪些|所有|全部|有|物料|低|查看|查询|请查询|还剩|多少|缺货|那么|那|再看|换成|呢)*',before):
                return self.clarify('请提供物料ID，或用“物料「名称」”指定对象，以免返回错误的全仓范围。',op,{'operationsArgs':args})
        if name=='get_production_shortage':args={k:v for k,v in args.items() if k=='productionOrderId'}
        if name=='get_pending_production_receipts':args={k:v for k,v in args.items() if k=='warehouseId'}
        get_stream_writer()(('context',{'domain':'operations','operation':op,'operationsArgs':args}))
        data=await self.call(name,args,state)
        try:content=self.render(name,data)
        except (KeyError,ValueError,TypeError,InvalidOperation) as exc:
            raise AgentFailure('INVALID_ERP_DATA','ERP数据未通过库存数值校验，未生成报告。') from exc
        return {'route':'completed','content':content,'context':{'domain':'operations','operation':op,'operationsArgs':args}}

    def render(self,name,data):
        as_of=datetime.fromisoformat(str(data['asOf']).replace('Z','+00:00'))
        if as_of.tzinfo is None or data['timezone']!='Asia/Shanghai':raise ValueError('Invalid timestamp')
        lines=[f'查询时刻：{as_of.astimezone(SHANGHAI).isoformat()}；时区：Asia/Shanghai。',f"数据来源：{cell(data['source'])}。"]
        if name in ('get_current_stock','get_low_stock_alerts'):
            lines+=['当前库存 / 最低库存阈值（各行独立单位，严格低于阈值才预警）', '| 仓库 | 物料ID / 编码 / 名称 | 当前库存 | 最低库存阈值 | 单位 |','|---|---|---:|---:|---|']
            for r in data['rows']:
                if not isinstance(r['lowStock'],bool) or r['lowStock']!=(Decimal(quantity(r['quantity']))<Decimal(quantity(r['minStock']))):raise ValueError('Inconsistent alert')
                lines.append(f"| {cell(r['warehouseName'])}（{cell(r['warehouseId'])}） | {cell(r['materialId'])} / {cell(r['materialCode'])} / {cell(r['materialName'])} | {r['quantity']} | {r['minStock']} | {cell(r['unit'])} |")
            if not data['rows']:lines+=['当前范围无低库存预警。' if name=='get_low_stock_alerts' else '当前范围无库存记录。']
            lines += [f"范围：{cell(data.get('scope','指定仓库与物料'))}。"]
        elif name=='get_production_shortage':
            lines += [f"工单：{cell(data['orderNo'])}；状态：{cell(data['status'])}；确认时BOM版本：{cell(data['bomRevision'])}。"]
            if data.get('needsIssue',True):
                lines+=['| 物料ID / 编码 / 名称 | 工单需求 | 现有数量 | 缺料 / 补料参考 | 单位 |','|---|---:|---:|---:|---|']
                for r in data['items']:
                    required,available,shortage=(quantity(r[k]) for k in ('requiredQuantity','availableQuantity','shortageQuantity'))
                    if Decimal(shortage)!=max(Decimal(required)-Decimal(available),Decimal(0)):raise ValueError('Invalid shortage')
                    lines.append(f"| {cell(r['materialId'])} / {cell(r['materialCode'])} / {cell(r['materialName'])} | {required} | {available} | {shortage} | {cell(r['unit'])} |")
                lines += ['需求来自工单确认时快照，当前BOM变更不改写该需求；缺料=max(需求−当前库存,0)。仅为补料参考数量，不是已优化的采购计划。']
                if data.get('hasShortage') is False:lines+=['该工单当前无缺料。']
            else:
                lines += [cell(data['notice']), '| 快照物料ID / 名称 | 原始工单需求 | 现有数量 | 缺料参考 | 单位 |','|---|---:|---:|---|---|']
                for r in data['currentStock']:lines.append(f"| {cell(r['materialId'])} / {cell(r['materialName'])} | {quantity(r['requiredQuantity'])} | {quantity(r['availableQuantity'])} | 不适用（已领料） | {cell(r['unit'])} |")
        else:
            lines+=['待执行成品入库单（PENDING / PRODUCTION_RECEIPT）','| 单据ID / 单号 | 工单号 | 仓库 | 成品ID / 名称 | 待入库数量 | 单位 |','|---|---|---|---|---:|---|']
            for r in data['rows']:
                for i in r['items']:lines.append(f"| {cell(r['id'])} / {cell(r['documentNo'])} | {cell(r['sourceNo'])} | {cell(r['warehouseName'])} | {cell(i['materialId'])} / {cell(i['materialName'])} | {quantity(i['quantity'])} | {cell(i['unit'])} |")
            if not data['rows']:lines+=['当前范围没有待执行成品入库单。']
        if 'total' in data:lines += [f"匹配总数：{cell(data['total'])}；{'仅展示前20项，请缩小范围。' if data.get('truncated') else '已展示本次范围全部匹配项。'}"]
        lines+=['当前库存不含待入库数量；工单需求未预留库存，多工单可能竞争同一余额。不同物料、仓库和单位不直接合计。当前结果随业务执行变化，不支持历史库存还原。']
        if name in ('get_current_stock','get_low_stock_alerts','get_production_shortage'):
            lines+=['',render_evidence(EvidenceEngine().operations(name,data))]
        return '\n\n'.join(lines[:2])+'\n\n'+'\n'.join(lines[2:])
