import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing is read from keystore.properties, which is gitignored.
 * See RELEASE.md for how to create it. When it is absent the release build
 * stays unsigned rather than silently falling back to the debug key, which
 * would produce an APK that looks releasable but can never be updated on Play.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "org.meshline.app"
    compileSdk = 36

    // Pinned so every machine and CI runner links the native core against the
    // same NDK. Without this, android.ndkDirectory cannot resolve at all.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "org.meshline.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Only ship ABIs we actually build a native core for. Bundling an ABI
        // without libmeshline_ffi.so would install an app whose secure core is
        // missing, which now fails closed and refuses to send anything.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Native symbols for readable crash reports in Play Console.
            ndk { debugSymbolLevel = "FULL" }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Required for the Rust cdylib to be loadable on API 23+ without
            // extraction; also what lets debugSymbolLevel strip cleanly.
            useLegacyPackaging = false
        }
    }

    lint {
        // A missing Bluetooth permission check is now a build failure, not a
        // warning. That class of bug is exactly what crashed the relay service.
        error += listOf("MissingPermission", "InlinedApi")
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// ---------------------------------------------------------------------------
// Rust native core
//
// The .so files are build outputs, not checked-in artifacts. Previously
// src/main/jniLibs was gitignored and populated by hand, so a clean clone built
// an APK with no native library at all.
// ---------------------------------------------------------------------------

val rustRoot = rootProject.file("..")
val skipRustBuild = providers.gradleProperty("meshline.skipRustBuild")
    .map { it.toBoolean() }
    .getOrElse(false)

// Each variant gets its own output directory. Sharing one would make the debug
// and release cargo tasks fight over the same path whenever both run in a single
// invocation, and would let a debug .so leak into a release APK.
fun rustJniLibsDir(variant: String) = layout.buildDirectory.dir("rustJniLibs/$variant")

fun registerCargoTask(name: String, variant: String, profileArgs: List<String>) =
    tasks.register<Exec>(name) {
        group = "rust"
        description = "Builds libmeshline_ffi.so for all shipped ABIs via cargo-ndk."
        workingDir = rustRoot
        val jniLibsDir = rustJniLibsDir(variant).get().asFile

        // Resolved lazily: touching android.ndkDirectory at configuration time
        // fails the whole build on machines where the NDK is not yet unpacked.
        doFirst {
            environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        }

        commandLine(
            buildList {
                add(if (org.gradle.internal.os.OperatingSystem.current().isWindows) "cargo.exe" else "cargo")
                add("ndk")
                addAll(listOf("-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64"))
                addAll(listOf("-o", jniLibsDir.absolutePath))
                add("build")
                addAll(listOf("-p", "meshline-ffi"))
                addAll(profileArgs)
            }
        )

        // Rebuild whenever the Rust sources or manifests change.
        inputs.dir(rustRoot.resolve("crates"))
        inputs.file(rustRoot.resolve("Cargo.toml"))
        inputs.file(rustRoot.resolve("Cargo.lock"))
        outputs.dir(jniLibsDir)

        onlyIf { !skipRustBuild }
    }

val cargoBuildDebug = registerCargoTask("cargoBuildDebug", "debug", emptyList())
val cargoBuildRelease = registerCargoTask("cargoBuildRelease", "release", listOf("--release"))

android.sourceSets.getByName("debug").jniLibs.srcDir(rustJniLibsDir("debug"))
android.sourceSets.getByName("release").jniLibs.srcDir(rustJniLibsDir("release"))

// Merging JNI lib folders is the first task that reads those directories. AGP
// registers these tasks late, so match lazily rather than looking them up by
// name at configuration time.
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    dependsOn(if (name.contains("Release")) cargoBuildRelease else cargoBuildDebug)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
