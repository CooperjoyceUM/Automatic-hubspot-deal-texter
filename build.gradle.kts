buildscript {
    // AGP is added here (rather than in the plugins block) so that it is only
    // resolved when the Android app module is part of the build. See settings.gradle.kts.
    if (System.getenv("CORE_ONLY") == null) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.9.1")
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
