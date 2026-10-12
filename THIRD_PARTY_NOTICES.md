# Сторонние компоненты

Исходный код KeyShelf распространяется по GPL-3.0. Зависимости сохраняют свои лицензии.

| Компонент | Версия | Лицензия и исходники |
|---|---|---|
| Kotpass | 0.13.0 | MIT — https://github.com/keemobile/kotpass |
| ZXing Core | 3.5.3 | Apache-2.0 — https://github.com/zxing/zxing |
| Kotlin runtime | 2.2.21 | Apache-2.0 — https://github.com/JetBrains/kotlin |
| Okio (Kotpass dependency) | 3.15.0 | Apache-2.0 — https://github.com/square/okio |
| JetBrains annotations | 13.0 | Apache-2.0 — https://github.com/JetBrains/java-annotations |
| Android framework | API 33 target | Apache-2.0 and component licenses — https://source.android.com |

Gradle dependency reports describe the resolved transitive versions. Robolectric, JUnit, R8 and apksig are build/test tools and are not application features. Public APK signing certificates contain public keys only; private signing keys are excluded from source and recovery.
