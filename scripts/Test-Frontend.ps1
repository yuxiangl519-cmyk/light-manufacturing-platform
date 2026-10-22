param([switch]$Administration,[switch]$Ai,[switch]$AiContext)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
$projectRoot=Split-Path $PSScriptRoot -Parent
$listener=Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue|Select-Object -First 1
if(!$listener){throw 'Start scripts/Start-E2eBackend.ps1 and isolated frontend 5174 first.'}
$processInfo=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
if(!$processInfo.CommandLine.Replace('/','\').Contains((Join-Path $projectRoot 'backend')) -or !$processInfo.CommandLine.Contains('--spring.profiles.active=test')){throw 'Port 18081 is not the verified project test backend; no browser mutations allowed.'}
$env:MFG_E2E_DATABASE='mini_mfg_test'
$env:MFG_E2E_BASE_URL='http://localhost:5174'
Push-Location (Join-Path $projectRoot 'frontend')
try{ if($AiContext){ & node scripts/e2e-ai-context.mjs }elseif($Ai){ & node scripts/e2e-ai.mjs }elseif($Administration){ & node scripts/e2e-admin.mjs }else{ & npm run test:e2e }; if($LASTEXITCODE -ne 0){throw 'Frontend browser verification failed; inspect sanitized output and screenshots.'} }finally{Pop-Location}
