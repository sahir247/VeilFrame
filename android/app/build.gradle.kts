plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.veilframe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.veilframe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 228
        versionName = "2.2.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: project.findProperty("KEYSTORE_PATH") as String?
            val storePass = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("KEYSTORE_PASSWORD") as String?
            val keyAl = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS") as String?
            val keyPass = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD") as String?

            val rootKeystore = rootProject.file("release.keystore")
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists() && !storePass.isNullOrEmpty()) {
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            } else if (file("release.keystore").exists() && !storePass.isNullOrEmpty()) {
                storeFile = file("release.keystore")
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            } else if (rootKeystore.exists() && !storePass.isNullOrEmpty()) {
                storeFile = rootKeystore
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            } else {
                val hasReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
                if (hasReleaseTask || isCi) {
                    throw GradleException("Release keystore credentials missing! Release builds cannot be signed with debug keys. Provide KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD.")
                }
            }

            // V1 is intentionally disabled.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Full Mobile FFmpegKit GPL with all audio/video encoders (including libx264 and libx265)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // AndroidX ExifInterface for native lossless and pixel-level EXIF privacy scrubbing
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // AndroidX Media3 ExoPlayer for modern video studio playback and timeline seeking
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // AndroidX WebKit for secure offline WebView asset loading and security policy
    implementation("androidx.webkit:webkit:1.11.0")

    // ONNX Runtime Android for AI image super-resolution inference (Plugin EP ABI compatible)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.27.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
