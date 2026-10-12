function Invoke-KeyShelfAdb {
    param([string]$AdbPath, [string]$Serial, [string[]]$Arguments)
    $deviceArguments = @($Arguments)
    if ($Serial) { $deviceArguments = @('-s', $Serial) + $deviceArguments }
    $result = & $AdbPath @deviceArguments
    if ($LASTEXITCODE -ne 0) { throw "ADB $($Arguments[0]) завершился с кодом $LASTEXITCODE" }
    $result
}

function Wait-KeyShelfRebind { param([int]$Milliseconds) Start-Sleep -Milliseconds $Milliseconds }

function Install-KeyShelfApk {
    param([string]$AdbPath, [string]$ApkPath, [string]$Serial, [switch]$EnableServiceRecovery)
    if (-not $Serial) {
        $devices = @(Invoke-KeyShelfAdb -AdbPath $AdbPath -Arguments @('devices') |
            ForEach-Object { if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] } })
        if ($devices.Count -ne 1) { throw 'Подключите один разрешённый телефон или укажите Serial.' }
        $Serial = $devices[0]
    }
    $component = 'app.keyshelf/app.keyshelf.FloatingService'
    $services = ((Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -join '').Trim()
    $enabled = ((Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')) -join '').Trim()
    $restoreService = $enabled -eq '1' -and ($services -split ':') -contains $component
    Invoke-KeyShelfAdb $AdbPath $Serial @('install', '-r', $ApkPath)
    if ($EnableServiceRecovery) {
        Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'pm', 'grant', 'app.keyshelf', 'android.permission.WRITE_SECURE_SETTINGS') | Out-Null
    }
    if (-not $restoreService) { return }

    # MIUI can keep the enabled flag but leave an updated service in Crashed services.
    # Refresh only the service the user already enabled; leave the others selected.
    $current = ((Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -join '').Trim()
    $others = (($current -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $component }) -join ':'
    try {
        if ($others) {
            Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $others) | Out-Null
        } else {
            Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null
        }
        Wait-KeyShelfRebind 3500
    } finally {
        $latest = ((Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -join '').Trim()
        $selected = @((($latest -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $component })) + $component
        Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($selected -join ':')) | Out-Null
        Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
    }
    Wait-KeyShelfRebind 3500
    $status = (Invoke-KeyShelfAdb $AdbPath $Serial @('shell', 'dumpsys', 'accessibility')) -join "`n"
    if ($status -notmatch 'Service\[label=KeyShelf,' -or $status -match 'Crashed services:[^\r\n]*app\.keyshelf') {
        throw 'APK установлен, но Android не подключил службу KeyShelf. Проверьте специальные возможности.'
    }
    Write-Output 'Служба плавающей кнопки KeyShelf подключена.'
}
