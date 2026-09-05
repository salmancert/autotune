import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Java 17 bytecode: what the Android Gradle Plugin expects from a dependency,
// while still building on any JDK 17 or newer.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":audio-core"))
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
    testImplementation(project(":audio-core"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "2g"
    testLogging { events("passed", "failed", "skipped") }
}

/**
 * Regenerates the bundled speech/music/effects model:
 *
 *   ./gradlew :model-training:trainModel
 *
 * Rewrites audio-core/src/main/resources/dev/autotune/core/ml/speech_music_mlp.model
 * and prints held-out accuracy plus the confusion matrix.
 */
val trainModel by tasks.registering(JavaExec::class) {
    group = "autotune"
    description = "Trains the bundled classifier from the synthetic corpus."
    mainClass.set("dev.autotune.training.ModelTrainerKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        rootProject.layout.projectDirectory
            .dir("audio-core/src/main/resources/dev/autotune/core/ml")
            .asFile.absolutePath,
    )
}
