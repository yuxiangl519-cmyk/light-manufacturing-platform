# Controlled outage of this project's dev Redis only. Always attempts restart.
param([string]$BaseUrl='http://localhost:8081')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $session
$headers=@{'X-CSRF-TOKEN'=$csrf.token}
$login=@{username='admin'; password=$env:MFG_ADMIN_PASSWORD} | ConvertTo-Json -Compress
$null=Invoke-RestMethod "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $login -Headers $headers -WebSession $session
$before=Invoke-WebRequest "$BaseUrl/api/v1/auth/me" -WebSession $session -SkipHttpErrorCheck
if ($before.StatusCode -ne 200) { throw 'Prerequisite authenticated session failed.' }
try {
    & (Join-Path $PSScriptRoot 'Stop-LocalServices.ps1') -Target RedisDev
    Start-Sleep -Milliseconds 750
    $during=Invoke-WebRequest "$BaseUrl/api/v1/auth/me" -WebSession $session -SkipHttpErrorCheck
    $errorBody=$during.Content | ConvertFrom-Json
    if ($during.StatusCode -ne 503 -or $errorBody.code -ne 'DEPENDENCY_UNAVAILABLE') { throw 'Redis outage did not fail closed with safe503.' }
    Write-Host 'Authenticated request during Redis outage: 503 DEPENDENCY_UNAVAILABLE (no authentication bypass).'
} finally {
    & (Join-Path $PSScriptRoot 'Start-LocalServices.ps1')
}
$recovered=$false
for($attempt=0;$attempt -lt 30;$attempt++) {
    $response=Invoke-WebRequest "$BaseUrl/actuator/health" -SkipHttpErrorCheck
    if ($response.StatusCode -eq 200) { $recovered=$true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $recovered) { throw 'Services restarted but application has not recovered.' }
Write-Host 'Redis restored; application health:200.'
