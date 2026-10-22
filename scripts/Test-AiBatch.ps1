param([ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label='operations-batch')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
. (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
if($env:MFG_TEST_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:13306/mini_mfg_test\?' -or $env:MFG_TEST_DB_USERNAME -ne 'mini_mfg_test'){throw 'Only isolated mini_mfg_test is allowed.'}
foreach($port in @(18081,8092)){
    $listener=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if(!$listener){throw "Isolated service on $port is required; see docs/ai-batch-testing.md."}
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $command=$process.CommandLine.Replace('/','\')
    if($port -eq 18081){
        if(!$command.Contains((Join-Path $root 'backend')) -or !$command.Contains('--spring.profiles.active=test')){throw 'Unverified test backend.'}
    }else{
        if(!$command.Contains((Join-Path $root 'agent')) -or !$command.Contains('uvicorn app.main:app') -or !$command.Contains('--port 8092')){throw 'Unverified test agent.'}
    }
}
$oldPythonPath=$env:PYTHONPATH
$oldTestGuard=$env:AI_LIVE_TEST_DATABASE
$oldOracle=$env:AI_EVAL_MYSQL
Push-Location $root
try{
    $env:PYTHONPATH=Join-Path $root 'agent'
    $env:AI_LIVE_TEST_DATABASE='mini_mfg_test'
    $env:AI_EVAL_MYSQL=Join-Path $env:LOCALAPPDATA 'LightManufacturing/stage2/mysql-8.4.7-winx64/bin/mysql.exe'
    & (Join-Path $root 'agent/.venv/Scripts/python.exe') -m evaluation.operations_batch $Label
    if($LASTEXITCODE -ne 0){throw 'AI batch verification failed; inspect the ignored output/ai-evaluation report.'}
}finally{
    Pop-Location
    $env:PYTHONPATH=$oldPythonPath
    $env:AI_LIVE_TEST_DATABASE=$oldTestGuard
    $env:AI_EVAL_MYSQL=$oldOracle
}
