<p align="center"><img src="docs/app-icon-android.png" width="128" alt="KeyShelf"></p>
<h1 align="center">KeyShelf for Android</h1>

[Русский](README.md) · [Download APK](https://github.com/Frommer-droid/KeyShelf-Android/releases/latest) · [Windows](https://github.com/Frommer-droid/KeyShelf)

Local encrypted KDBX4 storage for accounts, API keys and custom data. Version 0.2.0, Android 8.0+. Tested on Redmi Note 10 Pro running Android 13. Distributed as an APK outside Google Play.

## Getting started

Install the release APK, create a vault or pair with Windows using Settings → Computer sync → Scan QR. Both devices must be on the same local Wi-Fi network. Conflicting edits are not overwritten automatically.

Enable the KeyShelf accessibility service for the floating icon. Focus a text field in another app, tap the icon, search by name or alias, expand a record and tap a value to insert it. Edit individual fields from the mini window. Drag the icon to move it; its position is saved on this device. Search and expanded records remain during the current session.

On Xiaomi enable system autostart and unrestricted battery use. After boot and device unlock, background access works without opening the main window. Force stopping the app disables background operation until a manual launch. Insertion depends on the target app's text field support.

## Data and security

Name controls the displayed title; Alias is an optional search tag. Record type controls the editor's fields. Master-password prompting is a device-wide setting independent of Windows. When disabled, AndroidKeyStore protects the cached unlock key and the last vault opens automatically. An unknown vault requires its password once. KDBX stays encrypted; passwords and cached keys are never synced. Keep independent encrypted backups. Passwords cannot be recovered. Uninstalling removes local app data and keys. There is no cloud server.

## Build

Native Kotlin, JDK 17, Android Platform 35, target API 33. See [DEVELOPER.md](DEVELOPER.md). `tools/build-portable.ps1` accepts `-Jdk`, `-AndroidJar`, `-Aapt2`, `-Zipalign`, `-Gradle`, runs tests and writes a signed APK to outputs. Signing keys are excluded. A self-built APK uses a different identity and cannot update the distributed APK without uninstalling it first.

## License

GPL-3.0; see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Settings → Record fields includes “Hide passwords and keys”, enabled by default. Disable it to show secrets as plain text in record views, mini search and editors. This preference is local to this device.

## Manual change delivery

Local edits stay on the device until Save is pressed (the floppy icon on Android). The receiver accepts or rejects the batch. Rejection restores the sender from the receiver when no newer local edits would be lost. There is no automatic 30-second publication. Update both apps together: Windows 0.5.0 and Android 0.2.0.
