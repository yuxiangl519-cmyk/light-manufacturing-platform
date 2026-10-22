"""All reported numeric conclusions come from Decimal, never model arithmetic."""
from decimal import Decimal, ROUND_HALF_UP
from datetime import date
from .analysis_rules import DEFAULT_RULES


def number(value):
    result = Decimal(str(value))
    if not result.is_finite() or result < 0:
        raise ValueError('Invalid nonnegative source metric')
    return result


def money(value):
    return str(number(value).quantize(Decimal('.01'), rounding=ROUND_HALF_UP))


def growth(current: Decimal, previous: Decimal):
    if previous == 0:
        return '不可比（上期为零）'
    return f'{((current - previous) / previous * 100).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)}%'


def analyze(summary: dict, trend: dict, rules=DEFAULT_RULES):
    current, previous = summary['current'], summary['previous']
    points = trend['points']
    amounts = [number(point['salesAmount']) for point in points]
    dates = [date.fromisoformat(point['date']) for point in points]
    if dates != sorted(set(dates)):
        raise ValueError('Duplicate or unordered trend dates')
    for row in (current, previous):
        count = number(row['orderCount'])
        if count != count.to_integral_value():
            raise ValueError('Order count must be integral')
    if sum(amounts, Decimal(0)) != number(current['salesAmount']):
        raise ValueError('Summary and trend are inconsistent')
    mean = sum(amounts, Decimal(0)) / len(amounts) if amounts else Decimal(0)
    reliable=(len(amounts)>=rules.minimum_days and sum(a>0 for a in amounts)>=rules.minimum_active_days
              and number(current['orderCount'])>=rules.minimum_orders)
    anomalies = [f'{point["date"]} 销售额 {money(point["salesAmount"])} 元，超过当期日均的{rules.daily_multiple}倍'
                 for point, amount in zip(points, amounts) if reliable and mean and amount > mean * rules.daily_multiple]
    if amounts and any(amount == 0 for amount in amounts) and any(amount > 0 for amount in amounts):
        anomalies.append('存在零销售日；仅为统计提示，需结合发货节奏判断，不能据此推断原因。')
    delta = amounts[-1] - amounts[0] if amounts else Decimal(0)
    return {'salesAmount': money(current['salesAmount']),
            'previousSalesAmount': money(previous['salesAmount']),
            'orderCount': str(number(current['orderCount'])),
            'previousOrderCount': str(number(previous['orderCount'])),
            'amountGrowth': growth(number(current['salesAmount']), number(previous['salesAmount'])),
            'orderGrowth': growth(number(current['orderCount']), number(previous['orderCount'])),
            'dailyMean': money(mean),
            'trendDirection': '上升' if delta > 0 else '下降' if delta < 0 else '持平',
            'anomalies': anomalies}


class AnalyticsEngine:
    """Deterministic, stateless engine; no model-generated numeric facts."""
    def analyze(self, summary: dict, trend: dict):
        return analyze(summary, trend)
