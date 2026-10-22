"""Natural periods at a fixed Shanghai day; no run-day-dependent expectations."""
CALENDAR_CASES = [
    ('today', '今天销售', '2026-10-20', '2026-10-21'),
    ('yesterday', '昨天销售', '2026-10-19', '2026-10-20'),
    ('this_week', '本周销售', '2026-10-19', '2026-10-21'),
    ('last_week', '上周销售', '2026-10-12', '2026-10-19'),
    ('this_month', '本月销售', '2026-10-01', '2026-10-21'),
    ('last_month', '上月销售', '2026-09-01', '2026-10-01'),
    ('selected_month', '2024年2月销售', '2024-02-01', '2024-03-01'),
    ('recent_days', '最近7天销售', '2026-10-14', '2026-10-21'),
]
