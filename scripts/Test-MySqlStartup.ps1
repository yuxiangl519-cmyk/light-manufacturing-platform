$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'MySql-Startup.ps1')
if((Get-MySqlStartupAction $true $false $false $true) -ne 'Reuse'){throw 'Running instance must be reused even when filesystem detection fails.'}
if((Get-MySqlStartupAction $false $true $true $true) -ne 'Start'){throw 'Initialized instance must be started without initialization.'}
if((Get-MySqlStartupAction $false $false $false $false) -ne 'Initialize'){throw 'Only a new empty environment may initialize.'}
foreach($case in @(@($false,$false,$false,$true),@($false,$false,$true,$false))){
    $rejected=$false
    try {Get-MySqlStartupAction $case[0] $case[1] $case[2] $case[3] | Out-Null}catch{$rejected=$true}
    if(!$rejected){throw 'Existing environment must never silently initialize again.'}
}
Write-Host 'MySQL startup decision: 5 checks passed.'
