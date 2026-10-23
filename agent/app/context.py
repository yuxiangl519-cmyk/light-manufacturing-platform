"""Bounded query conditions, never tool-result memory or an authorization grant."""
import json
import re
from .dates import resolve_period, period, DATE_TOKEN, MONTH_TOKEN
from datetime import date
from .privacy import redact_question

MAX_BYTES=4096
QUESTIONS={'dates','metric','profit','revenue','dimensions','entity','operation','evidence','retry'}
OPERATIONS={'get_current_stock':'当前库存','get_low_stock_alerts':'低库存预警','get_production_shortage':'工单用料需求','get_pending_production_receipts':'成品待入库'}
ID_KEYS=('entityId','lastCustomerId','lastProductId','warehouseId','materialId','productionOrderId')

def valid_id(value):
    return isinstance(value,str) and bool(re.fullmatch(r'[1-9]\d{0,18}',value)) and int(value)<=9223372036854775807

def query_text(value):
    return value if isinstance(value,str) and 0<len(value.strip())<=100 and redact_question(value)==value else None

def lookup(value):
    if not isinstance(value,dict):return None
    allowed={'warehouseId':('warehouse','receiptWarehouse'),'materialId':('material',),'productionOrderId':('production',)}
    if value.get('dimension') not in allowed.get(value.get('field'),()) or not query_text(value.get('query')):return None
    return {k:value[k] for k in ('dimension','query','field')}

def clean_context(raw,today=None):
    if not isinstance(raw,dict):return {'version':1}
    if raw.get('version') not in (None,1):return {'version':1}
    c={'version':1}
    retry=raw.get('retryRequest')
    if isinstance(retry,str) and 0<len(retry)<=400 and redact_question(retry)==retry:c['retryRequest']=retry
    for key in ID_KEYS:
        if valid_id(raw.get(key)):c[key]=raw[key]
    for key,values in {'domain':('sales','operations'),'metric':('sales',),'dimension':('customer','product'),
                       'dateBasis':('created_at','shipped_at'),'question':QUESTIONS,'analysisOperation':('total','entity','ranking','concentration')}.items():
        if raw.get(key) in values:c[key]=raw[key]
    if type(raw.get('rankingLimit'))==int and 1<=raw['rankingLimit']<=20:c['rankingLimit']=raw['rankingLimit']
    if query_text(raw.get('entityQuery')):c['entityQuery']=raw['entityQuery']
    try:
        if raw.get('startDate') and raw.get('endDate'):c.update(period(date.fromisoformat(raw['startDate']),date.fromisoformat(raw['endDate'])))
        elif today is not None and (raw.get('dateHint') or raw.get('originalMessage')):
            c.update(resolve_period(raw.get('dateHint') or raw['originalMessage'],{},today=today) or {})
    except (ValueError,TypeError,OverflowError):pass
    op=raw.get('operation',{})
    if isinstance(op,dict) and op.get('name') in OPERATIONS:
        name=op['name'];c['domain']='operations';c['operation']={'name':name,'request':OPERATIONS[name]}
        args=raw.get('operationsArgs',{});args=args if isinstance(args,dict) else {}
        c['operationsArgs']={k:v for k,v in args.items() if k in ('warehouseId','materialId','productionOrderId') and valid_id(v)}
        for key in ('warehouseId','materialId','productionOrderId'):
            if key in c:c['operationsArgs'].setdefault(key,c[key])
        c.update(c['operationsArgs'])
        pending=lookup(raw.get('operationsLookup'))
        if pending:c['operationsLookup']=pending
        remaining=raw.get('operationsRemaining',[])
        if isinstance(remaining,list):c['operationsRemaining']=[v for item in remaining[:2] if (v:=lookup(item))]
        if not raw.get('version') and pending and pending['field']=='warehouseId' and not c['operationsArgs'].get('materialId'):
            named=re.search(r'物料[“"「]([^”"」]{1,100})[”"」]',op.get('request',''))
            if named and query_text(named[1]):c['operationsRemaining']=[{'dimension':'material','field':'materialId','query':named[1]}]
    confirmed=raw.get('confirmedOptions',{})
    if isinstance(confirmed,dict):
        c['confirmedOptions']={k:v for k,v in confirmed.items() if k in ('entityId','warehouseId','materialId','productionOrderId') and valid_id(v) and c.get(k)==v}
    if isinstance(raw.get('missingTools'),list):c['missingTools']=[x for x in raw['missingTools'][:2] if x in ('get_sales_summary','get_sales_trend')]
    # Legacy conversations are narrowed to known semantic markers, never replayed verbatim.
    original=raw.get('originalMessage','')
    if isinstance(original,str) and c.get('question'):
        if c.get('question') not in ('profit','revenue','metric') and any(w in original for w in ('销售','订单')):c.setdefault('metric','sales')
        if not c.get('dimension'):
            dim='customer' if '客户' in original else 'product' if any(w in original for w in ('商品','产品','台式机')) else None
            if dim:c['dimension']=dim
        if not c.get('analysisOperation'):
            if any(w in original for w in ('排行','排名')):c['analysisOperation']='ranking'
            elif '集中度' in original:c['analysisOperation']='concentration'
        if not c.get('entityQuery'):
            name=re.search(r'[“"「]([^”"」]{1,100})[”"」]',original)
            if name and c.get('dimension') and query_text(name[1]):c['entityQuery']=name[1]
    if c.get('dimension') and 'analysisOperation' not in c:c['analysisOperation']='entity'
    if 'domain' not in c and (c.get('metric')=='sales' or c.get('dimension')):c['domain']='sales'
    if c.get('dimension') and c.get('entityId') and (raw.get('version')!=1 or c.get('confirmedOptions',{}).get('entityId')==c['entityId']):
        c['lastCustomerId' if c['dimension']=='customer' else 'lastProductId']=c['entityId']
    if c.get('question'):
        c['originalMessage']={'profit':'利润','revenue':'收入','metric':'经营分析','dimensions':'销售分析'}.get(c['question'],
            ('客户销售' if c.get('dimension')=='customer' else '商品销售' if c.get('dimension')=='product' else '销售分析' if c.get('metric')=='sales' else '经营分析'))
    if len(json.dumps(c,ensure_ascii=False).encode('utf-8'))>MAX_BYTES:raise ValueError('Structured context too large')
    return c

def merge_context(base,update,*,completed=False,today=None):
    base=clean_context(base,today);update=dict(update or {})
    remembered={k:v for k,v in base.items() if k in ('lastCustomerId','lastProductId')}
    new_domain=update.get('domain') or ('operations' if 'operation' in update else 'sales' if update.get('metric')=='sales' or update.get('dimension') else base.get('domain'))
    if new_domain and new_domain!=base.get('domain'):
        base=remembered
    if new_domain:base['domain']=new_domain
    if new_domain=='sales':
        for k in ('operation','operationsArgs','operationsLookup','operationsRemaining','warehouseId','materialId','productionOrderId'):base.pop(k,None)
        if update.get('analysisOperation')=='total' or (update.get('dimension') and update['dimension']!=base.get('dimension')):
            for k in ('dimension','entityId','entityQuery','analysisOperation','confirmedOptions'):base.pop(k,None)
        if update.get('entityId') and update['entityId']!=base.get('entityId'):
            base.pop('entityQuery',None);base.pop('confirmedOptions',None)
        if update.get('entityQuery') and update['entityQuery']!=base.get('entityQuery') and not update.get('entityId'):
            base.pop('entityId',None);base.pop('confirmedOptions',None)
    if new_domain=='operations':
        for k in ('startDate','endDate','metric','dimension','entityId','entityQuery','analysisOperation','rankingLimit'):base.pop(k,None)
        if isinstance(update.get('operationsArgs'),dict):
            for k in ('warehouseId','materialId','productionOrderId'):
                if k not in update['operationsArgs']:base.pop(k,None)
    base.update(update)
    if completed:
        for k in ('question','originalMessage','missingTools','operationsLookup','operationsRemaining','retryRequest'):base.pop(k,None)
        fields=('entityId','warehouseId','materialId','productionOrderId')
        if isinstance(base.get('operationsArgs'),dict):base.update(base['operationsArgs'])
        base['confirmedOptions']={k:base[k] for k in fields if valid_id(base.get(k))}
    return clean_context(base,today)

def prepare_turn(message,resume,today):
    c=clean_context(resume,today);text=message.strip();rewritten=text
    period_only=bool(re.match(r'^(?:那|那么|改成|换成)?\s*(?:上个?月|本月|上周|本周|今天|昨天|最近\d+天)',text)) and not any(w in text for w in ('客户','商品','销售','库存','工单','利润','收入','原料','成品'))
    if not any(w in text for w in ('客户','商品','销售','库存','工单','利润','收入','原料','成品')):
        try:period_only=bool(resolve_period(text,{},today=today)) or period_only
        except ValueError:pass
    refer_customer=bool(re.search(r'刚才(?:那个|的)?客户|刚才那个客户',text))
    refer_product=bool(re.search(r'刚才(?:那个|的)?(?:商品|产品)|刚才那个(?:商品|产品)',text))
    if refer_customer or refer_product:
        dimension='customer' if refer_customer else 'product';id=c.get('lastCustomerId' if refer_customer else 'lastProductId')
        if id:
            remainder=re.sub(r'刚才(?:那个|的)?(?:客户|商品|产品)', ' ',text)
            rewritten=('客户' if refer_customer else '商品')+'ID '+id+' '+remainder
            c=merge_context(c,{'domain':'sales','metric':'sales','dimension':dimension,'entityId':id,'analysisOperation':'entity'},today=today)
        else:c['referenceMissing']='customer' if refer_customer else 'product'
    elif (period_only or text in ('重试','再试一次','继续','继续分析')) and c.get('metric')=='sales':
        suffix=text if period_only else ''
        if c.get('analysisOperation') in ('ranking','concentration'):
            rewritten=suffix+('客户' if c.get('dimension')=='customer' else '商品')+('销售排行' if c['analysisOperation']=='ranking' else '集中度')
            if c['analysisOperation']=='ranking':rewritten+='前'+str(c.get('rankingLimit',10))+'名'
        elif c.get('dimension') and c.get('entityId'):rewritten=('客户' if c['dimension']=='customer' else '商品')+'ID '+c['entityId']+' '+suffix
        else:rewritten=suffix+' 销售分析'
    elif period_only and c.get('domain')=='operations':rewritten=text+'库存'
    elif text in ('重试','再试一次','继续','继续分析') and c.get('operation'):rewritten=OPERATIONS[c['operation']['name']]
    if re.match(r'^(?:换成|改成|那|那么)',text) and re.search(r'仓(?:库)?',text) and c.get('domain')!='operations':c['scopeAmbiguous']=True
    note='沿用：已确认且未被本轮修改的条件；修改：以本轮明确日期、指标或对象为准，所有数值重新查询。' if len(c)>1 else '修改：建立本轮查询条件；不沿用旧工具数值。'
    return rewritten,c,note

def describe_change(before,after):
    labels={'startDate':'开始日期','endDate':'结束日期（排除）','metric':'指标','dimension':'分析维度','entityId':'对象ID','entityQuery':'待匹配对象','warehouseId':'仓库ID','materialId':'物料ID','productionOrderId':'工单ID'}
    kept=[];changed=[]
    for key,label in labels.items():
        old,new=before.get(key),after.get(key)
        if old is not None and old==new:kept.append(f'{label}={new}')
        elif old!=new:changed.append(f'{label}：{old if old is not None else "未指定"}→{new if new is not None else "不再沿用"}')
    return '沿用：'+('；'.join(kept) or '无已确认条件')+'。修改：'+('；'.join(changed) or '无范围变更')+'。本轮重新鉴权并查询，不沿用旧工具结果。'
