// Plugins are declared per module rather than here, so a checkout without an
// Android SDK can still build and test :audio-core and :model-training - the
// Android Gradle Plugin is only resolved when :app is part of the build.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
