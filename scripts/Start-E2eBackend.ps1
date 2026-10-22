$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
. (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
if($env:MFG_TEST_DB_URL -notmatch '/mini_mfg_test\?'){throw 'E2E requires the isolated test database.'}
$env:MFG_PORT='18081'
if(Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue){throw 'Test backend port 18081 is occupied; no existing process will be stopped.'}
$projectRoot=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $projectRoot 'backend/target/light-manufacturing-server-0.1.0.jar'
if(!(Test-Path -LiteralPath $jar)){throw 'Build the backend with integration verify first.'}
& "$env:JAVA_HOME/bin/java.exe" -jar $jar --spring.profiles.active=test --server.address=127.0.0.1
if($LASTEXITCODE -ne 0){throw 'Isolated E2E backend exited with an error.'}
