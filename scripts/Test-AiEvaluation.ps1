param([switch]$Live, [switch]$Calendar, [switch]$Dimensions)
if($Dimensions -and (!$Live -or !$Calendar)){throw '-Dimensions requires explicit -Live -Calendar and fixed-clock test app.'}
if($Calendar -and !$Live){throw '-Calendar requires explicit -Live.'}
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$python=Join-Path $root 'agent/.venv/Scripts/python.exe'
$out=Join-Path $root 'output/ai-evaluation'
New-Item -ItemType Directory -Force $out | Out-Null
# Remove only named prior evaluation artifacts; never touch test/development data.
foreach($name in @('unit.xml','live.json','report.json','report.md','TEST-io.github.lightmanufacturing.SalesAnalyticsIntegrationTest.xml','TEST-io.github.lightmanufacturing.AiIntegrationTest.xml','TEST-io.github.lightmanufacturing.SalesDimensionsIntegrationTest.xml','TEST-io.github.lightmanufacturing.AiOperationsIntegrationTest.xml','TEST-io.github.lightmanufacturing.AiEvidenceIntegrationTest.xml','TEST-io.github.lightmanufacturing.AiQuotaIntegrationTest.xml','TEST-io.github.lightmanufacturing.SalesSnapshotIntegrationTest.xml')) {
    $path=Join-Path $out $name
    if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}
}
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
if($env:MFG_TEST_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:13306/mini_mfg_test\?' -or $env:MFG_TEST_DB_USERNAME -ne 'mini_mfg_test'){throw 'Only mini_mfg_test is allowed.'}
$config=Get-Content -Raw (Join-Path $root 'backend/src/main/resources/application-test.yml')
if($config -notmatch "redis-prefix: 'light-mfg:test:'"){throw 'Test Redis prefix mismatch.'}
$priorPythonPath=$env:PYTHONPATH
$env:PYTHONPATH=Join-Path $root 'agent'
Push-Location $root
try {
    & $python -m pytest agent/tests -q "--junitxml=$out/unit.xml"
    $unitExit=$LASTEXITCODE
    Push-Location backend
    try { & ./mvnw.cmd -B -P integration '-Dit.test=SalesAnalyticsIntegrationTest,AiIntegrationTest,SalesDimensionsIntegrationTest,AiOperationsIntegrationTest,AiEvidenceIntegrationTest,AiQuotaIntegrationTest,SalesSnapshotIntegrationTest' verify *> (Join-Path $out 'backend.log'); $backendExit=$LASTEXITCODE } finally { Pop-Location }
    foreach($name in @('SalesAnalyticsIntegrationTest','AiIntegrationTest','SalesDimensionsIntegrationTest','AiOperationsIntegrationTest','AiEvidenceIntegrationTest','AiQuotaIntegrationTest','SalesSnapshotIntegrationTest')){
        $path=Join-Path $root "backend/target/failsafe-reports/TEST-io.github.lightmanufacturing.$name.xml"
        if((Test-Path -LiteralPath $path) -and (Get-Item -LiteralPath $path).LastWriteTime -ge (Get-Item -LiteralPath (Join-Path $out 'unit.xml')).LastWriteTime){Copy-Item -LiteralPath $path -Destination $out}
    }
    $liveExit=0
    if($Live){
        . (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
        $listener=Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if(!$listener){throw 'Live evaluation requires isolated backend18081 and Agent18091; see docs/ai-evaluation.md.'}
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if(!$process.CommandLine.Replace('/','\').Contains((Join-Path $root 'backend')) -or !$process.CommandLine.Contains('--spring.profiles.active=test') -or !$process.CommandLine.Contains('--mfg.ai.agent-url=http://127.0.0.1:18091')){throw 'Unverified isolated backend/Agent routing.'}
        if($Calendar){
            $agentListener=Get-NetTCPConnection -LocalPort 18091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            if(!$agentListener){throw 'Calendar test Agent18091 is not running.'}
            $agentProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($agentListener.OwningProcess)"
            if(!$agentProcess.CommandLine.Contains('evaluation.calendar_app:app')){throw 'Calendar suite requires fixed-clock evaluation.calendar_app:app.'}
            $env:AI_EVAL_CALENDAR='1'
        }
        if($Dimensions){$env:AI_EVAL_DIMENSIONS='1'}
        $env:AI_EVAL_LIVE='1'
        $env:AI_LIVE_TEST_DATABASE='mini_mfg_test'
        $env:AI_EVAL_MYSQL=Join-Path $env:LOCALAPPDATA 'LightManufacturing/stage2/mysql-8.4.7-winx64/bin/mysql.exe'
        & $python -m evaluation.live
        $liveExit=$LASTEXITCODE
    }
    $reportArgs=@('-m','evaluation.report','--root',$root)
    if($Live){$reportArgs+='--live'}
    & $python @reportArgs
    $reportExit=$LASTEXITCODE
    Get-Content -LiteralPath (Join-Path $out 'report.md')
    if($unitExit -ne 0 -or $backendExit -ne 0 -or $liveExit -ne 0 -or $reportExit -ne 0){throw 'AI evaluation failed; see sanitized report and local JUnit logs.'}
} finally {
    Pop-Location
    $env:PYTHONPATH=$priorPythonPath
    Remove-Item Env:AI_EVAL_LIVE -ErrorAction SilentlyContinue
    Remove-Item Env:AI_EVAL_CALENDAR -ErrorAction SilentlyContinue
    Remove-Item Env:AI_EVAL_DIMENSIONS -ErrorAction SilentlyContinue
}
