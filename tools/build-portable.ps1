param(
    [switch]$Install,
    [switch]$ProvisionDebug,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$AndroidJar = $env:KEYSHELF_ANDROID_JAR,
    [string]$Aapt2 = $env:KEYSHELF_AAPT2,
    [string]$Zipalign = $env:KEYSHELF_ZIPALIGN,
    [string]$Gradle = $(if ($env:KEYSHELF_GRADLE) { $env:KEYSHELF_GRADLE } else { 'gradle' }),
    [string]$Adb = 'adb',
    [string]$WorkDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $WorkDirectory) { $WorkDirectory = Join-Path (Split-Path -Parent $projectRoot) 'temp-dev/KeyShelf-Android' }
$env:KEYSHELF_WORK = [IO.Path]::GetFullPath($WorkDirectory)
$taskWork = Join-Path $env:KEYSHELF_WORK 'portable'
if (-not $AndroidJar -and $env:ANDROID_HOME) { $AndroidJar = Join-Path $env:ANDROID_HOME 'platforms/android-35/android.jar' }
foreach ($tool in @($Jdk, $AndroidJar, $Aapt2, $Zipalign)) { if (-not $tool -or -not (Test-Path -LiteralPath $tool)) { throw 'Specify Jdk, AndroidJar, Aapt2 and Zipalign paths.' } }
$env:KEYSHELF_ANDROID_JAR = $AndroidJar
$javaBin = Join-Path $Jdk 'bin'
$env:JAVA_HOME = Split-Path -Parent $javaBin
$env:GRADLE_USER_HOME = Join-Path $env:KEYSHELF_WORK 'gradle-cache'
$androidJar = $AndroidJar
$aapt = $Aapt2
$align = $Zipalign
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Команда завершилась с кодом $LASTEXITCODE" } }
New-Item -ItemType Directory -Force -Path "$taskWork\generated","$taskWork\dex","$taskWork\tools" | Out-Null
& $aapt compile --dir "$projectRoot\app\src\main\res" -o "$taskWork\resources.zip"
Check-Exit
[xml]$manifest = Get-Content -LiteralPath "$projectRoot\app\src\main\AndroidManifest.xml"
$manifest.manifest.SetAttribute('package', 'app.keyshelf')
if ($ProvisionDebug) { $manifest.manifest.application.SetAttribute('debuggable', 'http://schemas.android.com/apk/res/android', 'true') }
$manifest.Save("$taskWork\AndroidManifest.xml")
& $aapt link -I $androidJar -A "$projectRoot/app/src/main/assets" --manifest "$taskWork\AndroidManifest.xml" --java "$taskWork\generated" --min-sdk-version 26 --target-sdk-version 33 --version-code 4 --version-name 0.2.0 -o "$taskWork\resources.apk" "$taskWork\resources.zip"
Check-Exit
Push-Location "$projectRoot\portable-build"
try { & $Gradle test jar copyDependencies copyBuildTools --console=plain; Check-Exit } finally { Pop-Location }
$jars = Get-ChildItem -LiteralPath "$taskWork\dependencies" -Filter '*.jar' | ForEach-Object FullName
$r8 = (Get-ChildItem -LiteralPath "$taskWork\tools" -Filter 'r8-*.jar' | Select-Object -First 1).FullName
$appJar = Join-Path $env:KEYSHELF_WORK 'build/portable/libs/KeyShelfPortableBuild.jar'
& "$javaBin\java.exe" -Xmx3g -cp $r8 com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output "$taskWork\dex" $appJar @jars
Check-Exit
Copy-Item -LiteralPath "$taskWork\resources.apk" -Destination "$taskWork\unsigned.apk" -Force
Push-Location "$taskWork\dex"
try { & "$javaBin\jar.exe" uf "$taskWork\unsigned.apk" classes.dex; Check-Exit } finally { Pop-Location }
& $align -f 4 "$taskWork\unsigned.apk" "$taskWork\aligned.apk"
Check-Exit
$signingDir = Join-Path $projectRoot '.local-state'
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
$signingKey = Join-Path $signingDir 'keyshelf-debug.p12'
if (-not (Test-Path -LiteralPath $signingKey)) {
    & "$javaBin\keytool.exe" -genkeypair -keystore $signingKey -storetype PKCS12 -storepass android -keypass android -alias keyshelf -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=KeyShelf personal development'
    Check-Exit
}
$apkSign = (Get-ChildItem -LiteralPath "$taskWork\tools" -Filter 'apksig-*.jar' | Select-Object -First 1).FullName
& "$javaBin\javac.exe" -cp $apkSign -d "$taskWork\tools" "$projectRoot\tools\SignApk.java"
Check-Exit
$ready = if ($ProvisionDebug) { Join-Path $env:KEYSHELF_WORK 'provision-debug' } else { Join-Path $projectRoot 'outputs' }
New-Item -ItemType Directory -Force -Path $ready | Out-Null
& "$javaBin\java.exe" -cp "$taskWork\tools;$apkSign" SignApk $signingKey "$taskWork\aligned.apk" "$ready\KeyShelf-0.2.0.apk"
Check-Exit
& $aapt dump badging "$ready\KeyShelf-0.2.0.apk"
Check-Exit
if ($Install) {
    . "$PSScriptRoot\install-apk.ps1"
    Install-KeyShelfApk -AdbPath $Adb -ApkPath "$ready\KeyShelf-0.2.0.apk" -EnableServiceRecovery
}
