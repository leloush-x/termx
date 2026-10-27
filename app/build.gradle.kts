plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.termx"
    compileSdk = 37
    ndkVersion = "29.0.13846066"

    defaultConfig {
        applicationId = "app.termx" // placeholder, see docs/DECISIONS.md D1
        minSdk = 26
        targetSdk = 37
        versionCode = 23
        versionName = "0.8.0"
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

    buildTypes {
        release {
            // CI signs the aligned APK with apksigner using both V1 and V2 schemes.
            isMinifyEnabled = false
            isShrinkResources = false
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
    implementation(libs.kyant.backdrop)
    implementation(libs.androidx.datastore.preferences)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}
