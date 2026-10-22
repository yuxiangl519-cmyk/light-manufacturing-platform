$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
. (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
$aiRoot=Split-Path $PSScriptRoot -Parent
$listener=Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue|Select-Object -First 1
if(!$listener){throw 'Start the isolated test backend18081 and Agent8091 first.'}
$proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
if(!$proc.CommandLine.Replace('/','\').Contains((Join-Path $aiRoot 'backend')) -or !$proc.CommandLine.Contains('--spring.profiles.active=test')){throw 'Unverified test backend; no AI writes permitted.'}
$env:AI_LIVE_TEST_DATABASE='mini_mfg_test'
Push-Location $aiRoot
try{& (Join-Path $aiRoot 'agent/.venv/Scripts/python.exe') agent/tests/live_smoke.py;if($LASTEXITCODE -ne 0){throw 'Live AI verification failed; consult sanitized log.'}}finally{Pop-Location}
