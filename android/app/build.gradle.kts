plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.veilframe.app"
    compileSdk = 35

    val supportedAbis = setOf("arm64-v8a")
    val customAbis = (project.findProperty("targetAbi") as String?)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }

    if (customAbis != null) {
        val unsupported = customAbis.filter { it !in supportedAbis }
        if (unsupported.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Unsupported targetAbi: ${unsupported.joinToString()}. " +
                "VeilFrame NCNN upscaler subsystem strictly supports arm64-v8a."
            )
        }
    }

    defaultConfig {
        applicationId = "com.veilframe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 229
        versionName = "2.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static", "-DANDROID_CPP_FEATURES=rtti exceptions")
                cppFlags("-fexceptions")
            }
        }

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
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
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            // Production release targets arm64-v8a exclusively
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Debug targets arm64-v8a exclusively
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.ExperimentalUnsignedTypes",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
    }

    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    // Spring physics for M3 Expressive motion (VfSprings)
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
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

    // ZXing — QR code encode & decode (QR Studio: generator + scanner)
    implementation("com.google.zxing:core:3.5.3")

    // CameraX — QR Studio scanner (camera preview + image analysis)
    val cameraXVersion = "1.3.2"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // Google ML Kit Barcode Scanning (bundled model for 100% offline QR scanning)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // OpenCV — first-class VeilFrame CV Engine (com.veilframe.app.cv)
    // Provides org.opencv.* and org.opencv.wechat_qrcode.WeChatQRCode
    implementation(project(":opencv-sdk"))

    // TensorFlow Lite runtime for neural document segmentation (FairScan SOTA model)
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // ViewPager2 — tab pager in QrStudioFragment
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // PDFBox Android & BouncyCastle (Offline PDF Engine: Decryption & Digital Signature Validation)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")

    // Google ML Kit Text Recognition (Offline/Bundled on-device OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Apache POI (Offline DOC/DOCX/XLSX to PDF Converter)
    implementation("org.apache.poi:poi:5.5.1")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.apache.commons:commons-compress:1.26.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.27.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Fail closed when release packaging tasks are scheduled for :app without release credentials
gradle.taskGraph.whenReady {
    val appReleaseTasks = allTasks.filter { task ->
        task.project == project && (
            task.name.startsWith("assembleRelease") ||
            task.name.startsWith("bundleRelease") ||
            task.name.startsWith("packageRelease") ||
            task.name.startsWith("validateSigningRelease")
        )
    }
    if (appReleaseTasks.isNotEmpty()) {
        val releaseConfig = android.signingConfigs.getByName("release")
        if (releaseConfig.storeFile == null || !releaseConfig.storeFile!!.exists() || releaseConfig.storePassword.isNullOrEmpty()) {
            throw GradleException(
                "Release keystore credentials missing! Release builds cannot be signed with debug keys. " +
                "Provide KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD."
            )
        }
    }
}
