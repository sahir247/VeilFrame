package com.veilframe.app.cv.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression and parity tests verifying that the custom OpenCV build scripts,
 * CI workflow, and Gradle configuration prevent missing Java binding errors
 * (such as org.opencv.videoio) and cascading R / BuildConfig / attrs compilation failures.
 */
class OpenCvSdkBuildConfigurationTest {

    private fun findProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "android/opencv-sdk").exists() && File(current, ".github").exists()) {
                return current
            }
            if (File(current, "opencv-sdk").exists() && File(current, "app").exists()) {
                val parent = current.parentFile
                if (parent != null && File(parent, "android").exists()) {
                    return parent
                }
            }
            current = current.parentFile
        }
        val fallback = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return if (File(fallback, "android").exists()) fallback else fallback.parentFile ?: fallback
    }

    @Test
    fun `build script explicitly whitelists videoio module`() {
        val root = findProjectRoot()
        val buildScript = File(root, "android/scripts/build_opencv_contrib_sdk.sh")
        assertTrue("build_opencv_contrib_sdk.sh must exist at ${buildScript.absolutePath}", buildScript.exists())

        val content = buildScript.readText()
        assertTrue(
            "BUILD_MODULES must explicitly include 'videoio' to generate org.opencv.videoio Java bindings",
            content.contains("videoio")
        )
        val moduleLine = content.lines().firstOrNull { it.startsWith("BUILD_MODULES=") }
        assertTrue("BUILD_MODULES line must be found", moduleLine != null)
        assertTrue(
            "BUILD_MODULES must contain videoio in its comma-separated list",
            moduleLine!!.contains(",videoio,")
        )
    }

    @Test
    fun `build script specifies ndk_api_level 26 for Camera2 NDK support`() {
        val root = findProjectRoot()
        val buildScript = File(root, "android/scripts/build_opencv_contrib_sdk.sh")
        val content = buildScript.readText()

        assertTrue(
            "opencv-veilframe.config.py must explicitly pass ndk_api_level '26' to ABI config for arm64-v8a",
            content.contains("""ABI("3", "arm64-v8a", None, "26"""")
        )
        assertTrue(
            "opencv-veilframe.config.py must explicitly pass ndk_api_level '26' to ABI config for x86_64",
            content.contains("""ABI("4", "x86_64", None, "26"""")
        )
    }

    @Test
    fun `build script validates Videoio java binding before staging`() {
        val root = findProjectRoot()
        val buildScript = File(root, "android/scripts/build_opencv_contrib_sdk.sh")
        val content = buildScript.readText()

        assertTrue(
            "Build script must validate org/opencv/videoio/Videoio.java existence",
            content.contains("org/opencv/videoio/Videoio.java")
        )
    }

    @Test
    fun `build script stages res directory into opencv-sdk`() {
        val root = findProjectRoot()
        val buildScript = File(root, "android/scripts/build_opencv_contrib_sdk.sh")
        val content = buildScript.readText()

        assertTrue(
            "Build script must create res directory in opencv-sdk",
            content.contains("""mkdir -p "${'$'}{OPENCV_SDK_DIR}/src/main/res"""")
        )
        assertTrue(
            "Build script must copy resources from SDK stage to opencv-sdk",
            content.contains("""cp -R "${'$'}{SDK_STAGE}/java/res/"* "${'$'}{OPENCV_SDK_DIR}/src/main/res/"""")
        )
    }

    @Test
    fun `opencv-sdk build gradle verifies Videoio java binding`() {
        val root = findProjectRoot()
        val buildGradle = File(root, "android/opencv-sdk/build.gradle.kts")
        assertTrue("opencv-sdk/build.gradle.kts must exist", buildGradle.exists())

        val content = buildGradle.readText()
        assertTrue(
            "build.gradle.kts must define videoioJava check",
            content.contains("""val videoioJava = file("src/main/java/org/opencv/videoio/Videoio.java")""")
        )
        assertTrue(
            "isCustomSdkComplete must require videoioJava.exists()",
            content.contains("videoioJava.exists()")
        )
    }

    @Test
    fun `opencv-sdk build gradle enables buildConfig and matches namespace`() {
        val root = findProjectRoot()
        val buildGradle = File(root, "android/opencv-sdk/build.gradle.kts")
        val content = buildGradle.readText()

        assertTrue(
            "build.gradle.kts must set namespace unconditionally to org.opencv",
            content.contains("""namespace = "org.opencv"""")
        )
        assertTrue(
            "build.gradle.kts must enable buildConfig in buildFeatures for BuildConfig imports",
            content.contains("buildConfig = true")
        )
    }

    @Test
    fun `opencv-sdk build gradle does not contain Maven fallback dependencies`() {
        val root = findProjectRoot()
        val buildGradle = File(root, "android/opencv-sdk/build.gradle.kts")
        val content = buildGradle.readText()

        assertTrue(
            "build.gradle.kts must not contain external jenly1314 Maven dependencies",
            !content.contains("com.github.jenly1314.WeChatQRCode")
        )
        assertTrue(
            "build.gradle.kts must not contain allowMavenOpenCvFallback property check",
            !content.contains("allowMavenOpenCvFallback")
        )
    }

    @Test
    fun `settings gradle does not contain JitPack repository`() {
        val root = findProjectRoot()
        val settingsFile = File(root, "android/settings.gradle.kts")
        assertTrue("settings.gradle.kts must exist", settingsFile.exists())
        val content = settingsFile.readText()

        assertTrue(
            "settings.gradle.kts must not declare jitpack.io repository",
            !content.contains("jitpack.io")
        )
    }

    @Test
    fun `gradle properties does not configure allowMavenOpenCvFallback`() {
        val root = findProjectRoot()
        val propertiesFile = File(root, "android/gradle.properties")
        assertTrue("gradle.properties must exist", propertiesFile.exists())
        val content = propertiesFile.readText()

        assertTrue(
            "gradle.properties must not define allowMavenOpenCvFallback",
            !content.contains("allowMavenOpenCvFallback")
        )
    }

    @Test
    fun `app build gradle validates release signing on task graph when ready and not during configuration`() {
        val root = findProjectRoot()
        val appGradle = File(root, "android/app/build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist", appGradle.exists())
        val content = appGradle.readText()

        assertTrue(
            "app/build.gradle.kts must not throw GradleException during project configuration based on startParameter.taskNames",
            !content.contains("gradle.startParameter.taskNames.any")
        )
        assertTrue(
            "app/build.gradle.kts must validate release keystore credentials in taskGraph.whenReady",
            content.contains("gradle.taskGraph.whenReady") &&
                content.contains("task.project == project") &&
                content.contains("task.name.startsWith(\"assembleRelease\")") &&
                content.contains("Release keystore credentials missing!")
        )
    }

    @Test
    fun `build script ensures and validates CameraBridgeViewBase attrs xml`() {
        val root = findProjectRoot()
        val buildScript = File(root, "android/scripts/build_opencv_contrib_sdk.sh")
        val content = buildScript.readText()

        assertTrue(
            "Build script must explicitly ensure attrs.xml with CameraBridgeViewBase",
            content.contains("""<declare-styleable name="CameraBridgeViewBase">""") &&
                content.contains("attrs.xml")
        )
    }

    @Test
    fun `opencv-sdk attrs xml defines CameraBridgeViewBase styled attributes`() {
        val root = findProjectRoot()
        val candidatePaths = listOf(
            File(root, "android/opencv-sdk/src/main/res/values/attrs.xml"),
            File(root, "opencv-sdk/src/main/res/values/attrs.xml"),
        )
        val attrsXml = candidatePaths.firstOrNull { it.exists() } ?: candidatePaths.first()
        assertTrue("attrs.xml must exist at ${attrsXml.absolutePath}", attrsXml.exists())

        val content = attrsXml.readText()
        assertTrue(
            "attrs.xml at ${attrsXml.absolutePath} must declare CameraBridgeViewBase styleable (content was: '$content')",
            content.contains("""<declare-styleable name="CameraBridgeViewBase">""")
        )
        assertTrue(
            "attrs.xml at ${attrsXml.absolutePath} must declare show_fps attribute",
            content.contains("""<attr name="show_fps" format="boolean"""")
        )
        assertTrue(
            "attrs.xml at ${attrsXml.absolutePath} must declare camera_id attribute",
            content.contains("""<attr name="camera_id" format="integer"""")
        )
    }

    @Test
    fun `ci workflow verifies Videoio and attrs xml and uploads res directory`() {
        val root = findProjectRoot()
        val workflowFile = File(root, ".github/workflows/build-opencv-sdk.yml")
        assertTrue("build-opencv-sdk.yml must exist", workflowFile.exists())

        val content = workflowFile.readText()
        assertTrue(
            "CI workflow must verify Videoio.java existence",
            content.contains("test -f android/opencv-sdk/src/main/java/org/opencv/videoio/Videoio.java")
        )
        assertTrue(
            "CI workflow must verify attrs.xml existence",
            content.contains("test -f android/opencv-sdk/src/main/res/values/attrs.xml")
        )
        assertTrue(
            "CI workflow must verify CameraBridgeViewBase in attrs.xml",
            content.contains("""grep -q '<declare-styleable name="CameraBridgeViewBase">' android/opencv-sdk/src/main/res/values/attrs.xml""")
        )
        assertTrue(
            "CI workflow must include res directory in uploaded artifacts",
            content.contains("android/opencv-sdk/src/main/res/")
        )
    }

    @Test
    fun `ci workflow configures working-directory and executable gradlew for device tests`() {
        val root = findProjectRoot()
        val workflowFile = File(root, ".github/workflows/build-opencv-sdk.yml")
        val content = workflowFile.readText()

        assertTrue(
            "CI workflow emulator step must configure working-directory for android subproject",
            content.contains("working-directory: ./android")
        )
        assertTrue(
            "CI workflow must ensure chmod +x on gradlew before emulator execution",
            content.contains("chmod +x ./gradlew")
        )
        val rootGradlew = File(root, "gradlew")
        assertTrue("Root gradlew wrapper must exist", rootGradlew.exists())
    }

    @Test
    fun `opencv-sdk and app build gradle configure opt-in for ExperimentalUnsignedTypes`() {
        val root = findProjectRoot()
        val sdkBuildGradle = File(root, "android/opencv-sdk/build.gradle.kts")
        val appBuildGradle = File(root, "android/app/build.gradle.kts")

        assertTrue("opencv-sdk/build.gradle.kts must exist", sdkBuildGradle.exists())
        assertTrue("app/build.gradle.kts must exist", appBuildGradle.exists())

        val sdkContent = sdkBuildGradle.readText()
        val appContent = appBuildGradle.readText()

        assertTrue(
            "opencv-sdk build.gradle.kts must configure -opt-in=kotlin.ExperimentalUnsignedTypes for MatAt.kt extensions",
            sdkContent.contains("-opt-in=kotlin.ExperimentalUnsignedTypes")
        )
        assertTrue(
            "app build.gradle.kts must configure -opt-in=kotlin.ExperimentalUnsignedTypes",
            appContent.contains("-opt-in=kotlin.ExperimentalUnsignedTypes")
        )
    }

    @Test
    fun `ci workflow configures KVM hardware acceleration for emulator runner`() {
        val root = findProjectRoot()
        val workflowFile = File(root, ".github/workflows/build-opencv-sdk.yml")
        val content = workflowFile.readText()

        assertTrue(
            "CI workflow must configure KVM permissions for hardware accelerated emulator execution",
            content.contains("/dev/kvm") && content.contains("99-kvm4all.rules")
        )
        assertTrue(
            "CI workflow must upload custom OpenCV SDK artifacts with if: always()",
            content.contains("Upload Custom OpenCV SDK Artifacts") &&
                content.contains("if: always()")
        )
    }

    @Test
    fun `root gradle wrapper files and dependency submission workflow exist and are configured`() {
        val root = findProjectRoot()
        val rootProperties = File(root, "gradle/wrapper/gradle-wrapper.properties")
        val rootJar = File(root, "gradle/wrapper/gradle-wrapper.jar")
        val submissionWorkflow = File(root, ".github/workflows/dependency-submission.yml")

        assertTrue("Root gradle-wrapper.properties must exist", rootProperties.exists())
        assertTrue("Root gradle-wrapper.jar must exist", rootJar.exists())
        assertTrue(
            "Root gradle-wrapper.properties must specify gradle-8.10.2",
            rootProperties.readText().contains("gradle-8.10.2")
        )
        assertTrue("dependency-submission.yml must exist", submissionWorkflow.exists())
        val submissionContent = submissionWorkflow.readText()
        assertTrue(
            "dependency-submission workflow must configure build-root-directory to ./android",
            submissionContent.contains("build-root-directory: ./android")
        )
        assertTrue(
            "dependency-submission workflow must configure gradle-version 8.10.2",
            submissionContent.contains("gradle-version: \"8.10.2\"")
        )
    }

    @Test
    fun `cv native subsystem contract defines all types and methods used by androidTest`() {
        val root = findProjectRoot()
        val templateMatcherFile = File(root, "android/app/src/main/java/com/veilframe/app/cv/template/TemplateMatcher.kt")
        val quadDetectorFile = File(root, "android/app/src/main/java/com/veilframe/app/cv/geometry/QuadDetector.kt")
        val perspectiveCorrectorFile = File(root, "android/app/src/main/java/com/veilframe/app/cv/geometry/PerspectiveCorrector.kt")
        val preprocessorFile = File(root, "android/app/src/main/java/com/veilframe/app/cv/preprocess/Preprocessor.kt")
        val colorEngineFile = File(root, "android/app/src/main/java/com/veilframe/app/cv/color/ColorEngine.kt")
        val matPoolFile = File(root, "android/app/src/main/java/com/veilframe/app/cv/core/MatPool.kt")

        val tmText = templateMatcherFile.readText()
        assertTrue("TemplateMatcher must define rect property on MatchResult", tmText.contains("val rect: Rect"))
        assertTrue("TemplateMatcher must be instantiable as class or invoke", tmText.contains("class TemplateMatcher") || tmText.contains("operator fun invoke"))
        assertTrue("TemplateMatcher must gracefully handle low-variance templates", tmText.contains("TM_SQDIFF_NORMED"))

        val qdText = quadDetectorFile.readText()
        assertTrue("QuadDetector must support invoke operator", qdText.contains("operator fun invoke"))
        assertTrue("QuadDetector must verify convexity on approximated 4-point quad", qdText.contains("Imgproc.isContourConvex(approxMat)"))

        val pcText = perspectiveCorrectorFile.readText()
        assertTrue("PerspectiveCorrector must support invoke operator", pcText.contains("operator fun invoke"))
        assertTrue("PerspectiveCorrector must provide warp convenience method", pcText.contains("fun warp("))

        val ppText = preprocessorFile.readText()
        assertTrue("Preprocessor must provide toGray alias", ppText.contains("fun toGray("))
        assertTrue("Preprocessor must provide BlurMethod enum", ppText.contains("enum class BlurMethod"))
        assertTrue("Preprocessor must provide blur function", ppText.contains("fun blur("))

        val ceText = colorEngineFile.readText()
        assertTrue("WhiteBalanceResult must provide size(), type(), and release() delegators", ceText.contains("fun size(): org.opencv.core.Size") && ceText.contains("fun release()"))

        val mpText = matPoolFile.readText()
        assertTrue("MatPool must have public constructor for device tests", mpText.contains("constructor(\n        maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,"))
    }

    @Test
    fun `ci workflow specifies cmdline-tools latest to prevent sdkmanager xml version mismatch`() {
        val root = findProjectRoot()
        val workflowFile = File(root, ".github/workflows/build-opencv-sdk.yml")
        val content = workflowFile.readText()

        assertTrue(
            "CI workflow must install cmdline-tools;latest to eliminate XML version warning",
            content.contains("cmdline-tools;latest")
        )
    }

    @Test
    fun `gradle properties configures in-process kotlin execution strategy to prevent daemon termination`() {
        val root = findProjectRoot()
        val propsFile = File(root, "android/gradle.properties")
        val content = propsFile.readText()

        assertTrue(
            "gradle.properties must configure kotlin.compiler.execution.strategy=in-process",
            content.contains("kotlin.compiler.execution.strategy=in-process")
        )
        assertTrue(
            "gradle.properties must configure kotlin.daemon.jvmargs",
            content.contains("kotlin.daemon.jvmargs")
        )
    }

    @Test
    fun `opencv-sdk build gradle keeps jniLibs debug symbols to prevent stripReleaseDebugSymbols warning`() {
        val root = findProjectRoot()
        val sdkGradle = File(root, "android/opencv-sdk/build.gradle.kts").readText()

        assertTrue(
            "opencv-sdk build.gradle.kts must configure packaging.jniLibs.keepDebugSymbols",
            sdkGradle.contains("keepDebugSymbols += listOf(\"**/*.so\")")
        )
    }
}
