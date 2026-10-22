[CmdletBinding()]
param([switch]$IncludeDependencies)
$ErrorActionPreference='Stop'
$launcherRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Project-Process.ps1')
$launcherLock=Enter-ProjectLauncherLock $launcherRoot
try {
    Stop-ProjectApplications $launcherRoot
    if($IncludeDependencies){& (Join-Path $PSScriptRoot 'Stop-LocalServices.ps1')}
    Write-Host 'Project applications stopped. Databases and demo data retained.'
    if(!$IncludeDependencies){Write-Host 'MySQL/Redis remain running. Add -IncludeDependencies to stop them too.'}
} finally {$launcherLock.Dispose()}
