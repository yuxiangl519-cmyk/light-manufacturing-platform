# AI评测基线 v2

| 验证层 | 通过 | 失败 | 跳过/未运行 | 用例耗时(秒) |
|---|---:|---:|---:|---:|
| deterministic-no-model | 81 | 0 | 0 | 0.459 |
| mysql-test-tools | 7 | 0 | 0 | 3.370 |
| real-model | 24 | 0 | 0 | 79.951 |

模型：deepseek-v4-flash；固定案例版本：ai-baseline-v2。

失败/未运行原因：
- 无。

逐项耗时见 report.json；mysql-test-tools 中的 Java SSE 上游为测试桩，不算真实模型。
真实模型故障探针为 Agent→真实网络验证，不覆盖 Java 转发故障；scope 字段明确标记。
