# AI评测基线 v3

| 验证层 | 通过 | 失败 | 跳过/未运行 | 用例耗时(秒) |
|---|---:|---:|---:|---:|
| deterministic-no-model | 105 | 0 | 0 | 0.683 |
| mysql-test-tools | 13 | 0 | 0 | 6.669 |
| real-model | 22 | 1 | 0 | 153.696 |
| dimension-http-real-tools | 7 | 0 | 0 | 5.105 |

模型：deepseek-v4-flash；固定案例版本：ai-baseline-v3。

失败/未运行原因：
- real-model / calendar_yesterday: Terminal status mismatch; codes=MODEL_REQUEST_FAILED

逐项耗时见 report.json；mysql-test-tools 中的 Java SSE 上游为测试桩，不算真实模型。
真实模型故障探针为 Agent→真实网络验证，不覆盖 Java 转发故障；scope 字段明确标记。
dimension-http-real-tools 使用真实Java/MySQL，维度路径不调用模型；不把工具验证计成模型验证。
