# Creates only this project's isolated instances; never modifies existing services.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localRoot = Join-Path $projectRoot '.local'
$runtimeRoot = Join-Path $env:LOCALAPPDATA 'LightManufacturing/stage2'
$ownerPath = Join-Path $runtimeRoot 'owner.json'
$statePath = Join-Path $localRoot 'provision-state.json'
$secretPath = Join-Path $localRoot 'secrets.json'
$mysqlArchive = Join-Path $localRoot 'downloads/mysql-8.4.7-winx64.zip'
$mysqlRoot = Join-Path $runtimeRoot 'mysql-8.4.7-winx64'
$mysqlData = Join-Path $runtimeRoot 'mysql-data'
$mysqlConfig = Join-Path $runtimeRoot 'my.ini'
$mysqlExe = Join-Path $mysqlRoot 'bin/mysqld.exe'
$clientExe = Join-Path $mysqlRoot 'bin/mysql.exe'

function Protect-Directory([string]$Directory) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $Directory
    $rules = @($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if ($acl.AreAccessRulesProtected -and $rules.Count -eq 1 -and $rules[0].IdentityReference -eq $sid -and $rules[0].FileSystemRights -eq [Security.AccessControl.FileSystemRights]::FullControl) { return }
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($existingRule in @($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]))) { $acl.RemoveAccessRuleSpecific($existingRule) }
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Directory -AclObject $acl
}
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)) }
function Assert-FreePort([int]$Port) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is occupied; refusing to replace another instance." }
}
function Test-MySqlLogin([string[]]$Arguments) {
    # Native stderr during a readiness retry must not terminate Windows PowerShell 5.
    $oldPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try {& $clientExe @Arguments 2>$null | Out-Null;return $LASTEXITCODE -eq 0}
    finally {$ErrorActionPreference=$oldPreference}
}
New-Item -ItemType Directory -Force $localRoot | Out-Null
Protect-Directory $localRoot
New-Item -ItemType Directory -Force (Join-Path $localRoot 'logs') | Out-Null
if (Test-Path -LiteralPath $runtimeRoot) {
    if (-not (Test-Path -LiteralPath $ownerPath)) { throw 'Runtime directory already exists without a project ownership marker.' }
    $owner = Get-Content -Raw -Encoding UTF8 -LiteralPath $ownerPath | ConvertFrom-Json
    if ($owner.projectRoot -ne $projectRoot) { throw 'Runtime belongs to a different project. Nothing was overwritten.' }
} else {
    New-Item -ItemType Directory $runtimeRoot | Out-Null
    Protect-Directory $runtimeRoot
    @{projectRoot=$projectRoot; projectId='light-manufacturing-stage2'; createdAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $ownerPath -Encoding utf8
}
if (-not (Test-Path -LiteralPath $secretPath)) {
    @{projectRoot=$projectRoot; rootPassword=(New-Secret); devPassword=(New-Secret); testPassword=(New-Secret); redisDevPassword=(New-Secret); redisTestPassword=(New-Secret); adminPassword=(New-Secret); operatorPassword=(New-Secret); viewerPassword=(New-Secret)} | ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding utf8
}
try { $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath $secretPath | ConvertFrom-Json } catch { throw 'Unable to read project local credentials as UTF-8 JSON. Details suppressed to protect secrets.' }
if ($settings.projectRoot -ne $projectRoot) { throw 'Credential ownership mismatch.' }
if (-not (Test-Path -LiteralPath $mysqlExe)) {
    if (-not (Test-Path -LiteralPath $mysqlArchive)) { throw 'Download the fixed official MySQL archive first (see startup documentation).' }
    $expectedArchiveHash = 'fd9bdbd4b5a878d31c8e4067078bd60665b1b3c4677fa1f099416d194b458aff'
    if ((Get-FileHash -LiteralPath $mysqlArchive -Algorithm SHA256).Hash -ne $expectedArchiveHash) { throw 'MySQL archive checksum mismatch. No binary was executed.' }
    Expand-Archive -LiteralPath $mysqlArchive -DestinationPath $runtimeRoot
}
$normalizedRoot = $mysqlRoot.Replace('\','/')
$normalizedData = $mysqlData.Replace('\','/')
if (-not (Test-Path -LiteralPath $mysqlConfig)) {
    @"
[mysqld]
basedir="$normalizedRoot"
datadir="$normalizedData"
bind-address=127.0.0.1
port=13306
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
default-time-zone=+00:00
log-error="$($runtimeRoot.Replace('\','/'))/mysql-error.log"
pid-file="$($runtimeRoot.Replace('\','/'))/mysql.pid"
"@ | Set-Content -LiteralPath $mysqlConfig -Encoding ascii
}
. (Join-Path $PSScriptRoot 'MySql-Startup.ps1')
$listener = Get-NetTCPConnection -LocalPort 13306 -State Listen -ErrorAction SilentlyContinue
if($listener) {
    foreach($processId in @($listener | Select-Object -ExpandProperty OwningProcess -Unique)) {
        $record=Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction Stop
        $expectedExe=[IO.Path]::GetFullPath($mysqlExe)
        $actualExe=[string]$record.ExecutablePath
        $command=([string]$record.CommandLine).Replace('\\','\').Replace('/','\')
        if($actualExe -ne $expectedExe -or !$command.Contains($mysqlConfig.Replace('/','\'))) {
            throw 'Port 13306 belongs to an unverified MySQL instance; nothing was replaced.'
        }
    }
    $action=Get-MySqlStartupAction $true $false $false (Test-Path -LiteralPath $statePath)
} else {
    $initialized=(Test-Path -LiteralPath (Join-Path $mysqlData 'auto.cnf') -PathType Leaf -ErrorAction Stop) -and (Test-Path -LiteralPath (Join-Path $mysqlData 'mysql.ibd') -PathType Leaf -ErrorAction Stop)
    $hasData=(Test-Path -LiteralPath $mysqlData -ErrorAction Stop) -and (@(Get-ChildItem -LiteralPath $mysqlData -Force -ErrorAction Stop | Select-Object -First 1).Count -gt 0)
    $action=Get-MySqlStartupAction $false $initialized $hasData (Test-Path -LiteralPath $statePath)
}
if ($action -eq 'Initialize') {
    Assert-FreePort 13306
    if ((Test-Path -LiteralPath $mysqlData) -and (Get-ChildItem -LiteralPath $mysqlData -Force | Select-Object -First 1)) { throw 'Refusing to initialize a nonempty data directory.' }
    & $mysqlExe "--defaults-file=$mysqlConfig" --initialize-insecure
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed; see private mysql-error.log.' }
}
if ($action -ne 'Reuse') {
    $arguments = @("`"--defaults-file=$mysqlConfig`"")
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeRoot 'root-auth.ready'))) {
        # MySQL's init-file parser on Windows cannot handle this project's Chinese path.
        # Use the private, ASCII-named runtime directory instead of the workspace.
        $initFile = Join-Path $runtimeRoot 'root-init.sql'
        "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($settings.rootPassword)';" | Set-Content -LiteralPath $initFile -Encoding ascii
        $arguments += "`"--init-file=$initFile`""
    }
    Start-Process -FilePath $mysqlExe -ArgumentList $arguments -WindowStyle Hidden | Out-Null
}
$clientConfig = Join-Path $runtimeRoot 'mysql-admin.cnf'
@"
[client]
host=127.0.0.1
port=13306
user=root
password=$($settings.rootPassword)
protocol=TCP
"@ | Set-Content -LiteralPath $clientConfig -Encoding ascii
$ready = $false
for ($attempt=0; $attempt -lt 40; $attempt++) {
    if (Test-MySqlLogin @("--defaults-file=$clientConfig",'--batch','--skip-column-names','-e','SELECT VERSION();')) { $ready=$true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'Dedicated MySQL failed readiness/authentication check.' }
Set-Content -LiteralPath (Join-Path $runtimeRoot 'root-auth.ready') -Value 'initialized' -Encoding ascii
foreach ($candidate in @((Join-Path $runtimeRoot 'root-init.sql'),(Join-Path $localRoot 'root-init.sql'))) {
    if (Test-Path -LiteralPath $candidate) { Remove-Item -LiteralPath $candidate }
}
$existing = & $clientExe "--defaults-file=$clientConfig" --batch --skip-column-names -e "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('mini_mfg_dev','mini_mfg_test');"
if ($existing -and -not (Test-Path -LiteralPath $statePath)) { throw 'Same-name database exists without a completed project provision marker. Refusing to overwrite.' }
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json
    if ($state.projectRoot -ne $projectRoot -or $state.runtimeRoot -ne $runtimeRoot) { throw 'Provision ownership mismatch.' }
    $databaseNames=@($existing | ForEach-Object { ([string]$_).Trim() })
    if ('mini_mfg_dev' -notin $databaseNames -or 'mini_mfg_test' -notin $databaseNames) {
        throw 'Project provision marker exists but a project database is missing. No database was recreated. Restore an approved project backup; see docs/startup.md.'
    }
} else {
    $sqlFile = Join-Path $localRoot 'provision.sql'
    @"
CREATE DATABASE mini_mfg_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE mini_mfg_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'mini_mfg_dev'@'localhost' IDENTIFIED BY '$($settings.devPassword)';
CREATE USER 'mini_mfg_test'@'localhost' IDENTIFIED BY '$($settings.testPassword)';
GRANT ALL PRIVILEGES ON mini_mfg_dev.* TO 'mini_mfg_dev'@'localhost';
GRANT ALL PRIVILEGES ON mini_mfg_test.* TO 'mini_mfg_test'@'localhost';
"@ | Set-Content -LiteralPath $sqlFile -Encoding ascii
    $proc = Start-Process -FilePath $clientExe -ArgumentList "`"--defaults-file=$clientConfig`"" -RedirectStandardInput $sqlFile -RedirectStandardOutput (Join-Path $localRoot 'logs/provision.out') -RedirectStandardError (Join-Path $localRoot 'logs/provision.err') -NoNewWindow -Wait -PassThru
    if ($proc.ExitCode -ne 0) { throw 'Provision failed; private log available. No existing databases were cleared.' }
    Remove-Item -LiteralPath $sqlFile
    @{projectRoot=$projectRoot; runtimeRoot=$runtimeRoot; databases=@('mini_mfg_dev','mini_mfg_test'); createdAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
}
# A root login and an old marker do not establish that application accounts still work.
$oldMysqlPassword=$env:MYSQL_PWD
try {
    foreach($account in @(@{Name='mini_mfg_dev';Password=$settings.devPassword},@{Name='mini_mfg_test';Password=$settings.testPassword})) {
        $env:MYSQL_PWD=$account.Password
        if(!(Test-MySqlLogin @('--host=127.0.0.1','--port=13306','--protocol=TCP',"--user=$($account.Name)",'--batch','--skip-column-names','-e','SELECT 1;',$account.Name))){throw "Project database login failed for $($account.Name). No password was changed; verify local credentials or restore an approved backup."}
    }
} finally {$env:MYSQL_PWD=$oldMysqlPassword}
Write-Host 'Dedicated MySQL 8.4.7 ready on 13306; project dev/test databases verified. No credentials printed.'
Write-Host 'Redis services are prepared separately by scripts/Start-LocalServices.ps1.'
