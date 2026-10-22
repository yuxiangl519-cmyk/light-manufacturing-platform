import pytest
from evaluation.dimension_checks import aggregate,check_ranking


def test_dimension_oracle_counts_distinct_orders_and_keeps_units():
    result=aggregate([['1','11','n','2026-10-01 00:00:00','10.01','台','2'],
                      ['1','12','n','2026-10-01 00:00:00','20.02','套','3']])
    assert result=={'salesAmount':'30.03','orderCount':'1','quantitiesByUnit':{'台':'2','套':'3'}}


def test_unit_quantity_checker_rejects_numeric_substring_match(monkeypatch):
    import evaluation.dimension_checks as checks
    monkeypatch.setattr(checks,'ranking',lambda *args:[{'id':'11','name':'n','salesAmount':'10.00','orderCount':'1','quantitiesByUnit':{'台':'2'}}])
    report=('商品销售额排行：2026-10-01 至 2026-10-03（结束日期不含当天，Asia/Shanghai）\n'
            '| 11 | n | 10.00 | 1 | 12 台 |\n不是收入或利润\n数据来源：sales_order.status=SHIPPED;line_amount')
    with pytest.raises(AssertionError):check_ranking(report,'2026-10-01','2026-10-03','product')
