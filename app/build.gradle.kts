plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes only from CI secrets (exported as TERMX_KEYSTORE_FILE and
// friends). The repo never contains a keystore or a password. See README.md.
val releaseKeystore: String? = System.getenv("TERMX_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.termx"
    compileSdk = 37
    ndkVersion = "29.0.13846066"

    defaultConfig {
        applicationId = "app.termx" // placeholder, see docs/DECISIONS.md D1
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Keep dependency metadata out of the APK (F-Droid friendly, smaller).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_ALL_FILES", "false")
        }
        create("direct") {
            // GitHub and F-Droid builds. Phase 3 adds MANAGE_EXTERNAL_STORAGE here only.
            dimension = "distribution"
            buildConfigField("boolean", "HAS_ALL_FILES", "true")
        }
    }

    signingConfigs {
        // Created only when a keystore is supplied. Without it, release builds are unsigned.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("TERMX_KEYSTORE_PASSWORD")
                    ?: error("TERMX_KEYSTORE_PASSWORD is required when TERMX_KEYSTORE_FILE is set")
                keyAlias = System.getenv("TERMX_KEY_ALIAS")
                    ?: error("TERMX_KEY_ALIAS is required when TERMX_KEYSTORE_FILE is set")
                keyPassword = System.getenv("TERMX_KEY_PASSWORD")
                    ?: error("TERMX_KEY_PASSWORD is required when TERMX_KEYSTORE_FILE is set")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off until a release-device smoke test exists (DECISIONS.md D13).
            // wolfi-terminal v1.3.1 crashed on launch after shrinking.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // proot and its loader must exist as real files in nativeLibraryDir to be executed.
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.annotation)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Termux terminal engine (Apache-2.0 modules, see NOTICE).
    implementation(libs.termux.emulator)
    // API 26 lacks the platform Ed25519 Signature provider. This strict-verification fork
    // avoids the signature-malleability issue in the older net.i2p.crypto:eddsa:0.3.0.
    implementation(libs.eddsa)
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
