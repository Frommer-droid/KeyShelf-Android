param([switch]$Install, [string]$Sdk = $env:ANDROID_HOME, [string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:KEYSHELF_WORK = Join-Path (Split-Path -Parent $projectRoot) 'temp-dev/KeyShelf-Android'
$env:GRADLE_USER_HOME = Join-Path $env:KEYSHELF_WORK 'gradle-cache'
if (-not (Test-Path -LiteralPath "$Jdk\bin\java.exe")) { throw 'Настройте JDK 17 через параметр -Jdk' }
if (-not (Test-Path -LiteralPath "$Sdk\platforms\android-35\android.jar")) { throw 'Установите Android SDK Platform 35 через параметр -Sdk' }
Set-Location -LiteralPath $projectRoot
& "$Jdk\bin\java.exe" '-classpath' "$projectRoot\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Сборка или тесты завершились ошибкой' }
$readyDirectory = Join-Path $projectRoot 'outputs'
New-Item -ItemType Directory -Force -Path $readyDirectory | Out-Null
Copy-Item -LiteralPath "$env:KEYSHELF_WORK/build/app/outputs/apk/debug/app-debug.apk" -Destination "$readyDirectory\KeyShelf-0.2.0.apk"
if ($Install) {
    . "$PSScriptRoot\install-apk.ps1"
    Install-KeyShelfApk -AdbPath "$Sdk\platform-tools\adb.exe" -ApkPath "$readyDirectory\KeyShelf-0.2.0.apk" -EnableServiceRecovery
}
