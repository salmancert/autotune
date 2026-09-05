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
