"""Independent row-level MySQL oracle for dimension HTTP evaluation."""
import os
import re
import subprocess
from datetime import date,datetime,time,timedelta,timezone
from decimal import Decimal
from app.dates import SHANGHAI


def query(sql):
    assert os.getenv('AI_LIVE_TEST_DATABASE')=='mini_mfg_test'
    assert '/mini_mfg_test?' in os.environ['MFG_TEST_DB_URL']
    result=subprocess.run([os.environ['AI_EVAL_MYSQL'],'--no-defaults','--host=127.0.0.1','--port=13306',
        '--user='+os.environ['MFG_TEST_DB_USERNAME'],'--database=mini_mfg_test','--default-character-set=utf8mb4',
        '--batch','--skip-column-names','--execute','SELECT DATABASE(); '+sql],
        env=dict(os.environ,MYSQL_PWD=os.environ['MFG_TEST_DB_PASSWORD']),capture_output=True,text=True,encoding='utf-8',timeout=15)
    assert result.returncode==0,'Dimension oracle SQL failed (details suppressed)'
    lines=result.stdout.splitlines();assert lines[0]=='mini_mfg_test','Oracle database mismatch'
    return [line.split('\t') for line in lines[1:]]


def detail(start,end,dimension,entity_id=None):
    assert dimension in ('customer','product')
    def utc(day):return datetime.combine(date.fromisoformat(day),time(),SHANGHAI).astimezone(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')
    if dimension=='customer':
        fields="o.id,o.customer_id,o.partner_name,o.created_at,o.total_amount,'',0"
        join='';id_column='o.customer_id'
    else:
        fields='o.id,i.material_id,i.material_name,o.created_at,i.line_amount,i.unit,i.quantity'
        join=' join sales_order_item i on i.sales_order_id=o.id';id_column='i.material_id'
    where=''
    if entity_id is not None:
        assert re.fullmatch(r'[1-9][0-9]{0,18}',entity_id)
        where=' and '+id_column+'='+str(int(entity_id))
    return query("SELECT "+fields+" FROM sales_order o"+join+" WHERE o.status='SHIPPED' AND o.deleted_at IS NULL AND o.created_at>='"+utc(start)+"' AND o.created_at<'"+utc(end)+"'"+where+' ORDER BY o.created_at,o.id')


def aggregate(rows):
    result={'salesAmount':Decimal(0),'ids':set(),'quantitiesByUnit':{}}
    for order_id,entity_id,name,stamp,amount,unit,qty in rows:
        result['salesAmount']+=Decimal(amount);result['ids'].add(order_id)
        if unit:result['quantitiesByUnit'][unit]=result['quantitiesByUnit'].get(unit,Decimal(0))+Decimal(qty)
    return {'salesAmount':str(result['salesAmount'].quantize(Decimal('.01'))),'orderCount':str(len(result['ids'])),
            'quantitiesByUnit':{u:str(q) for u,q in result['quantitiesByUnit'].items()}}


def ranking(start,end,dimension,limit=10):
    groups={};names={}
    for row in detail(start,end,dimension):
        groups.setdefault(row[1],[]).append(row);names[row[1]]=row[2]
    rows=[{'id':id,'name':names[id],**aggregate(items)} for id,items in groups.items()]
    return sorted(rows,key=lambda row:(-Decimal(row['salesAmount']),int(row['id'])))[:limit]


def parse_units(text):
    if text=='无已发货商品数量':return {}
    result={}
    for item in text.split('；'):
        match=re.fullmatch(r'([0-9]+) (.+)',item.strip())
        assert match,'Invalid quantity cell'
        assert match[2] not in result,'Duplicate unit'
        result[match[2]]=Decimal(match[1])
    return result


def check_ranking(content,start,end,dimension,limit=10):
    expected=ranking(start,end,dimension,limit)
    actual=[]
    for line in content.splitlines():
        match=re.match(r'^\|\s*(\d+)\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|',line)
        if match:actual.append([part.strip() for part in match.groups()])
    assert len(actual)==len(expected),'Ranking row count differs from independent details'
    for got,want in zip(actual,expected):
        assert got[0]==want['id'],'Ranking ID/order mismatch'
        assert got[1]==want['name'].replace('|','｜'),'Ranking snapshot name mismatch'
        assert Decimal(got[2])==Decimal(want['salesAmount']),'Ranking amount mismatch'
        assert Decimal(got[3])==Decimal(want['orderCount']),'Ranking distinct order count mismatch'
        if dimension=='product':
            assert parse_units(got[4])=={u:Decimal(q) for u,q in want['quantitiesByUnit'].items()},'Ranking unit quantity mismatch'
    assert f'{start} 至 {end}（结束日期不含当天，Asia/Shanghai）' in content,'Ranking date/timezone mismatch'
    assert '不是收入或利润' in content and any(s in content for s in ('数据来源：sales_order.status=SHIPPED','数据来源：已发货销售订单受控统计')),'Ranking definition/source mismatch'
    assert any(s in content for s in ('line_amount','明细行金额')) == (dimension=='product'),'Ranking amount basis mismatch'


def check_entity(result,start,end,dimension):
    from evaluation.checks import check_report
    context=result['context'];id=context['entityId']
    assert context['dimension']==dimension
    now=detail(start,end,dimension,id)
    a,b=date.fromisoformat(start),date.fromisoformat(end)
    previous=detail(str(a-(b-a)),start,dimension,id)
    current,old=aggregate(now),aggregate(previous)
    source='sales_order.status=SHIPPED;created_at;deleted_at IS NULL'
    if dimension=='product':source+=';sales_order_item.line_amount;material_id;unit'
    definition=('按销售单创建时间筛选当前已发货的指定商品明细金额（line_amount），人民币含税；不是收入或利润' if dimension=='product'
                else '按创建时间筛选当前已发货销售单，人民币含税订单金额；不是收入或利润')
    check_report(result,start,end,current,old,source,definition=definition)
    if dimension=='product':
        match=re.search(r'本期数量：(.*?)；上期数量：(.*?)。不同单位不相加。',result['content'])
        assert match,'Missing scoped unit quantities'
        assert parse_units(match[1])=={u:Decimal(q) for u,q in current['quantitiesByUnit'].items()},'Entity current quantity mismatch'
        assert parse_units(match[2])=={u:Decimal(q) for u,q in old['quantitiesByUnit'].items()},'Entity previous quantity mismatch'
    from decimal import ROUND_HALF_UP
    mean=(Decimal(current['salesAmount'])/(b-a).days).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
    assert f'日均销售额：{mean} 元' in result['content'],'Scoped daily mean mismatch'
    assert now==detail(start,end,dimension,id),'Source changed during scoped evaluation'
