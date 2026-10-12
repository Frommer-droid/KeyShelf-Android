plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val workRoot = providers.environmentVariable("KEYSHELF_WORK").getOrElse(rootDir.resolve("../temp-dev/KeyShelf-Android").canonicalPath)
android {
    namespace = "app.keyshelf"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.keyshelf"
        minSdk = 26
        targetSdk = 33
        versionCode = 4
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
layout.buildDirectory.set(file("$workRoot/build/app"))
dependencies {
    implementation("app.keemobile:kotpass:0.13.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
