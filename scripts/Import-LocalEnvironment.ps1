# Dot-source this file. It loads only this project's ignored local credentials.
param([string]$JavaHome = 'C:/Users/Yoga 7i/.jdks/jdk-17.0.20.1+1')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$secretPath = Join-Path $projectRoot '.local/secrets.json'
if (-not (Test-Path -LiteralPath $secretPath)) { throw 'Run Initialize-LocalEnvironment.ps1 first.' }
try { $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath $secretPath | ConvertFrom-Json } catch { throw 'Unable to read project local credentials as UTF-8 JSON. Details suppressed to protect secrets.' }
if ($settings.projectRoot -ne $projectRoot) { throw 'Local credentials belong to a different project directory.' }
$values = @{
    MFG_DB_URL='jdbc:mysql://127.0.0.1:13306/mini_mfg_dev?connectionTimeZone=UTC'; MFG_DB_USERNAME='mini_mfg_dev'; MFG_DB_PASSWORD=$settings.devPassword
    MFG_TEST_DB_URL='jdbc:mysql://127.0.0.1:13306/mini_mfg_test?connectionTimeZone=UTC'; MFG_TEST_DB_USERNAME='mini_mfg_test'; MFG_TEST_DB_PASSWORD=$settings.testPassword
    MFG_REDIS_HOST='127.0.0.1'; MFG_REDIS_PORT='16379'; MFG_REDIS_PASSWORD=$settings.redisDevPassword
    MFG_TEST_REDIS_HOST='127.0.0.1'; MFG_TEST_REDIS_PORT='16380'; MFG_TEST_REDIS_PASSWORD=$settings.redisTestPassword
    MFG_BOOTSTRAP_ENABLED='true'; MFG_ADMIN_PASSWORD=$settings.adminPassword
    MFG_OPERATOR_PASSWORD=$settings.operatorPassword; MFG_VIEWER_PASSWORD=$settings.viewerPassword
}
foreach ($entry in $values.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process') }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Supply the path to an installed JDK 17.' }
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome/bin;$env:Path"
# Do not print $settings, $values, or the process environment.
