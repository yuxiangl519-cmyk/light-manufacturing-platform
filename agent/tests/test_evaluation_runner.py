import json
from pathlib import Path

from evaluation.report import summarize


def test_report_records_failure_skip_duration_and_never_reads_raw_body(tmp_path):
    out = tmp_path/'output/ai-evaluation'
    out.mkdir(parents=True)
    (out/'unit.xml').write_text('''<testsuite><testcase name="ok" time="1.25"/>
      <testcase name="bad" time="2"><failure type="AssertionError">sensitive raw output</failure></testcase>
      <testcase name="skip" time="0"><skipped/></testcase></testsuite>''')
    assert summarize(tmp_path)
    report = json.loads((out/'report.json').read_text())
    assert report['results'][0]['seconds'] == 1.25
    assert report['results'][1]['reason'] == 'AssertionError'
    assert report['results'][2]['status'] == 'skipped'
    assert 'sensitive raw output' not in (out/'report.json').read_text()
    assert report['results'][-1]['status'] == 'not-run'


def test_live_is_never_inferred_from_old_result_file(tmp_path):
    out = tmp_path/'output/ai-evaluation'
    out.mkdir(parents=True)
    (out/'live.json').write_text('{"results":[{"layer":"real-model","case":"old","status":"passed","seconds":1}]}')
    summarize(tmp_path)
    assert json.loads((out/'report.json').read_text())['results'][-1]['status'] == 'not-run'


def test_independent_oracle_groups_utc_rows_as_shanghai_days(monkeypatch):
    import subprocess
    from evaluation.live import oracle
    from datetime import date
    for key,value in {'AI_LIVE_TEST_DATABASE':'mini_mfg_test','MFG_TEST_DB_URL':'jdbc:mysql://127.0.0.1:13306/mini_mfg_test?',
                      'MFG_TEST_DB_PASSWORD':'not-a-real-secret','MFG_TEST_DB_USERNAME':'mini_mfg_test','AI_EVAL_MYSQL':'mysql'}.items():
        monkeypatch.setenv(key,value)
    def run(command, **kwargs):
        assert '--database=mini_mfg_test' in command
        assert 'not-a-real-secret' not in str(command)
        assert 'SUM(' not in command[-1].upper()
        return subprocess.CompletedProcess(command,0,'mini_mfg_test\n2026-09-30 15:59:59\t100.00\n2026-09-30 16:00:00\t10.01\n2026-10-01 16:00:00\t20.02\n','')
    monkeypatch.setattr(subprocess,'run',run)
    totals, daily = oracle('2026-10-01','2026-10-03')
    assert totals == [{'salesAmount':'30.03','orderCount':'2'},{'salesAmount':'100.00','orderCount':'1'}]
    assert str(daily[date(2026,10,1)][0]) == '10.01'
    assert daily[date(2026,10,2)][1] == 1

def test_collection_error_cannot_look_like_empty_success(tmp_path):
    out = tmp_path/'output/ai-evaluation'
    out.mkdir(parents=True)
    (out/'unit.xml').write_text('<testsuite tests="0" errors="1"><error/></testsuite>')
    summarize(tmp_path)
    rows = json.loads((out/'report.json').read_text())['results']
    assert any(r['layer']=='deterministic-no-model' and r['status']=='failed' for r in rows)

def test_independent_oracle_uses_shanghai_historical_dst(monkeypatch):
    import subprocess
    from evaluation.live import oracle
    for key,value in {'AI_LIVE_TEST_DATABASE':'mini_mfg_test','MFG_TEST_DB_URL':'jdbc:mysql://127.0.0.1:13306/mini_mfg_test?',
                      'MFG_TEST_DB_PASSWORD':'not-a-real-secret','MFG_TEST_DB_USERNAME':'mini_mfg_test','AI_EVAL_MYSQL':'mysql'}.items():
        monkeypatch.setenv(key,value)
    def run(command, **kwargs):
        assert '1990-05-29 15:00:00' in command[-1]
        assert '1990-06-02 15:00:00' in command[-1]
        return subprocess.CompletedProcess(command,0,'mini_mfg_test\n1990-05-31 15:30:00\t10.01\n','')
    monkeypatch.setattr(subprocess,'run',run)
    totals,daily=oracle('1990-06-01','1990-06-03')
    assert totals==[{'salesAmount':'10.01','orderCount':'1'},{'salesAmount':'0','orderCount':'0'}]
