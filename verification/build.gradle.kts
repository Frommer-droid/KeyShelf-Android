plugins { kotlin("jvm") version "2.2.21" }
val workRoot = providers.environmentVariable("KEYSHELF_WORK").getOrElse(rootDir.parentFile.resolve("../temp-dev/KeyShelf-Android").canonicalPath)
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("**/VaultCodec.kt", "**/Totp.kt")
    }
}
dependencies {
    implementation("app.keemobile:kotpass:0.13.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
layout.buildDirectory.set(file("$workRoot/build/verification"))
tasks.test {
    systemProperty("fixtureDir", "$workRoot/compatibility")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
