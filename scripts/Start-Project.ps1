[CmdletBinding()]
param([switch]$Restart,[switch]$OpenBrowser)
$ErrorActionPreference='Stop'
$launcherRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Project-Process.ps1')
$launcherLock=Enter-ProjectLauncherLock $launcherRoot
try {
    if(!(Test-Path -LiteralPath (Join-Path $launcherRoot '.local/provision-state.json'))){throw 'Local environment is not provisioned. See docs/startup.md; this launcher does not create databases.'}
    if(!(Test-Path -LiteralPath (Join-Path $launcherRoot 'frontend/node_modules/vite/bin/vite.js'))){throw 'Frontend dependencies missing. Run npm ci in frontend first.'}
    if(!(Test-Path -LiteralPath (Join-Path $launcherRoot 'agent/.venv/Scripts/python.exe'))){throw 'Agent environment missing. See docs/ai-agent-mvp.md.'}
    foreach($definition in @(Get-ProjectDefinitions)) {
        foreach($processId in @(Get-ProjectListener $definition.Port)){$null=Assert-ProjectProcess $definition $processId $launcherRoot}
    }
    if($Restart){Stop-ProjectApplications $launcherRoot}
    Write-Host '[1/4] Checking MySQL and Redis (existing project services are reused)...'
    $logDirectory=Join-Path $launcherRoot '.local/logs'
    $shellExe=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $dependencyStamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $dependencyLog=Join-Path $logDirectory "launcher-dependencies-$dependencyStamp.out.log"
    $dependencyError=Join-Path $logDirectory "launcher-dependencies-$dependencyStamp.err.log"
    $dependencyScript=Join-Path $PSScriptRoot 'Start-LocalServices.ps1'
    $oldModulePath=$env:PSModulePath
    try {
        $env:PSModulePath=[Environment]::GetEnvironmentVariable('PSModulePath','Machine')
        $dependencies=Start-Process -FilePath $shellExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$dependencyScript+'"')) -WorkingDirectory $launcherRoot -WindowStyle Hidden -RedirectStandardOutput $dependencyLog -RedirectStandardError $dependencyError -PassThru
    } finally {$env:PSModulePath=$oldModulePath}
    $null=$dependencies.Handle
    # -Wait waits for descendants too, including the database we intentionally keep running.
    $dependencyDeadline=[DateTime]::UtcNow.AddSeconds(120)
    while(!$dependencies.WaitForExit(500)) {
        if([DateTime]::UtcNow -gt $dependencyDeadline){throw "Dependency startup timed out; inspect $dependencyLog and $dependencyError."}
    }
    if($dependencies.ExitCode -ne 0){throw "MySQL/Redis readiness or login check failed. See $dependencyError. No application was newly started."}
    Write-Host '  READY: project MySQL and Redis'
    $index=1
    foreach($definition in @(Get-ProjectDefinitions)) {
        $index++
        $listeners=@(Get-ProjectListener $definition.Port)
        if($listeners.Count -gt 0) {
            Write-Host "[$index/4] Reusing project $($definition.Name) on $($definition.Port)..."
        } else {
            $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
            $out=Join-Path $logDirectory ("launcher-"+$definition.Name.ToLower()+"-$stamp.out.log")
            $err=Join-Path $logDirectory ("launcher-"+$definition.Name.ToLower()+"-$stamp.err.log")
            $oldPort=$env:MFG_PORT;$oldAgent=$env:MFG_AI_AGENT_URL;$oldErp=$env:AI_ERP_BASE_URL;$oldModules=$env:PSModulePath
            try {
                $env:MFG_PORT='8081';$env:MFG_AI_AGENT_URL='http://127.0.0.1:8091';$env:AI_ERP_BASE_URL='http://127.0.0.1:8081'
                $env:PSModulePath=[Environment]::GetEnvironmentVariable('PSModulePath','Machine')
                $scriptFile=Join-Path $PSScriptRoot $definition.Script
                $worker=Start-Process -FilePath $shellExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$scriptFile+'"')) -WorkingDirectory $launcherRoot -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
            } finally {$env:MFG_PORT=$oldPort;$env:MFG_AI_AGENT_URL=$oldAgent;$env:AI_ERP_BASE_URL=$oldErp;$env:PSModulePath=$oldModules}
            Write-Host "[$index/4] Starting $($definition.Name); logs: $out"
        }
        $deadline=[DateTime]::UtcNow.AddSeconds(180)
        while(!(Test-ProjectHealth $definition)) {
            if($listeners.Count -eq 0 -and $worker.HasExited){throw "$($definition.Name) exited before ready. Check $out and $err. Existing services and data were retained."}
            if([DateTime]::UtcNow -gt $deadline){throw "$($definition.Name) did not become healthy on port $($definition.Port). Check .local/logs; existing services were retained."}
            Start-Sleep -Milliseconds 700
        }
        foreach($processId in @(Get-ProjectListener $definition.Port)){$null=Assert-ProjectProcess $definition $processId $launcherRoot}
        Write-Host "  READY: $($definition.Name)"
    }
    Write-Host ''
    Write-Host 'All services are ready: http://localhost:5173' -ForegroundColor Green
    Write-Host 'Services run in background. Stop: .\scripts\Stop-Project.ps1'
    Write-Host 'Restart applications: .\scripts\Start-Project.ps1 -Restart'
    if($OpenBrowser){Start-Process 'http://localhost:5173'}
} finally {$launcherLock.Dispose()}
