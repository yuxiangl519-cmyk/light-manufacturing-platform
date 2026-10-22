"""Combine fresh JUnit and live results; no raw logs/secrets in public report."""
import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def summarize(root, live=False):
    root = Path(root)
    results = []
    files = [('deterministic-no-model', root/'output/ai-evaluation/unit.xml')]
    files += [('mysql-test-tools', root/'output/ai-evaluation'/name) for name in (
        'TEST-io.github.lightmanufacturing.SalesAnalyticsIntegrationTest.xml',
        'TEST-io.github.lightmanufacturing.AiIntegrationTest.xml',
        'TEST-io.github.lightmanufacturing.SalesDimensionsIntegrationTest.xml',
        'TEST-io.github.lightmanufacturing.AiOperationsIntegrationTest.xml',
        'TEST-io.github.lightmanufacturing.AiEvidenceIntegrationTest.xml')]
    for layer, path in files:
        if not path.exists():
            results.append(dict(layer=layer, case=path.name, status='not-run', seconds=0, reason='No fresh JUnit report'))
            continue
        try:
            cases = list(ET.parse(path).iter('testcase'))
        except ET.ParseError:
            cases = []
        if not cases:
            results.append(dict(layer=layer, case=path.name, status='failed', seconds=0, reason='Empty or invalid JUnit report / collection failure'))
        for case in cases:
            failure = case.find('failure')
            if failure is None: failure = case.find('error')
            skipped = case.find('skipped')
            status = 'failed' if failure is not None else 'skipped' if skipped is not None else 'passed'
            results.append(dict(layer=layer, case=case.get('name'), status=status,
                                seconds=float(case.get('time', 0)),
                                reason=failure.get('type', 'Assertion failed') if failure is not None else ''))
    live_path = root/'output/ai-evaluation/live.json'
    model = None
    calendar_clock = None
    if live and live_path.exists():
        live_report = json.loads(live_path.read_text(encoding='utf-8'))
        model = live_report.get('model')
        calendar_clock = live_report.get('calendarClock')
        results.extend(live_report['results'])
    else:
        results.append(dict(layer='real-model', case='explicit-live-suite', status='not-run', seconds=0,
                            reason='Requires -Live; deterministic doubles are not live verification'))
    report = dict(decisionPolicy='model-loop-v1', analysisPolicy='evidence-v1', suite='ai-baseline-v3', fixedClock='2026-10-02', calendarClock=calendar_clock, model=model, results=results)
    out = root/'output/ai-evaluation'
    out.mkdir(parents=True, exist_ok=True)
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    lines = ['# AI评测基线 v3', '', '| 验证层 | 通过 | 失败 | 跳过/未运行 | 用例耗时(秒) |', '|---|---:|---:|---:|---:|']
    for layer in dict.fromkeys(r['layer'] for r in results):
        rows = [r for r in results if r['layer']==layer]
        lines.append(f"| {layer} | {sum(r['status']=='passed' for r in rows)} | {sum(r['status']=='failed' for r in rows)} | {sum(r['status'] in ('skipped','not-run') for r in rows)} | {sum(r['seconds'] for r in rows):.3f} |")
    lines += ['', '模型：'+(model or '未启用真实模型')+'；固定案例版本：ai-baseline-v3。', '', '失败/未运行原因：']
    lines += [f"- {r['layer']} / {r['case']}: {r['reason'] or r['status']}" for r in results if r['status']!='passed'] or ['- 无。']
    lines += ['', '分析规则：evidence-v1；逐项耗时见 report.json；mysql-test-tools 中的 Java SSE 上游为测试桩，不算真实模型。',
              '真实模型故障探针为 Agent→真实网络验证，不覆盖 Java 转发故障；scope 字段明确标记。',
              '生产策略：model-loop-v1；RuleWorkflow覆盖既有规则/报告组件，新主环路由test_decision_loop单独覆盖。显式Live下所有业务HTTP问题均调用真实模型；无模型测试桩不算真实模型验证。']
    (out/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return any(r['status'] in ('failed','not-run','skipped') for r in results if live or r['layer']!='real-model')


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--root', default='.')
    parser.add_argument('--live', action='store_true')
    args=parser.parse_args()
    raise SystemExit(summarize(args.root,args.live))
