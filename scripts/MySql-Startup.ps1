# Pure decision logic; a listener must have its project ownership verified by the caller.
function Get-MySqlStartupAction([bool]$Listening,[bool]$Initialized,[bool]$HasData,[bool]$Provisioned) {
    if($Listening){return 'Reuse'}
    if($Initialized){return 'Start'}
    if($Provisioned -or $HasData){throw 'Existing MySQL data is unavailable or incomplete. Refusing to reinitialize; inspect the project runtime or restore an explicitly approved backup.'}
    return 'Initialize'
}
