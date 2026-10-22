param([int]$Port=5173,[string]$BackendTarget='http://127.0.0.1:8081')
$ErrorActionPreference='Stop'
if($Port -notin @(5173,5174)){throw 'Use project frontend port 5173 or isolated test port 5174.'}
if($BackendTarget -notin @('http://127.0.0.1:8081','http://127.0.0.1:18081')){throw 'Only the project development/test backend targets are allowed.'}
if($Port -eq 5174 -and $BackendTarget -ne 'http://127.0.0.1:18081'){throw 'Test frontend must proxy isolated test backend 18081.'}
$env:VITE_API_TARGET=$BackendTarget
$env:VITE_PORT=[string]$Port
Push-Location (Join-Path (Split-Path $PSScriptRoot -Parent) 'frontend')
try{ & npm run dev; if($LASTEXITCODE -ne 0){throw 'Frontend exited with an error.'} }finally{Pop-Location}
