# Reproduce a stale dev PID file referencing the test instance; neither may be killed.
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$localRoot=Join-Path $projectRoot '.local'
$state=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $localRoot 'redis-runtime.json')|ConvertFrom-Json
$devPidFile="$($state.linuxRuntime)/dev/redis.pid"
$testPidFile="$($state.linuxRuntime)/test/redis.pid"
$original=((& wsl.exe -d $state.distro -- cat $devPidFile) -join '').Trim()
$wrong=((& wsl.exe -d $state.distro -- cat $testPidFile) -join '').Trim()
if ($original -notmatch '^[1-9][0-9]*$' -or $wrong -notmatch '^[1-9][0-9]*$' -or $original -eq $wrong) { throw 'Requires both dedicated project instances running.' }
$originalFile=Join-Path $localRoot 'original-dev.pid'
$wrongFile=Join-Path $localRoot 'stale-dev.pid'
$original | Set-Content -LiteralPath $originalFile -Encoding ascii
$wrong | Set-Content -LiteralPath $wrongFile -Encoding ascii
$originalLinux=((& wsl.exe -d $state.distro -- wslpath -a $originalFile) -join '').Trim()
$wrongLinux=((& wsl.exe -d $state.distro -- wslpath -a $wrongFile) -join '').Trim()
$rejected=$false
try {
    & wsl.exe -d $state.distro -- install -m 600 $wrongLinux $devPidFile
    if($LASTEXITCODE -ne 0){throw 'Could not install regression fixture.'}
    try { & (Join-Path $PSScriptRoot 'Stop-LocalServices.ps1') -Target RedisDev }
    catch {
        if ($_.Exception.Message -notmatch 'does not match its expected listening port') { throw }
        $rejected=$true
    }
} finally {
    & wsl.exe -d $state.distro -- install -m 600 $originalLinux $devPidFile
    if ($LASTEXITCODE -ne 0) { throw 'Failed to restore project dev pid file.' }
}
if (-not $rejected) { throw 'Stale PID regression was not rejected.' }
foreach($serverPid in @($original,$wrong)) {
    & wsl.exe -d $state.distro -- kill -0 $serverPid
    if($LASTEXITCODE -ne 0){throw 'An instance was unexpectedly stopped.'}
}
Write-Host 'Stale dev PID targeting test Redis rejected; both original processes remain alive; PID file restored.'
