"""Versioned deterministic analysis policy. No model-selected thresholds."""
from dataclasses import dataclass
from decimal import Decimal

@dataclass(frozen=True)
class AnalysisRules:
    version: str = 'evidence-v1'
    change_percent: Decimal = Decimal('30')
    amount_delta: Decimal = Decimal('1000')
    order_delta: int = 5
    minimum_orders: int = 5
    daily_multiple: Decimal = Decimal('2')
    minimum_days: int = 7
    minimum_active_days: int = 3
    top1_percent: Decimal = Decimal('50')
    top3_percent: Decimal = Decimal('80')
    minimum_top1_customers: int = 1
    minimum_top3_customers: int = 4
    maximum_read_skew_seconds: int = 60
    maximum_expanded_facts: int = 10

    def __post_init__(self):
        decimals=(self.change_percent,self.amount_delta,self.daily_multiple,self.top1_percent,self.top3_percent)
        if any(not isinstance(v,Decimal) or not v.is_finite() or v<=0 for v in decimals):raise ValueError('Invalid rule thresholds')
        if any(type(v)!=int or v<1 for v in (self.order_delta,self.minimum_orders,self.minimum_days,self.minimum_active_days,self.minimum_top1_customers,self.minimum_top3_customers,self.maximum_read_skew_seconds,self.maximum_expanded_facts)):raise ValueError('Invalid rule limits')
        if self.top1_percent>100 or self.top3_percent>100:raise ValueError('Invalid concentration threshold')

    def describe(self):
        return (f'{self.version}：明显变化需本期和上期各至少{self.minimum_orders}单、变化率绝对值≥{self.change_percent}%，'
                f'且销售额差额≥{self.amount_delta}元或对应订单数差额≥{self.order_delta}单；'
                f'日额异常需≥{self.minimum_days}天、≥{self.minimum_active_days}个有销售日及≥{self.minimum_orders}单，单日额>{self.daily_multiple}倍日均；'
                f'客户集中度需本期≥{self.minimum_orders}单，Top1≥{self.top1_percent}%（至少{self.minimum_top1_customers}个客户），Top3≥{self.top3_percent}%（至少{self.minimum_top3_customers}个客户）。'
                f'跨工具读取时刻差≤{self.maximum_read_skew_seconds}秒，仍不代表同一数据库快照。'
                '低库存沿用物料最低库存阈值，严格小于才预警；缺料沿用max(确认需求−当前余额,0)>0。'
                '这些是业务筛查阈值，不是统计显著性检验或预测。')

DEFAULT_RULES=AnalysisRules()
