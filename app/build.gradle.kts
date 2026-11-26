plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.stamper.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stamper.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "stamper")

        ndk {
            // Real phones (arm64) + x86_64 emulator; skips 32-bit libs to keep the APK smaller.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    androidResources {
        // Let the native ASR engine mmap the model straight out of the APK.
        noCompress += listOf("onnx")
    }

    flavorDimensions += "speech"
    productFlavors {
        create("full") {
            dimension = "speech"
            // Bundles the offline Alibaba SenseVoice Chinese ASR model (~300MB APK).
        }
        create("lite") {
            dimension = "speech"
            // No bundled model: uses the phone's system speech recognizer / keyboard voice input.
            versionNameSuffix = "-lite"
        }
    }

    signingConfigs {
        getByName("debug") {
            // CI injects a shared keystore so every build installs over the previous one.
            val ciKeystore = System.getenv("STAMPER_KEYSTORE_FILE")
            if (!ciKeystore.isNullOrBlank() && file(ciKeystore).exists()) {
                storeFile = file(ciKeystore)
                // Unset GitHub secrets arrive as empty strings, so treat blank as "use default".
                fun envOr(name: String, default: String) =
                    System.getenv(name)?.takeUnless { it.isBlank() } ?: default
                storePassword = envOr("STAMPER_KEYSTORE_PASSWORD", "android")
                keyAlias = envOr("STAMPER_KEY_ALIAS", "androiddebugkey")
                keyPassword = envOr("STAMPER_KEY_PASSWORD", "android")
            }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl?.outputFileName = if (variant.buildType.name == "debug") {
                "stamper-${variant.flavorName}.apk"
            } else {
                "stamper-${variant.flavorName}-${variant.buildType.name}.apk"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // sherpa-onnx offline ASR runtime, full flavor only (fetch via scripts/fetch_asr_model.sh)
    "fullImplementation"(files("libs/sherpa-onnx-2.0.3.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
