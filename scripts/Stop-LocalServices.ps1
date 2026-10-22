[CmdletBinding()]
param([ValidateSet('All','RedisDev','RedisTest')][string]$Target='All')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$localRoot=Join-Path $projectRoot '.local'
$state=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $localRoot 'provision-state.json') | ConvertFrom-Json
if ($state.projectRoot -ne $projectRoot) { throw 'Project instance ownership mismatch.' }
$redisState=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $localRoot 'redis-runtime.json') | ConvertFrom-Json
function Invoke-StopWsl([string[]]$Arguments) {
    # WSL proxy warnings on stderr are not command failures (notably in PowerShell 5).
    $oldPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try {$result=@(& wsl.exe @Arguments 2>$null)}
    finally {$ErrorActionPreference=$oldPreference}
    return $result
}
foreach ($name in @('dev','test')) {
    if ($Target -ne 'All' -and $Target -ne ('Redis'+(Get-Culture).TextInfo.ToTitleCase($name))) { continue }
    $port=if($name -eq 'dev'){16379}else{16380}
    $listener=((Invoke-StopWsl @('-d',$redisState.distro,'--','ss','-H','-ltnp',"sport = :$port")) -join "`n").Trim()
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect WSL listeners; no stop signal sent.'}
    if ([string]::IsNullOrWhiteSpace($listener)) {
        Write-Host "Project Redis $name is already stopped; no signal sent."
        continue
    }
    $pidFile="$($redisState.linuxRuntime)/$name/redis.pid"
    $serverPid=((Invoke-StopWsl @('-d',$redisState.distro,'--','cat',$pidFile)) -join '').Trim()
    if ($serverPid -notmatch '^[1-9][0-9]*$') { throw "Cannot identify dedicated Redis $name process." }
    if ($listener -notmatch "127\.0\.0\.1:$port\s" -or $listener -notmatch "pid=$serverPid,") {
        throw "Redis $name PID file does not match its expected listening port; refusing to stop any process."
    }
    $executable=((Invoke-StopWsl @('-d',$redisState.distro,'--','readlink',"/proc/$serverPid/exe")) -join '').Trim()
    if ($executable -ne "$($redisState.linuxRuntime)/redis-7.4.2/src/redis-server") { throw 'PID belongs to another executable; refusing to stop.' }
    Invoke-StopWsl @('-d',$redisState.distro,'--','kill','-TERM',$serverPid)
    if ($LASTEXITCODE -ne 0) { throw "Could not stop dedicated Redis $name." }
    $stopped=$false
    for($attempt=0;$attempt -lt 30;$attempt++) {
        Invoke-StopWsl @('-d',$redisState.distro,'--','test','-e',"/proc/$serverPid/exe")
        $exited=$LASTEXITCODE -ne 0
        if ($exited -and -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) { $stopped=$true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $stopped) { throw "Redis $name shutdown did not finish; do not race a restart." }
    Write-Host "Stopped project Redis $name; data/config retained."
}
if ($Target -eq 'All') {
    $listener=Get-NetTCPConnection -LocalPort 13306 -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        $exe=Join-Path $state.runtimeRoot 'mysql-8.4.7-winx64/bin/mysqld.exe'
        $process=Get-Process -Id ($listener | Select-Object -First 1).OwningProcess
        if ($process.Path -ne $exe) { throw 'Port 13306 is not the project MySQL; refusing to stop.' }
        $admin=Join-Path $state.runtimeRoot 'mysql-8.4.7-winx64/bin/mysqladmin.exe'
        $config=Join-Path $state.runtimeRoot 'mysql-admin.cnf'
        & $admin "--defaults-file=$config" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'Project MySQL shutdown failed.' }
        $deadline=[DateTime]::UtcNow.AddSeconds(15)
        while(Get-NetTCPConnection -LocalPort 13306 -State Listen -ErrorAction SilentlyContinue) {
            if([DateTime]::UtcNow -gt $deadline){throw 'Project MySQL shutdown has not finished; restart cancelled.'}
            Start-Sleep -Milliseconds 300
        }
        Write-Host 'Stopped project MySQL; databases retained.'
    }
}
