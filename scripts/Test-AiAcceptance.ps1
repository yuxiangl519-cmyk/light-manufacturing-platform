param([switch]$Live)
$ErrorActionPreference='Stop'
if(!$Live){throw 'The production graph is model-driven for every question. Browser acceptance now requires explicit -Live; use Test-AiEvaluation.ps1 for no-model tests.'}
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
if(!(Get-Content -Raw (Join-Path $root 'backend/src/main/resources/application-test.yml')).Contains("redis-prefix: 'light-mfg:test:'")){throw 'Test Redis prefix mismatch.'}
if($env:MFG_TEST_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:13306/mini_mfg_test\?' -or $env:MFG_TEST_REDIS_PORT -ne '16380'){throw 'Only isolated test resources allowed.'}
foreach($port in @(18081,18091)){
 $listener=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue|Select-Object -First 1
 if(!$listener){throw "Start isolated service on $port first."}
 $info=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
 if(!$info.CommandLine.Replace('/','\').Contains($root)){throw 'Unverified project process.'}
 if($port -eq 18081 -and (!$info.CommandLine.Contains('--spring.profiles.active=test') -or !$info.CommandLine.Contains('--mfg.ai.agent-url=http://127.0.0.1:18091'))){throw 'Unverified isolated Java routing.'}
 if($port -eq 18091 -and !$info.CommandLine.Contains('evaluation.acceptance_app:app')){throw 'Acceptance needs guarded evaluation.acceptance_app.'}
}
$health=Invoke-RestMethod http://127.0.0.1:18091/health
if([bool]$health.modelConfigured -ne [bool]$Live){throw 'Live flag and Agent configuration do not match; restart guarded Agent with matching switch.'}
$oldPath=$env:PYTHONPATH;$oldGuard=$env:AI_LIVE_TEST_DATABASE;$oldLive=$env:AI_EVAL_LIVE;$oldOracle=$env:AI_EVAL_MYSQL
Push-Location $root
try{
 $env:PYTHONPATH=Join-Path $root 'agent';$env:AI_LIVE_TEST_DATABASE='mini_mfg_test';$env:AI_EVAL_LIVE=if($Live){'1'}else{'0'}
 $env:AI_EVAL_MYSQL=Join-Path $env:LOCALAPPDATA 'LightManufacturing/stage2/mysql-8.4.7-winx64/bin/mysql.exe'
 & (Join-Path $root 'agent/.venv/Scripts/python.exe') -m evaluation.acceptance_fixtures
 if($LASTEXITCODE -ne 0){throw 'Independent oracle preparation failed.'}
 $env:MFG_E2E_DATABASE='mini_mfg_test';$env:MFG_E2E_BASE_URL='http://localhost:5174'
 Push-Location frontend
 try{& node scripts/e2e-ai-acceptance.mjs;if($LASTEXITCODE -ne 0){throw 'Browser acceptance failed; see sanitized report.'}}finally{Pop-Location}
}finally{Pop-Location;$env:PYTHONPATH=$oldPath;$env:AI_LIVE_TEST_DATABASE=$oldGuard;$env:AI_EVAL_LIVE=$oldLive;$env:AI_EVAL_MYSQL=$oldOracle}
