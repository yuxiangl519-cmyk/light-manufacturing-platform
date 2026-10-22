param([string]$JavaHome = 'C:/Users/Yoga 7i/.jdks/jdk-17.0.20.1+1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1') -JavaHome $JavaHome
. (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'backend')
try {
    & ./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error.' }
} finally { Pop-Location }
