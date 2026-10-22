"""Strict semantic checks for the existing deterministic Markdown reporter."""
import re
from decimal import Decimal, ROUND_HALF_UP


def rate(current, previous):
    a, b = Decimal(current), Decimal(previous)
    return '不可比（上期为零）' if not b else str(
        ((a-b)*100/b).quantize(Decimal('.01'), rounding=ROUND_HALF_UP))+'%'


def check_report(result, start, end, current, previous, source, *, definition='按创建时间筛选当前已发货销售单，人民币含税订单金额；不是收入或利润'):
    content = result['content']
    assert result['context']['startDate'] == start, 'start date mismatch'
    assert result['context']['endDate'] == end, 'end date mismatch'
    for label, key in [('已发货销售额（元）', 'salesAmount'), ('已发货订单数', 'orderCount')]:
        match = re.search(r'\|\s*'+re.escape(label)+r'\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|', content)
        assert match, 'missing metric row: '+key
        actual = [x.strip() for x in match.groups()]
        assert Decimal(actual[0]) == Decimal(current[key]), key+' current mismatch'
        assert Decimal(actual[1]) == Decimal(previous[key]), key+' previous mismatch'
        assert actual[2] == rate(current[key], previous[key]), key+' growth mismatch'
    assert f'{start} 至 {end}（结束日期不含当天' in content, 'report dates mismatch'
    public_source='已发货销售订单受控统计' if source.startswith('sales_order.status=SHIPPED;created_at;deleted_at IS NULL') else source
    assert any(f'数据来源：{name}；币种：CNY' in content for name in (source,public_source)), 'source/currency mismatch'

    assert '时区：Asia/Shanghai' in content, 'timezone missing'
    assert definition in content or definition.replace('line_amount','明细行金额') in content, 'metric definition mismatch'
