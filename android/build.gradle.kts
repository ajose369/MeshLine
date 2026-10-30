plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // Kotlin 2.x ships the Compose compiler as a plugin; the old
    // composeOptions.kotlinCompilerExtensionVersion no longer applies.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}
