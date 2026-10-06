plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.veilframe.opencv"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val hasLocalNativeLibs = file("src/main/jniLibs").exists() &&
    file("src/main/jniLibs").walkTopDown().any { it.extension == "so" }
val hasLocalJavaSources = file("src/main/java").exists() &&
    file("src/main/java").walkTopDown().any { it.extension == "java" }

dependencies {
    if (hasLocalNativeLibs && hasLocalJavaSources) {
        logger.lifecycle(":opencv-sdk compiling with in-tree custom OpenCV 4.14 SDK artifacts")
    } else {
        logger.lifecycle(":opencv-sdk using vetted Maven WeChatQRCode OpenCV distribution (transitional baseline)")
        api("com.github.jenly1314.WeChatQRCode:opencv:2.6.0")
        api("com.github.jenly1314.WeChatQRCode:opencv-armv64:2.6.0")
        api("com.github.jenly1314.WeChatQRCode:opencv-x86_64:2.6.0")
    }
}
