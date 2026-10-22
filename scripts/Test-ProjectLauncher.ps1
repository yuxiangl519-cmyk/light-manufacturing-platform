# Dependency-free regression checks; no services are stopped and no credentials are read.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Project-Process.ps1')
$workspace='C:\Demo Factory'
function Assert-True([bool]$Value,[string]$Message){if(!$Value){throw $Message}}
Assert-True (Test-ProjectCommand 'Frontend' 'node.exe' 'node "C:\Demo Factory\frontend\node_modules\vite\bin\vite.js"' $workspace) 'Own frontend should match'
Assert-True (!(Test-ProjectCommand 'Frontend' 'node.exe' 'node C:\Other\frontend\node_modules\vite\bin\vite.js' $workspace)) 'Other frontend must be rejected'
Assert-True (Test-ProjectCommand 'Agent' 'python.exe' 'python -m uvicorn app.main:app --app-dir "C:\Demo Factory\agent" --port 8091' $workspace) 'Own agent should match'
Assert-True (!(Test-ProjectCommand 'Agent' 'python.exe' 'python -m uvicorn app.main:app --app-dir C:\Other\agent' $workspace)) 'Other agent must be rejected'
Assert-True (Test-ProjectCommand 'Backend' 'java.exe' 'java -cp "C:\Demo Factory\backend\target\classes" io.github.lightmanufacturing.Application' $workspace) 'Own backend should match'
Assert-True (!(Test-ProjectCommand 'Backend' 'java.exe' 'java -cp C:\Other\backend\target\classes io.github.lightmanufacturing.Application' $workspace)) 'Other backend must be rejected'
Assert-True (!(Test-ProjectCommand 'Backend' 'java.exe' 'java -jar C:\Demo Factory\backend\unrelated.jar' $workspace)) 'Unrecognized Java main must be rejected'
Write-Host 'Launcher ownership regression: 7 checks passed.'
function Invoke-WebRequest {return @{StatusCode=200;Content=[Text.Encoding]::UTF8.GetBytes('{"status":"UP"}')}}
Assert-True (Test-ProjectHealth (@(Get-ProjectDefinitions)[0])) 'PowerShell 5 binary JSON health response must decode correctly'
Remove-Item Function:\Invoke-WebRequest
Write-Host 'Launcher health regression: binary JSON response passed.'
$fixtureRoot=Join-Path (Split-Path $PSScriptRoot -Parent) '.local/launcher-lock-test'
$held=Enter-ProjectLauncherLock $fixtureRoot
$rejected=$false
try {
    try {$unexpected=Enter-ProjectLauncherLock $fixtureRoot;$unexpected.Dispose()}
    catch {if($_.Exception.Message -like '*Another project start/stop command*'){$rejected=$true}else{throw}}
} finally {$held.Dispose()}
Assert-True $rejected 'A concurrent launcher must be rejected'
Write-Host 'Launcher concurrency regression: second lock rejected.'
