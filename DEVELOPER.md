# Development

JDK17, Gradle8.13, Android Platform35, target33. Build through tools/build-portable.ps1 with explicit Jdk, AndroidJar, Aapt2, Zipalign, Gradle paths; outputs/KeyShelf-0.2.0.apk. Tests use synthetic data. Protocol3 publishes only immutable manual Save batches and delivers events on a pinned TLS LAN channel. Signing keys remain local. A different key cannot update the official APK.
