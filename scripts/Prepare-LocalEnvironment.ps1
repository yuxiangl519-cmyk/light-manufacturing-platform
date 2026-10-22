# One-time setup of this project's isolated MySQL/Redis; no existing service changes.
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$downloads=Join-Path $projectRoot '.local/downloads'
New-Item -ItemType Directory -Force $downloads | Out-Null
function Download-Verified([string]$Url,[string]$Name,[string]$ExpectedSha256) {
    $destination=Join-Path $downloads $Name
    if (-not (Test-Path -LiteralPath $destination)) { Invoke-WebRequest -Uri $Url -OutFile $destination -TimeoutSec 600 }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $ExpectedSha256) { throw "Checksum mismatch for $Name. Stop without executing downloaded content." }
}
Download-Verified 'https://cdn.mysql.com/archives/mysql-8.4/mysql-8.4.7-winx64.zip' 'mysql-8.4.7-winx64.zip' 'fd9bdbd4b5a878d31c8e4067078bd60665b1b3c4677fa1f099416d194b458aff'
Download-Verified 'https://download.redis.io/releases/redis-7.4.2.tar.gz' 'redis-7.4.2.tar.gz' '4ddebbf09061cbb589011786febdb34f29767dd7f89dbe712d2b68e808af6a1f'
& wsl.exe -d Ubuntu -- sh -c 'command -v gcc >/dev/null && command -v make >/dev/null'
if ($LASTEXITCODE -ne 0) { throw 'WSL Ubuntu needs gcc, make and libc6-dev. Install those build tools first; existing Redis services need no changes.' }
$sourcePath=Join-Path $downloads 'redis-7.4.2.tar.gz'
$linuxSource=((& wsl.exe -d Ubuntu -- wslpath -a $sourcePath) -join '').Trim()
$linuxHome=((& wsl.exe -d Ubuntu -- printenv HOME) -join '').Trim()
if ($linuxHome -notmatch '^/home/[A-Za-z0-9_-]+$') { throw 'Unexpected WSL home.' }
$runtime="$linuxHome/.local/share/light-manufacturing"
& wsl.exe -d Ubuntu -- mkdir -p $runtime
& wsl.exe -d Ubuntu -- test -x "$runtime/redis-7.4.2/src/redis-server"
if ($LASTEXITCODE -ne 0) {
    & wsl.exe -d Ubuntu -- tar -xzf $linuxSource -C $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Redis source extraction failed.' }
    & wsl.exe -d Ubuntu -- make -C "$runtime/redis-7.4.2" -j2 MALLOC=libc BUILD_TLS=no
    if ($LASTEXITCODE -ne 0) { throw 'Redis build failed.' }
}
& (Join-Path $PSScriptRoot 'Start-LocalServices.ps1')
