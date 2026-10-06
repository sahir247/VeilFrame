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

dependencies {
    api("com.github.jenly1314.WeChatQRCode:opencv:2.6.0")
    api("com.github.jenly1314.WeChatQRCode:opencv-armv64:2.6.0")
    api("com.github.jenly1314.WeChatQRCode:opencv-x86_64:2.6.0")
}
