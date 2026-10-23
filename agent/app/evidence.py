"""Bound evidence, deterministic findings and object-specific actions; no causal inference."""
from datetime import date, datetime, timedelta
from decimal import Decimal
import re
from .analytics import analyze, number, money, growth
from .analysis_rules import DEFAULT_RULES

class MissingEvidence(ValueError):
    pass

def safe(value):
    return str(value).replace('|','／').replace('\n',' ').replace('\r',' ')[:240]

class EvidenceEngine:
    def __init__(self,rules=DEFAULT_RULES):self.rules=rules

    def dossier(self):return {'facts':[],'hypotheses':[],'actions':[],'unanswered':[],'rules':self.rules.describe()}

    def metadata(self,data,period,tool,dimension=None):
        for key in ('source','asOf','definition','currency','period'):
            if not data.get(key):raise MissingEvidence(f'{tool} 缺少{key}')
        if any(not isinstance(data[k],str) for k in ('source','asOf','definition','currency')):raise ValueError('Invalid evidence metadata')
        if data['period']!=period or data['currency']!='CNY' or data.get('dimension')!=dimension:
            raise ValueError('Unrelated period/currency/dimension')
        as_of=datetime.fromisoformat(str(data['asOf']).replace('Z','+00:00'))
        if as_of.tzinfo is None:raise ValueError('Missing timestamp timezone')
        return as_of

    def totals(self,summary):
        for row in (summary['current'],summary['previous']):
            amount=number(row['salesAmount']);count=number(row['orderCount'])
            if amount!=Decimal(money(amount)) or count!=count.to_integral_value() or (not count and amount):raise ValueError('Invalid summary quantities')

    def paired(self,first,second,period,tools,dimensions=(None,None)):
        times=[self.metadata(data,period,tool,dim) for data,tool,dim in zip((first,second),tools,dimensions)]
        ids=[d.get('snapshotId') for d in (first,second)]
        if any(ids) and (not all(ids) or ids[0]!=ids[1]):raise MissingEvidence('Sales results do not share one atomic snapshot')
        if first.get('dateBasis','created_at')!=second.get('dateBasis','created_at'):raise MissingEvidence('Sales date basis mismatch')
        if abs((times[0]-times[1]).total_seconds())>self.rules.maximum_read_skew_seconds:
            raise ValueError('Incompatible read timestamps')

    def fact(self,id,statement,current,comparison,formula,data,period,tools,**extra):
        return {'id':id,'statement':statement,'definition':data[0]['definition'],'period':dict(period),
                'snapshotId':data[0].get('snapshotId'),'dateBasis':data[0].get('dateBasis','created_at'),'snapshotScope':data[0].get('snapshotScope'),'timezone':'Asia/Shanghai','asOf':dict(zip(tools,[d['asOf'] for d in data])),
                'sources':dict(zip(tools,[d['source'] for d in data])), 'current':current,'comparison':comparison,
                'formula':formula,'tools':list(tools),'flagged':False,**extra}

    def sales(self,summary,trend,period,*,dimension=None,scope='全厂',tools=('get_sales_summary','get_sales_trend')):
        self.paired(summary,trend,period,tools,(dimension,dimension))
        start,end=map(date.fromisoformat,(period['startDate'],period['endDate']))
        if not 0<(end-start).days<=366:raise ValueError('Invalid period')
        if [p['date'] for p in trend['points']]!=[str(start+timedelta(days=i)) for i in range((end-start).days)]:raise ValueError('Incomplete trend calendar')
        self.totals(summary)
        metrics=analyze(summary,trend,self.rules)
        if sum(number(p['orderCount']) for p in trend['points'])!=number(summary['current']['orderCount']):raise ValueError('Inconsistent order totals')
        for row in (summary['current'],summary['previous'],*trend['points']):
            count=number(row['orderCount'])
            if count!=count.to_integral_value() or (count==0 and number(row['salesAmount'])!=0):raise ValueError('Impossible order count/amount')
        previous_period={'startDate':str(start-(end-start)),'endDate':str(start)}
        if summary.get('previousPeriod',previous_period)!=previous_period:raise ValueError('Comparison period mismatch')
        dossier=self.dossier()
        if not summary.get('snapshotId'):dossier['unanswered'].append('缺少原子快照标识，查询时刻接近不代表同一快照；这些旧格式数据不能证明跨工具读取一致性。')
        enough=all(number(summary[k]['orderCount'])>=self.rules.minimum_orders for k in ('current','previous'))
        for key,label,minimum in [('salesAmount','含税已发货销售额',self.rules.amount_delta),('orderCount','已发货订单数',Decimal(self.rules.order_delta))]:
            a,b=(number(summary[k][key]) for k in ('current','previous'))
            rate=growth(a,b)
            assessment='上期为零，不计算增长率' if not b else '样本不足，不评估明显变化' if not enough else '未触发明显变化阈值'
            flagged=bool(b and enough and abs((a-b)/b*100)>=self.rules.change_percent and abs(a-b)>=minimum)
            if flagged:assessment='触发明显变化筛查（非因果或统计显著性结论）'
            current,previous=(money(a),money(b)) if key=='salesAmount' else (str(a),str(b))
            fact=self.fact(key,f'{scope} {label}：{current}，等长上期{previous}',current,previous,
                f'({current}−{previous})/{previous}×100%，上期为0时不计算；{previous_period["startDate"]}至{previous_period["endDate"]}为相邻等长上期',
                (summary,trend),period,tools,rate=rate,flagged=flagged,assessment=assessment,scope=scope,comparisonPeriod=previous_period)
            if key=='orderCount':fact['definition']=('按实际发货时间' if summary.get('dateBasis')=='shipped_at' else '按销售单创建时间')+'筛选当前SHIPPED、未删除且属于该范围的销售单，按销售单ID去重计数；跨商品订单数不可直接相加'
            dossier['facts'].append(fact)
            if flagged:
                dossier['actions'].append({'evidence':key,'text':f'核对{scope}在{period["startDate"]}至{period["endDate"]}及等长上期的已发货订单，按{label}差额{abs(a-b)}定位变动订单；先核实发货节奏。'})
                dossier['hypotheses'].append({'evidence':key,'text':'待核实：集中发货或订单结构变化可能影响该指标；现有汇总与趋势不能确认原因。'})
            elif not b or not enough:
                dossier['unanswered'].append(f'{scope} {label}：{assessment}；显示的非零基数比值仅为算术描述，不据此作经营异常判断。')
        dossier['facts'].append(self.fact('dailyMean',f'{scope} 日均销售额与首末日比较仅描述已查询数据',metrics['dailyMean'],'不适用（描述统计）',
            f'逐日金额合计/{(end-start).days}天；首末日差={trend["points"][-1]["salesAmount"]}−{trend["points"][0]["salesAmount"]}；不代表趋势预测',
            (summary,trend),period,tools,scope=scope,definition='该范围含税已发货销售额除以日历天数（包含零销售日）；首末日比较不是趋势预测'))
        points=trend['points'];mean=sum((number(p['salesAmount']) for p in points),Decimal(0))/len(points)
        daily_enough=(len(points)>=self.rules.minimum_days and sum(number(p['salesAmount'])>0 for p in points)>=self.rules.minimum_active_days
                      and number(summary['current']['orderCount'])>=self.rules.minimum_orders)
        if daily_enough and mean:
            for p in points:
                if number(p['salesAmount'])<=mean*self.rules.daily_multiple:continue
                id='daily:'+p['date']
                dossier['facts'].append(self.fact(id,f'{scope} {p["date"]}单日销售额触发日均倍数筛查',money(p['salesAmount']),money(mean*self.rules.daily_multiple),
                    f'单日额>{self.rules.daily_multiple}×（全期间日额之和/{len(points)}天）；仅描述日额集中，不推断原因',(summary,trend),period,tools,flagged=True,assessment='超过日均倍数阈值'))
                dossier['actions'].append({'evidence':id,'text':f'核对{scope}在{p["date"]}的已发货订单与单日金额{money(p["salesAmount"])}元，确认是否集中发货，不外推后续销量。'})
        if not dossier['actions']:
            dossier['actions'].append({'evidence':'salesAmount','text':f'针对{scope}的当前已发货销售额{metrics["salesAmount"]}元，核对所选{period["startDate"]}至{period["endDate"]}范围及发货状态；样本不足时先补充可比期间，不给出增长或采购目标。'})
        dossier['unanswered'].append('销售变化与缺货的因果关系未回答：未取得对应历史缺货、订单履约与发货受阻证据；当前库存或待入库也不能证明历史下降原因。')
        return dossier

    def concentration(self,summary,ranking,period):
        tools=('get_sales_summary','get_customer_sales_ranking')
        self.paired(summary,ranking,period,tools,(None,'customer'))
        self.totals(summary)
        total=number(summary['current']['salesAmount']);orders=number(summary['current']['orderCount']);rows=ranking['rows']
        if orders!=orders.to_integral_value() or len(rows)>4 or ranking.get('limit',4)!=4:raise ValueError('Invalid population/ranking bound')
        seen=set();last=None
        for r in rows:
            if not isinstance(r['id'],str) or not re.fullmatch(r'[1-9]\d{0,18}',r['id']) or r['id'] in seen:raise ValueError('Invalid customer ID')
            seen.add(r['id']);amount=number(r['salesAmount']);count=number(r['orderCount'])
            if count!=count.to_integral_value() or count>orders or (amount>0 and not count) or (last is not None and amount>last):raise ValueError('Invalid ranking')
            last=amount
        if sum((number(r['salesAmount']) for r in rows),Decimal(0))>total or sum((number(r['orderCount']) for r in rows),Decimal(0))>orders:raise ValueError('Ranking exceeds total')
        if (total>0 and not rows) or (not total and any(number(r['salesAmount'])>0 for r in rows)) or (not orders and total):raise ValueError('Conflicting denominator')
        dossier=self.dossier()
        if not summary.get('snapshotId'):dossier['unanswered'].append('缺少原子快照标识，不能确认跨工具读取一致性。')
        if not total or orders<self.rules.minimum_orders:
            dossier['unanswered'].append(f'客户集中度未计算：本期总销售额为零或订单少于{self.rules.minimum_orders}单，样本不足。')
            return dossier
        if len(rows)<4 and sum(number(r['salesAmount']) for r in rows)!=total:raise MissingEvidence('客户排行不足4名且不是完整销售总体，不能确认Top3覆盖范围')
        for n,threshold in ((1,self.rules.top1_percent),(3,self.rules.top3_percent)):
            selected=rows[:n];amount=sum((number(r['salesAmount']) for r in selected),Decimal(0))
            share=amount/total*100;minimum=self.rules.minimum_top1_customers if n==1 else self.rules.minimum_top3_customers;enough=len(rows)>=minimum
            f=self.fact('customerTop'+str(n),f'客户Top{n}销售额占全厂含税已发货销售额的比重',money(share)+'%',money(threshold)+'%',
                f'{money(amount)}/{money(total)}×100%；分母为同期间全厂销售额，不是前N名合计',
                (summary,ranking),period,tools,entityId=rows[0]['id'],entityIds=[r['id'] for r in selected],
                definition=f'同期间客户Top{n}的含税已发货销售单头金额 / 同期间全厂含税已发货销售单头金额；不是客户数或订单数占比',
                flagged=enough and share>=threshold,assessment='触发集中度筛查' if enough and share>=threshold else f'已知客户不足{minimum}个，不作集中度预警' if not enough else '未触发集中度阈值')
            dossier['facts'].append(f)
            if f['flagged']:
                dossier['actions'].append({'evidence':f['id'],'text':f'核对客户ID {", ".join(f["entityIds"])} 的复购与待履约订单；其Top{n}占比{f["current"]}，评估客户结构，不将集中度视为已发生流失。'})
                dossier['hypotheses'].append({'evidence':f['id'],'text':'待核实：集中发货或单个大单可能抬高占比；当前排行不能证明依赖风险已经造成损失。'})
        return dossier

    def operations(self,name,data):
        dossier=self.dossier();source=data['source'];as_of=data['asOf']
        when=datetime.fromisoformat(str(as_of).replace('Z','+00:00'))
        if when.tzinfo is None or data['timezone']!='Asia/Shanghai':raise ValueError('Invalid current timestamp')
        if not isinstance(source,str) or not source:raise MissingEvidence('库存证据缺少工具来源')
        if name in ('get_current_stock','get_low_stock_alerts'):
            for r in data['rows']:
                current,minimum=number(r['quantity']),number(r['minStock'])
                if current!=current.to_integral_value() or minimum!=minimum.to_integral_value() or type(r['lowStock'])!=bool or r['lowStock']!=(current<minimum):raise ValueError('Contradictory stock threshold')
                if not current<minimum:continue
                id='stock:'+r['warehouseId']+':'+r['materialId']
                dossier['facts'].append({'id':id,'statement':f'仓库ID {r["warehouseId"]} / 物料ID {r["materialId"]}（{safe(r["materialName"])}）低于最低库存',
                    'definition':'当前库存余额与物料最低库存阈值，单位='+r['unit'],'period':{'asOf':as_of},'timezone':'Asia/Shanghai','asOf':{name:as_of},'sources':{name:source},
                    'current':r['quantity'],'comparison':r['minStock'],'formula':'quantity < minStock；补足阈值参考=max(minStock−quantity,0)，不是工单需求','tools':[name],'flagged':True})
                dossier['actions'].append({'evidence':id,'text':f'核对仓库ID {r["warehouseId"]}、物料ID {r["materialId"]}；补足最低阈值参考{minimum-current} {r["unit"]}，先核对待入库和其他工单需求，不自动采购或预留。'})
        elif name=='get_production_shortage' and data.get('needsIssue',True):
            for r in data['items']:
                required,available,shortage=(number(r[k]) for k in ('requiredQuantity','availableQuantity','shortageQuantity'))
                if any(n!=n.to_integral_value() for n in (required,available,shortage)):raise ValueError('Fractional material demand')
                if shortage!=max(required-available,Decimal(0)):raise ValueError('Conflicting shortage')
                if not shortage:continue
                id='shortage:'+r['materialId']
                dossier['facts'].append({'id':id,'statement':f'工单{safe(data["orderNo"])} / 物料ID {r["materialId"]}有缺料',
                    'definition':f'确认时BOM版本{data["bomRevision"]}的需求与当前发料仓余额，单位={r["unit"]}',
                    'period':{'asOf':as_of},'timezone':'Asia/Shanghai','asOf':{name:as_of},'sources':{name:source},'current':r['availableQuantity'],'comparison':r['requiredQuantity'],
                    'formula':f'max({required}−{available},0)={shortage}；多工单需求不预留','tools':[name],'flagged':True})
                dossier['actions'].append({'evidence':id,'text':f'针对工单{safe(data["orderNo"])}、物料ID {r["materialId"]}核实补料参考{shortage} {r["unit"]}；需求未预留，不创建采购单、领料或入库。'})
        if dossier['facts']:dossier['hypotheses'].append({'evidence':dossier['facts'][0]['id'],'text':'待核实：待入库单是否能按时执行、其他工单是否竞争同一余额。当前短缺不能证明历史销量下降由缺货导致。'})
        return dossier

def render_evidence(dossier,maximum=DEFAULT_RULES.maximum_expanded_facts):
    lines=['### 已查询确认的事实与证据']
    for f in dossier['facts'][:maximum]:
        period=f['period'];period_text=f'{period["startDate"]} 至 {period["endDate"]}（结束日排除）' if 'startDate' in period else '当前查询时刻'
        lines += [f'- [{safe(f["id"])}] {safe(f["statement"])}。当前值={safe(f["current"])}；比较值={safe(f["comparison"])}。',
                  f'  定义：{safe(f["definition"])}；范围：{period_text}；Asia/Shanghai。',
                  f'  方法：{safe(f["formula"])}；判断：{safe(f.get("assessment","触发既定库存规则"))}。',
                  '  工具/来源/查询时刻：'+'；'.join(f'{safe(t)} / {safe(f["sources"][t])} / {safe(f["asOf"][t])}' for t in f['tools'])+'。']
        if f.get('snapshotId'):lines.append('  原子读取范围：'+safe(f.get('snapshotScope'))+'；快照：'+safe(f['snapshotId'])+'；日期口径：'+safe(f.get('dateBasis'))+'。')
    if not dossier['facts']:lines+=['- 本次没有新增的异常结论；已校验的原始查询数值或无匹配结果见上表。']
    if len(dossier['facts'])>maximum:lines += [f'- 匹配{len(dossier["facts"])}条；仅展开前{maximum}条证据/建议，其余对象的已校验数值见上表，可按ID缩小范围继续。']
    lines+=['','### 仍需核实的可能解释']
    lines += [f'- [{safe(h["evidence"])}] {safe(h["text"])}' for h in dossier['hypotheses'][:maximum]] or ['- 未提出未经证据支持的原因。']
    lines+=['','### 建议采取的行动']
    lines += [f'- [{safe(a["evidence"])}] {safe(a["text"])}' for a in dossier['actions'][:maximum]] or ['- 当前未形成可据证据提出的新增行动；存在数据缺口时，按未回答项补充。']
    if dossier['unanswered']:lines+=['','### 尚未回答 / 数据限制']+[f'- {safe(text)}' for text in dossier['unanswered']]
    lines+=['','规则：'+dossier['rules']]
    return '\n'.join(lines)
