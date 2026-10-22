from datetime import date

from app.graph import resolve_period


def test_relative_dates_use_injected_shanghai_day():
    assert resolve_period('最近3天销售', {}, today=date(2026, 10, 20)) == {
        'startDate': '2026-10-18', 'endDate': '2026-10-21'}
