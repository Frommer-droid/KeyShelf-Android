import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.zip.ZipFile

abstract class AarToJar : TransformAction<TransformParameters.None> {
    @get:InputArtifact abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val output = outputs.file(input.nameWithoutExtension + ".jar")
        ZipFile(input).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { stream -> output.outputStream().use { stream.copyTo(it) } }
        }
    }
}
plugins { kotlin("jvm") version "2.2.21" }
val workRoot = providers.environmentVariable("KEYSHELF_WORK").getOrElse(rootDir.parentFile.resolve("../temp-dev/KeyShelf-Android").canonicalPath)
val androidJar = providers.environmentVariable("KEYSHELF_ANDROID_JAR").getOrElse(System.getenv("ANDROID_HOME") + "/platforms/android-35/android.jar")
repositories { mavenCentral(); maven("https://maven.aliyun.com/repository/google") }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        java.srcDir("$workRoot/portable/generated")
    }
}
val buildTools by configurations.creating
dependencies {
    registerTransform(AarToJar::class) {
        from.attribute(Attribute.of("artifactType", String::class.java), "aar")
        to.attribute(Attribute.of("artifactType", String::class.java), "jar")
    }
    compileOnly(files("$androidJar"))
    implementation("app.keemobile:kotpass:0.13.0")
    implementation("com.google.zxing:core:3.5.3")
    buildTools("com.android.tools:r8:8.13.17")
    buildTools("com.android.tools.build:apksig:8.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testCompileOnly(files("$androidJar"))
    testRuntimeOnly(files("$androidJar"))
}
configurations.testRuntimeClasspath { attributes.attribute(Attribute.of("artifactType", String::class.java), "jar") }
tasks.test {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
layout.buildDirectory.set(file("$workRoot/build/portable"))
tasks.register<Copy>("copyDependencies") {
    from(configurations.runtimeClasspath)
    into("$workRoot/portable/dependencies")
}
tasks.register<Copy>("copyBuildTools") {
    from(buildTools)
    into("$workRoot/portable/tools")
}
