param([switch]$Live)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
. (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
if(!(Get-Content -Raw (Join-Path $root 'backend/src/main/resources/application-test.yml')).Contains("redis-prefix: 'light-mfg:test:'")){throw 'Test Redis prefix mismatch.'}
if($env:MFG_TEST_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:13306/mini_mfg_test\?' -or $env:MFG_TEST_REDIS_PORT -ne '16380'){throw 'Only isolated test resources allowed.'}
if(Get-NetTCPConnection -LocalPort 18091 -State Listen -ErrorAction SilentlyContinue){throw 'Acceptance port 18091 is occupied.'}
$env:AI_ERP_BASE_URL='http://127.0.0.1:18081'
$env:AI_ACCEPTANCE='1'
$env:AI_LIVE_TEST_DATABASE='mini_mfg_test'
$env:AI_EVAL_LIVE=if($Live){'1'}else{'0'}
& (Join-Path $root 'agent/.venv/Scripts/python.exe') -m uvicorn evaluation.acceptance_app:app --app-dir (Join-Path $root 'agent') --host 127.0.0.1 --port 18091 --no-access-log
if($LASTEXITCODE -ne 0){throw 'Acceptance Agent exited with an error.'}
