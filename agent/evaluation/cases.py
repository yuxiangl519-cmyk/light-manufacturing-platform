"""Versioned questions. Dates and expected outcomes never depend on run day."""
CASES = [
    ('summary', '分析2026-10-01至2026-10-02销售汇总', 'completed'),
    ('trend', '分析2026-10-01至2026-10-02每日销售趋势', 'completed'),
    ('comparison', '分析2026-10-01至2026-10-02销售环比', 'completed'),
    ('empty', '分析1990-01-01至1990-01-02销售情况', 'completed'),
    ('dates', '分析销售情况', 'clarification'),
    ('profit', '分析2026-10-01至2026-10-02利润', 'clarification'),
    ('customer', '分析2026-10-01至2026-10-02客户销售排名', 'completed'),
    ('unauthorized', '分析2026-10-01至2026-10-02销售', 'denied'),
    ('model_failure', '分析2026-10-01至2026-10-02销售', 'error'),
    ('tool_failure', '分析2026-10-01至2026-10-02销售', 'error'),
    ('cancel', '分析2026-10-01至2026-10-02销售', 'cancelled'),
    ('resume', '最近2天', 'completed'),
]
SOURCE = 'sales_order.status=SHIPPED;created_at;deleted_at IS NULL'
