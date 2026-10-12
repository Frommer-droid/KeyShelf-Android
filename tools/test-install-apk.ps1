$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\install-apk.ps1"
$ownComponent = 'app.keyshelf/app.keyshelf.FloatingService'
function Wait-KeyShelfRebind {
    param([int]$Milliseconds)
    if ($script:fixture.FailWait -and $Milliseconds -eq 3500) { throw 'synthetic interruption' }
}
function Invoke-KeyShelfAdb {
    param([string]$AdbPath, [string]$Serial, [string[]]$Arguments)
    $script:fixture.Calls.Add(($Arguments -join '|'))
    switch ($Arguments[0]) {
        'devices' { "synthetic-phone`tdevice" }
        'install' {
            if ($script:fixture.FailInstall) { throw 'synthetic install failure' }
            if ($Arguments[1] -ne '-r') { throw 'Must preserve app data' }
            if ($script:fixture.RemoveOnUpdate) { $script:fixture.Services = 'other/service' }
            'Success'
        }
        'shell' {
            if ($Arguments[1] -eq 'dumpsys') { 'Bound services:{Service[label=KeyShelf, feedbackType[FEEDBACK_GENERIC]]}' }
            elseif ($Arguments[2] -eq 'get') {
                if ($Arguments[4] -eq 'accessibility_enabled') { $script:fixture.Enabled }
                else { $script:fixture.Services }
            } elseif ($Arguments[2] -eq 'delete') { $script:fixture.Services = 'null' }
            elseif ($Arguments[2] -eq 'put') {
                if ($Arguments[4] -eq 'accessibility_enabled') { $script:fixture.Enabled = $Arguments[5] }
                else { $script:fixture.Services = $Arguments[5] }
            }
        }
    }
}
$passed = 0
foreach ($case in @('enabled', 'own-only', 'removed-on-update', 'disabled', 'global-off', 'install-failure', 'interrupted')) {
    $initial = if ($case -eq 'disabled') { 'other/service' } elseif ($case -eq 'own-only') { $ownComponent } else { "other/service:$ownComponent" }
    $script:fixture = @{
        Services = $initial; Enabled = if ($case -eq 'global-off') { '0' } else { '1' }
        FailInstall = $case -eq 'install-failure'; FailWait = $case -eq 'interrupted'
        RemoveOnUpdate = $case -eq 'removed-on-update'
        Calls = [System.Collections.Generic.List[string]]::new()
    }
    $failed = $false
    try { Install-KeyShelfApk -AdbPath 'fixture' -ApkPath 'synthetic.apk' | Out-Null } catch { $failed = $true }
    if ($failed -ne ($case -in @('install-failure', 'interrupted'))) { throw "Unexpected failure: $case" }
    if ($fixture.Services -ne $initial) { throw "Service choices changed: $case" }
    $writes = @($fixture.Calls | Where-Object { $_ -match '^shell\|settings\|(put|delete)\|' })
    if ($case -in @('disabled', 'global-off', 'install-failure') -and $writes.Count -ne 0) { throw "Unrequested activation: $case" }
    if ($case -in @('enabled', 'own-only', 'removed-on-update') -and $writes.Count -ne 3) { throw 'Expected targeted rebind' }
    if ($case -eq 'global-off' -and $fixture.Enabled -ne '0') { throw 'Global preference changed' }
    $passed++
}
Write-Output "$passed install lifecycle cases passed."
