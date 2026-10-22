# 销售指标业务口径

- 指标：salesAmount、orderCount。
- 定义：按创建时间筛选当前已发货 SHIPPED 销售单，人民币含税订单金额，不是收入或利润。
- 币种：CNY。
- 来源：sales_order.status=SHIPPED;created_at;deleted_at IS NULL。
- 时区：Asia/Shanghai。
- 日期：startDate 包含当天，endDate 不包含当天，最多366天。
- 对比：相邻、等长的上期；不是任意日历月同比或环比。
- 查询：get_sales_summary、get_sales_trend；execute_safe_query 仅允许 sales_summary / sales_trend 模板。
- 本文件用于全厂模型规划分支，该分支只调用原三个全厂工具。客户/商品销售额排行和指定ID汇总/趋势由确定性Dimensions分支执行；候选名称和身份不交模型选择。重名、多匹配或未知对象必须先向用户澄清。
- 不支持：利润、成本、跨物料数量相加、任意SQL、写操作。

- 时间理解：最近N天包含今天；本周从周一至今天，上周为完整周一至周日；本月/指定当前月份为月初至今天，上月/历史指定月份为完整自然月。明确日期包含首尾日，工具结束日排除。日期由代码计算，不由模型计算。
- 上期为零时不可比（包括本期为零）；不得输出误导性百分比。通用经营问题须确认日期和销售指标；收入/营收没有确认口径，不能用含税订单金额代替。

- 第3阶段：客户金额用单头total_amount，商品用已确认明细line_amount；当前SHIPPED和创建时间口径不变。订单按销售单ID去重，数量按快照单位分组，不跨单位或跨商品相加；停用资料仍可历史查询。维度工具不出现在模型工具schemas中。
