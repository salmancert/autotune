// The build runs on the JVM that launched Gradle, and the Kotlin compiler that
// builds these .kts files only knows JVM targets up to its own release. On a
// JDK newer than the toolchain, the build dies with a bare version number for a
// message ("* What went wrong: 26.0.1") that says nothing about the cause. Fail
// here instead, where there is room to explain.
run {
    val current = JavaVersion.current()
    val oldest = JavaVersion.VERSION_17
    val newest = JavaVersion.VERSION_21
    val override = System.getProperty("autotune.allowUnsupportedJdk") == "true"
    if (!override && (current < oldest || current > newest)) {
        val home = System.getProperty("java.home")
        error(
            """
            Autotune needs Java $oldest to $newest, but this build is running on Java $current.
              java.home = $home

            Java 17 is what CI builds with. Newer releases fail in confusing ways:
            the Android Gradle Plugin 8.7.3 targets 17, and Kotlin 2.0.21 cannot
            emit for JVM targets newer than itself.

            To fix it:
              sudo apt install openjdk-17-jdk          # or your distro's package
              ./gradlew --stop                         # the running daemon has the wrong JVM
              export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
              ./gradlew :app:assembleDebug

            To make it stick, put this in ~/.gradle/gradle.properties:
              org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64

            tools/build-apk.sh finds a supported JDK on its own.
            To try anyway: ./gradlew -Dautotune.allowUnsupportedJdk=true ...
            """.trimIndent(),
        )
    }
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "autotune"

// The DSP + model code is plain Kotlin/JVM so it builds and is unit-tested
// anywhere, with or without an Android SDK installed.
include(":audio-core")

// Corpus synthesis + training for the bundled classifier. Never shipped in the APK.
include(":model-training")

// The Android TV app needs an SDK. Skip it when one is not configured so that
// `./gradlew :audio-core:test` still works on a bare JVM (CI, containers).
val androidSdk: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }
        ?.let { props ->
            java.util.Properties().apply { props.inputStream().use(::load) }.getProperty("sdk.dir")
        }

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found (ANDROID_HOME / local.properties): skipping :app module.")
}
