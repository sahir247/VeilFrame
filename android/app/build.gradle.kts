import java.util.jar.JarFile

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

// Robolectric fetches framework JARs from Maven independently of Gradle, *during*
// JUnit class setup. On CI a transient HTTP failure at MavenArtifactFetcher.java:129
// looks like a product test failure (PrnuSanitizerEngineTest > classMethod).
//
// Resolve only the SDK levels used by our tests via Gradle BEFORE starting JUnit,
// so its normal dependency cache, mirror and checksum verification apply.
// Version strings are from Robolectric 4.18's DefaultSdkProvider (i7).
// @Config(sdk=33) QR tests, @Config(sdk=34) CV/Office tests, targetSdk=36 default.
val robolectricSdkVersions = listOf(
    "13-robolectric-9030017-i7",     // API 33
    "14-robolectric-10818077-i7",    // API 34
    "16-robolectric-13921718-2-i7"  // API 36
)
// Each framework is a different version of the SAME Maven module. Resolving
// them together selects only the highest version through Gradle conflict
// resolution. Isolate each SDK in its own non-transitive configuration.
val robolectricSdkConfigurations = robolectricSdkVersions.associateWith { version ->
    configurations.detachedConfiguration(
        dependencies.create("org.robolectric:android-all-instrumented:$version")
    ).apply {
        isTransitive = false
    }
}
val robolectricOfflineDir = layout.buildDirectory.dir("robolectric/preinstrumented")
// Use the *resolved artifact metadata* to stage each JAR at Robolectric's
// required filename. A bare Gradle Sync(from(configuration)) did not produce the
// expected API 33 file in CI despite successful dependency resolution and gave
// an unhelpful missing-or-truncated message. Never guess an artifact filename.
val prepareRobolectricSdkJars = tasks.register("prepareRobolectricSdkJars") {
    inputs.files(robolectricSdkConfigurations.values).withPropertyName("robolectricSdkJars")
    inputs.property("robolectricSdkVersions", robolectricSdkVersions)
    outputs.dir(robolectricOfflineDir).withPropertyName("preinstrumentedRobolectricJars")
    doLast {
        val destination = robolectricOfflineDir.get().asFile
        destination.mkdirs()
        val artifacts = robolectricSdkConfigurations.flatMap { (expectedVersion, configuration) ->
            val resolved = configuration.resolvedConfiguration.resolvedArtifacts
            check(resolved.size == 1 && resolved.single().moduleVersion.id.version == expectedVersion) {
                "Unexpected Robolectric SDK resolution for $expectedVersion: " +
                    resolved.map { it.moduleVersion.id.toString() }
            }
            resolved
        }
        val byVersion = artifacts.groupBy { it.moduleVersion.id.version }
        logger.lifecycle(
            "Robolectric prefetch: expected SDK versions={}, resolved artifacts={}",
            robolectricSdkVersions,
            artifacts.map { "${it.moduleVersion.id.version}: ${it.file.name} (${it.file.length()} bytes)" }
        )
        check(byVersion.keys == robolectricSdkVersions.toSet() &&
            robolectricSdkVersions.all { byVersion[it]?.size == 1 }) {
            "Unexpected Robolectric SDK artifacts: expected=$robolectricSdkVersions, " +
                "resolved=${artifacts.map { "${it.moduleVersion.id.version}/${it.file.name}" }}"
        }
        robolectricSdkVersions.forEach { version ->
            val source = byVersion.getValue(version).single().file
            check(source.isFile && source.length() > 10_000_000L) {
                "Resolved Robolectric SDK JAR missing/truncated: ${source.absolutePath}, " +
                    "exists=${source.exists()}, size=${if (source.exists()) source.length() else -1}"
            }
            // Validate the ZIP central directory and an actual Android framework
            // class rather than treating arbitrary nonempty files as SDK JARs.
            JarFile(source).use { jar ->
                check(jar.getJarEntry("android/os/Build.class") != null) {
                    "Resolved artifact is not a Robolectric Android framework JAR: $source"
                }
            }
            val expected = destination.resolve("android-all-instrumented-$version.jar")
            // The task runs when its inputs or outputs change. Replace even a
            // same-sized stale file instead of trusting its length as identity.
            source.copyTo(expected, overwrite = true)
            check(expected.isFile && expected.length() == source.length()) {
                "Robolectric JAR staging failed: source=${source.absolutePath} " +
                    "(${source.length()} bytes), staged=${expected.absolutePath} " +
                    "(${if (expected.exists()) expected.length() else -1} bytes)"
            }
            logger.lifecycle(
                "Robolectric SDK staged: {} -> {} ({} bytes)",
                source.name, expected.absolutePath, expected.length()
            )
        }
        // Fail closed if an old / removed SDK is still present. The output
        // directory must contain exactly the current three named JARs.
        val expectedNames = robolectricSdkVersions.map { "android-all-instrumented-$it.jar" }.toSet()
        destination.listFiles()?.filter { it.isFile && it.extension == "jar" && it.name !in expectedNames }
            ?.forEach { obsolete -> check(obsolete.delete()) { "Cannot remove stale SDK JAR: $obsolete" } }
    }
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (providers.environmentVariable("CI").orNull.equals("true", ignoreCase = true)) {
        dependsOn(prepareRobolectricSdkJars)
        systemProperty("robolectric.dependency.dir", robolectricOfflineDir.get().asFile.absolutePath)
        systemProperty("robolectric.offline", "true")
    } else {
        // Local developer JVM tests continue to work without a prefetch task.
        systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2/")
    }
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
