plugins {
    // AGP 8.4+ is required for Gradle 9.x
    id("com.android.application") version "8.4.2" apply false

    // Gradle 9 requires Kotlin Gradle Plugin 2.0+.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false

    // Required for Jetpack Compose with Kotlin 2.0+
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
