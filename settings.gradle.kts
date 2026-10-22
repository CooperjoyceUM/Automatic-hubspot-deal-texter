pluginManagement {
    repositories {
        google()
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

rootProject.name = "UtebuildTexter"

// The core module is plain Kotlin/JVM (timing, wording, phone numbers, the
// send/dedupe workflow) and can be built and tested without the Android SDK.
// Set CORE_ONLY=1 to build/test just that module on a machine with no SDK.
include(":core")
if (System.getenv("CORE_ONLY") == null) {
    include(":app")
}
