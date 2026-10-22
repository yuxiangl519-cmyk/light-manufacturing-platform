# Shared local launcher helpers; Windows PowerShell 5.1 and PowerShell 7.
function ConvertTo-ProjectPath([string]$Value) {return $Value.Replace('\\','\').Replace('/','\').ToLowerInvariant()}
function Test-ProjectCommand([string]$Service,[string]$Name,[string]$Command,[string]$Root) {
    if([string]::IsNullOrWhiteSpace($Command)){return $false}
    $text=ConvertTo-ProjectPath $Command
    $base=(ConvertTo-ProjectPath $Root).TrimEnd('\')
    switch($Service){
        'Frontend' {return $Name -eq 'node.exe' -and $text.Contains($base+'\frontend\node_modules\') -and $text.Contains('vite\bin\vite.js')}
        'Agent' {return $Name -in @('python.exe','pythonw.exe') -and $text.Contains('uvicorn app.main:app') -and $text.Contains($base+'\agent"')}
        'Backend' {return $Name -eq 'java.exe' -and ($text.Contains('io.github.lightmanufacturing.application') -or $text.Contains('light-manufacturing-server-0.1.0.jar')) -and ($text.Contains($base+'\backend\target\classes') -or $text.Contains($base+'\backend\target\light-manufacturing-server-0.1.0.jar'))}
        default {return $false}
    }
}
function Get-ProjectDefinitions {
    return @(
        @{Name='Backend';Port=8081;Script='Start-Backend.ps1';Url='http://127.0.0.1:8081/actuator/health'},
        @{Name='Agent';Port=8091;Script='Start-Agent.ps1';Url='http://127.0.0.1:8091/health'},
        @{Name='Frontend';Port=5173;Script='Start-Frontend.ps1';Url='http://localhost:5173'}
    )
}
function Get-ProjectListener([int]$Port) {
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
}
function Assert-ProjectProcess($Definition,[int]$ProcessId,[string]$Root) {
    $record=Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction Stop
    if(!$record){throw "Process $ProcessId disappeared; retry the command."}
    $command=[string]$record.CommandLine
    if($Definition.Name -eq 'Backend' -and $command.Contains('io.github.lightmanufacturing.Application') -and $command -match '@([^\s]+\.argfile)') {
        $argumentFile=$Matches[1]
        if([IO.Path]::GetFileName($argumentFile) -notmatch '^spring-boot-[a-zA-Z0-9-]+\.argfile$'){throw 'Unrecognized backend argument file; no process was stopped.'}
        if(!(Test-Path -LiteralPath $argumentFile -PathType Leaf)){throw 'Cannot verify backend classpath; no process was stopped.'}
        $argumentBytes=[IO.File]::ReadAllBytes($argumentFile)
        $command+=' '+[Text.Encoding]::UTF8.GetString($argumentBytes)+' '+[Text.Encoding]::GetEncoding(936).GetString($argumentBytes)
    }
    if(!(Test-ProjectCommand $Definition.Name $record.Name $command $Root)) {
        throw "Port $($Definition.Port) is occupied by an unverified process. Nothing was replaced; inspect PID $ProcessId."
    }
    return $record
}
function Test-ProjectHealth($Definition) {
    try {
        $response=Invoke-WebRequest -Uri $Definition.Url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
        if($response.StatusCode -ne 200){return $false}
        if($Definition.Name -eq 'Frontend'){return $response.Headers['X-Light-Mfg-Backend-Target'] -eq 'http://127.0.0.1:8081'}
        $content=$response.Content
        if($content -is [byte[]]){$content=[Text.Encoding]::UTF8.GetString($content)}
        $body=$content | ConvertFrom-Json
        if($Definition.Name -eq 'Backend'){return $body.status -eq 'UP'}
        return $body.status -eq 'ok' -and $body.serviceConfigured -eq $true -and $body.modelConfigured -eq $true
    } catch {return $false}
}
function Enter-ProjectLauncherLock([string]$Root) {
    $directory=Join-Path $Root '.local'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    try {return [IO.File]::Open((Join-Path $directory 'project-launcher.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
    catch {throw 'Another project start/stop command is running. Wait for it to finish.'}
}
function Stop-ProjectApplications([string]$Root) {
    $definitions=@(Get-ProjectDefinitions)
    $verified=@()
    foreach($definition in $definitions){
        foreach($processId in @(Get-ProjectListener $definition.Port)) {
            $record=Assert-ProjectProcess $definition $processId $Root
            $verified+=@{Definition=$definition;ProcessId=$processId;Created=$record.CreationDate}
        }
    }
    [array]::Reverse($verified)
    foreach($item in $verified) {
        $current=Assert-ProjectProcess $item.Definition $item.ProcessId $Root
        if($current.CreationDate -ne $item.Created){throw 'Process identity changed; retry instead of stopping it.'}
        Stop-Process -Id $item.ProcessId -ErrorAction Stop
        Write-Host "Stopped project $($item.Definition.Name) on $($item.Definition.Port)."
    }
    foreach($definition in $definitions) {
        $deadline=[DateTime]::UtcNow.AddSeconds(15)
        while(@(Get-ProjectListener $definition.Port).Count -gt 0) {
            if([DateTime]::UtcNow -gt $deadline){throw "Port $($definition.Port) is still listening; restart cancelled."}
            Start-Sleep -Milliseconds 300
        }
    }
}
