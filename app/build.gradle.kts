import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// whisper.cpp native build configuration.
//
// These properties pin the upstream whisper.cpp revision that the
// `buildWhisperNative` task compiles. Override on the command line, e.g.:
//   ./gradlew buildWhisperNative -PwhisperCommit=<sha>
// ---------------------------------------------------------------------------
val whisperCommit: String = (project.findProperty("whisperCommit") as String?)
    ?: "v1.7.4"
val whisperRepo: String = (project.findProperty("whisperRepo") as String?)
    ?: "https://github.com/ggerganov/whisper.cpp.git"

android {
    namespace = "com.example.whisperserver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.whisperserver"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Primary target arm64-v8a, secondary armeabi-v7a per the spec.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Surface the pinned whisper.cpp revision to code / about screen.
        buildConfigField("String", "WHISPER_COMMIT", "\"$whisperCommit\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
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

    // The whisper-server binary (MVP subprocess option) ships as an asset and is
    // copied to executable storage at runtime. Do not compress it, so it can be
    // mmap'd / exec'd efficiently.
    androidResources {
        noCompress += listOf("bin", "so")
    }

    packaging {
        jniLibs {
            // Extract native libs to nativeLibraryDir at install time. The
            // whisper-server executable (shipped as libwhisper-server.so) must
            // exist as a real, executable file on disk to be exec'd.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

// ---------------------------------------------------------------------------
// Build pipeline task: cross-compile whisper.cpp for Android and drop the
// resulting shared libraries + whisper-server binary into the source tree.
//
// Requires: NDK r26+ installed and ANDROID_NDK_HOME / ndk.dir set, CMake, git.
// See scripts/build-whisper.sh for the actual build steps.
// ---------------------------------------------------------------------------
tasks.register<Exec>("buildWhisperNative") {
    group = "whisper"
    description = "Clones and cross-compiles whisper.cpp ($whisperCommit) for arm64-v8a + armeabi-v7a."

    val script = rootProject.file("scripts/build-whisper.sh")
    workingDir = rootProject.projectDir

    // Resolve an NDK path from the usual environment variables.
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("NDK_HOME")
        ?: ""

    environment("WHISPER_COMMIT", whisperCommit)
    environment("WHISPER_REPO", whisperRepo)
    environment("ANDROID_NDK_HOME", ndkHome)
    environment("APP_JNILIBS_DIR", file("src/main/jniLibs").absolutePath)
    environment("APP_ASSETS_DIR", file("src/main/assets").absolutePath)

    commandLine("bash", script.absolutePath)
}
