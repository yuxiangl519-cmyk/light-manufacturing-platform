from decimal import Decimal
import pytest

from app.analytics import analyze, growth


def test_decimal_growth_and_zero_baseline():
    assert growth(Decimal('120.01'), Decimal('100')) == '20.01%'
    assert growth(Decimal('0'), Decimal('0')) == '不可比（上期为零）'
    assert growth(Decimal('3'), Decimal('0')) == '不可比（上期为零）'


def test_analysis_reports_only_source_numbers_and_anomalies():
    result = analyze({'current': {'salesAmount': '120.01', 'orderCount': '2'},
                      'previous': {'salesAmount': '100', 'orderCount': '1'}},
                     {'points': [{'date': '2026-10-01', 'salesAmount': '0'},
                                 {'date': '2026-10-02', 'salesAmount': '120.01'}]})
    assert result['amountGrowth'] == '20.01%'
    assert result['trendDirection'] == '上升'
    assert result['anomalies']


def test_mismatched_summary_trend_is_rejected():
    with pytest.raises(ValueError, match='inconsistent'):
        analyze({'current': {'salesAmount': '100', 'orderCount': '1'},
                 'previous': {'salesAmount': '0', 'orderCount': '0'}},
                {'points': [{'date': '2026-10-01', 'salesAmount': '90'}]})


def test_fractional_order_count_is_rejected():
    with pytest.raises(ValueError):
        analyze({'current': {'salesAmount': '100', 'orderCount': '1.5'},
                 'previous': {'salesAmount': '0', 'orderCount': '0'}},
                {'points': [{'date': '2026-10-01', 'salesAmount': '100'}]})
