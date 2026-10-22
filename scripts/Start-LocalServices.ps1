[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Initialize-LocalEnvironment.ps1')
$localRoot = Join-Path $projectRoot '.local'
try { $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $localRoot 'secrets.json') | ConvertFrom-Json } catch { throw 'Unable to read project local credentials as UTF-8 JSON. Details suppressed to protect secrets.' }
$linuxHome = ((& wsl.exe -d Ubuntu -- printenv HOME) -join '').Replace([string][char]0,'').Trim()
if ($linuxHome -notmatch '^/home/[A-Za-z0-9_-]+$') { throw 'Unexpected WSL home directory.' }
$linuxRuntime = "$linuxHome/.local/share/light-manufacturing"
$redisExe = "$linuxRuntime/redis-7.4.2/src/redis-server"
function Assert-RedisReady([int]$Port,[string]$Password) {
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        $connection=[Net.Sockets.TcpClient]::new()
        try {
            if (-not $connection.ConnectAsync('127.0.0.1',$Port).Wait(500)) { throw 'Not connected' }
            $stream=$connection.GetStream(); $stream.ReadTimeout=1000
            foreach ($command in @("AUTH $Password",'PING','INFO server')) {
                $bytes=[Text.Encoding]::ASCII.GetBytes($command+"`r`n")
                $stream.Write($bytes,0,$bytes.Length)
                $buffer=New-Object byte[] 4096
                $length=$stream.Read($buffer,0,$buffer.Length)
                $reply=[Text.Encoding]::ASCII.GetString($buffer,0,$length)
                if ($command.StartsWith('AUTH ') -and -not $reply.StartsWith('+OK')) { throw 'Authentication failed' }
                if ($command -eq 'PING' -and -not $reply.StartsWith('+PONG')) { throw 'Not ready' }
                if ($command -eq 'INFO server' -and $reply -notmatch 'redis_version:7\.4\.2') { throw 'Unexpected version' }
            }
            return
        } catch {
            # Never print the command, server response, or authentication material.
        } finally { $connection.Dispose() }
        Start-Sleep -Milliseconds 200
    }
    throw "Dedicated Redis on port $Port did not pass authenticated readiness/version checks."
}
& wsl.exe -d Ubuntu -- test -x $redisExe
if ($LASTEXITCODE -ne 0) { throw 'Build Redis 7.4.2 from the checksum-verified source first; see docs/startup.md.' }
$instances = @(@{Name='dev'; Port=16379; Password=$settings.redisDevPassword},@{Name='test'; Port=16380; Password=$settings.redisTestPassword})
foreach ($instance in $instances) {
    $name=$instance.Name; $port=$instance.Port
    $dataPath="$linuxRuntime/$name"
    $configPath="$dataPath/redis.conf"
    $hostConfig = Join-Path $localRoot "redis-$name.conf"
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        & wsl.exe -d Ubuntu -- test -f "$dataPath/redis.pid"
        if ($LASTEXITCODE -ne 0) { throw "Redis port $port already occupied without project pid file." }
        Assert-RedisReady $port $instance.Password
        Write-Host "Project Redis $name authenticated/version-checked on $port."
        continue
    }
    @"
bind 127.0.0.1
protected-mode yes
port $port
requirepass $($instance.Password)
daemonize yes
pidfile $dataPath/redis.pid
logfile $dataPath/redis.log
dir $dataPath
appendonly yes
save ""
"@ | Set-Content -LiteralPath $hostConfig -Encoding ascii
    & wsl.exe -d Ubuntu -- mkdir -p $dataPath
    & wsl.exe -d Ubuntu -- chmod 700 $dataPath
    # wslpath emits UTF-8; PowerShell 5's OEM decoding corrupts Chinese workspace names.
    $previousEncoding=[Console]::OutputEncoding
    try {
        [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
        $wslSource = ((& wsl.exe -d Ubuntu -- wslpath -a $hostConfig) -join '').Replace([string][char]0,'').Trim()
    } finally {[Console]::OutputEncoding=$previousEncoding}
    & wsl.exe -d Ubuntu -- install -m 600 $wslSource $configPath
    if ($LASTEXITCODE -ne 0) { throw "Redis $name private config installation failed." }
    & wsl.exe -d Ubuntu -- $redisExe $configPath
    if ($LASTEXITCODE -ne 0) { throw "Redis $name failed to start." }
    Assert-RedisReady $port $instance.Password
    Write-Host "Project Redis $name started on $port (authentication required)."
}
@{distro='Ubuntu'; linuxRuntime=$linuxRuntime; devPort=16379; testPort=16380} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localRoot 'redis-runtime.json') -Encoding utf8
