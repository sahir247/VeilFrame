plugins {
    id("com.android.application")
}

android {
    namespace = "com.veilframe.app"
    // AndroidX Core 1.19.1 requires API 37; use the explicit 37.0 platform hash.
    // targetSdk stays 36, so Android 17 runtime behavior is not opted into.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

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
        targetSdk = 36
        versionCode = 231
        versionName = "2.3.1"

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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite")
    }

    ndkVersion = "27.2.12479018"

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

// Robolectric downloads its android-all-instrumented SDK jars at test runtime,
// outside Gradle's ordinary dependency resolution. Its repo1.maven.org default
// can fail transiently on shared CI hosts even after :app:assembleDebug passes.
// Use the canonical Maven Central hostname for every forked JVM test process;
// this changes TEST provisioning only, never production app dependencies.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2/")
}

// AGP 9 built-in Kotlin: preserve the existing Java 17 bytecode target and compiler opt-ins.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll("kotlin.ExperimentalUnsignedTypes", "kotlin.RequiresOptIn")
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    }
}

dependencies {
    // Bundled, offline byte-pair tokenizer; exact named-encoding artifact counts.
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    // Spring physics for M3 Expressive motion (VfSprings)
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Full Mobile FFmpegKit GPL with all audio/video encoders (including libx264 and libx265)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // AndroidX ExifInterface for native lossless and pixel-level EXIF privacy scrubbing
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // AndroidX Media3 ExoPlayer for modern video studio playback and timeline seeking
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // AndroidX WebKit for secure offline WebView asset loading and security policy
    implementation("androidx.webkit:webkit:1.18.0")

    // ONNX Runtime Android for AI image super-resolution inference (Plugin EP ABI compatible)

    // ZXing — QR code encode & decode (QR Studio: generator + scanner)
    implementation("com.google.zxing:core:3.5.4")

    // CameraX — QR Studio scanner (camera preview + image analysis)
    val cameraXVersion = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // Google ML Kit Barcode Scanning (bundled model for 100% offline QR scanning)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // OpenCV — first-class VeilFrame CV Engine (com.veilframe.app.cv)
    // Provides org.opencv.* and org.opencv.wechat_qrcode.WeChatQRCode
    implementation(project(":opencv-sdk"))

    // Google AI Edge LiteRT CompiledModel runtime and GPU accelerator (offline, bundled).
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.2")

    // ViewPager2 — tab pager in QrStudioFragment
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // PDFBox Android & BouncyCastle (Offline PDF Engine: Decryption & Digital Signature Validation)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")

    // Google ML Kit Text Recognition (Offline/Bundled on-device OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Apache POI (Offline DOC/DOCX/XLSX to PDF Converter)
    implementation("org.apache.poi:poi:6.0.0")
    // Replace the reduced schema bundle: D8 cannot dex one of its nested schema
    // classes because its declared nest host is absent from poi-ooxml-lite.
    // Apache POI documents poi-ooxml-full as the drop-in alternative to lite.
    implementation("org.apache.poi:poi-ooxml:6.0.0") {
        exclude(group = "org.apache.poi", module = "poi-ooxml-lite")
    }
    implementation("org.apache.poi:poi-ooxml-full:6.0.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("commons-io:commons-io:2.22.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.18")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// Fast metadata-only compatibility check, independent of the native OpenCV build.
// Configuration is resolved on demand and missing dependency coordinates fail the task.
// Avoid artifact transforms here: OpenCV's custom SDK may not be staged yet.
tasks.register("verifyDependencyResolution") {
    group = "verification"
    description = "Resolve Android compile/runtime dependency graphs before costly native builds."
    doLast {
        // Android Gradle Plugin names the resolvable test variants
        // debugUnitTest* and debugAndroidTest*, not testDebug*.
        // Check both compile and runtime classpaths, including test-only dependencies,
        // without requiring the custom OpenCV AAR to have been built.
        val scopes = listOf(
            "debugCompileClasspath",
            "debugRuntimeClasspath",
            "releaseCompileClasspath",
            "releaseRuntimeClasspath",
            "debugUnitTestCompileClasspath",
            "debugUnitTestRuntimeClasspath",
            "debugAndroidTestCompileClasspath",
            "debugAndroidTestRuntimeClasspath",
        )
        scopes.forEach { scope ->
            val configuration = configurations.findByName(scope)
                ?: throw GradleException(
                    "Expected Android classpath configuration '$scope' was not created. " +
                        "Available resolvable classpaths: " +
                        configurations.filter { it.isCanBeResolved && it.name.endsWith("Classpath") }
                            .joinToString { it.name }
                )
            check(configuration.isCanBeResolved) {
                "Expected '$scope' to be a resolvable Android classpath"
            }
            val failures = configuration.incoming.resolutionResult.allDependencies
                .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
            if (failures.isNotEmpty()) {
                val details = failures.joinToString("\n") { failure ->
                    // Gradle's top-level ModuleVersionResolveException often says
                    // only "Could not resolve X", hiding a nested HTTP 429. Keep
                    // a bounded cause chain so CI retries transient throttling,
                    // while missing modules/incompatibilities remain failures.
                    val chain = generateSequence(failure.failure) { it.cause }
                        .take(10).mapNotNull { it.message?.take(600) }
                        .distinct().joinToString(" -> ")
                    "${failure.requested.displayName}: $chain"
                }
                throw GradleException("Unresolved dependencies in $scope:\n$details")
            }
            logger.lifecycle("Verified dependency graph: $scope")
        }
    }
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
