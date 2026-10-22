[CmdletBinding()]
param([ValidateSet('Test','Development')][string]$Database='Test',[switch]$Preview)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if($Preview){ Get-Content -LiteralPath (Join-Path $projectRoot 'docs/demo-data/orders.csv') -Encoding UTF8; return }
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
$databaseName=if($Database -eq 'Test'){'mini_mfg_test'}else{'mini_mfg_dev'}
$profile=if($Database -eq 'Test'){'test'}else{'local'}
$runtimeRoot=Join-Path $env:LOCALAPPDATA 'LightManufacturing/stage2'
$owner=Get-Content -LiteralPath (Join-Path $runtimeRoot 'owner.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if($owner.projectRoot -ne $projectRoot){throw 'Runtime owner does not match this project.'}
$mysqlBin=Join-Path $runtimeRoot 'mysql-8.4.7-winx64/bin'
$backupRoot=Join-Path $projectRoot '.local/backups'
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$backup=Join-Path $backupRoot ($databaseName+'-before-demo-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.sql')
$oldPwd=$env:MYSQL_PWD
try {
    $env:MYSQL_PWD=if($Database -eq 'Test'){$env:MFG_TEST_DB_PASSWORD}else{$env:MFG_DB_PASSWORD}
    & (Join-Path $mysqlBin 'mysqldump.exe') --host=127.0.0.1 --port=13306 --protocol=TCP "--user=$databaseName" --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --default-character-set=utf8mb4 "--result-file=$backup" --databases $databaseName 2> (Join-Path $projectRoot '.local/logs/demo-backup-error.log')
    if($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $backup).Length -lt 1000){throw 'Database backup failed; no import attempted. Details remain in ignored local logs.'}
    if(-not ((Get-Content -LiteralPath $backup -Tail 3 -Encoding UTF8) -match 'Dump completed on')){throw 'Backup completion marker missing; no import attempted.'}
} finally { $env:MYSQL_PWD=$oldPwd }
$oldImport=$env:MFG_DEMO_IMPORT; $oldDatabase=$env:MFG_DEMO_DATABASE; $oldBackup=$env:MFG_DEMO_BACKUP
try {
    $env:MFG_DEMO_IMPORT='true'; $env:MFG_DEMO_DATABASE=$databaseName; $env:MFG_DEMO_BACKUP=$backup
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & ./mvnw.cmd -B '-Dtest=HistoricalDemoImport' '-Dmfg.demo.import=true' "-Dspring.profiles.active=$profile" test
        if($LASTEXITCODE -ne 0){throw 'Demo import failed. Completed document transactions remain resumable; no automatic restore/delete is performed.'}
    } finally { Pop-Location }
    Write-Host "Verified demo import: $databaseName. Backup: $backup"
} finally {
    $env:MFG_DEMO_IMPORT=$oldImport; $env:MFG_DEMO_DATABASE=$oldDatabase; $env:MFG_DEMO_BACKUP=$oldBackup
}
