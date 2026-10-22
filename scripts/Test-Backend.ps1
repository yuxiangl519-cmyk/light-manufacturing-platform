param([string]$JavaHome = 'C:/Users/Yoga 7i/.jdks/jdk-17.0.20.1+1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1') -JavaHome $JavaHome
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'backend')
try {
    & ./mvnw.cmd -B -P integration verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed; check test reports.' }
} finally { Pop-Location }
