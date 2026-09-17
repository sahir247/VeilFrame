plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.veilframe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.veilframe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 221
        versionName = "2.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
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
            signingConfig = signingConfigs.getByName("debug") // Configurable for production release key
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
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        pip {
            install("numpy>=1.24.0")
            install("pillow>=10.0.0")
            install("cryptography>=41.0.0")
            install("pyyaml>=6.0.1")
            install("pathspec>=0.11.0")
        }
    }

    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
        }
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

    // Full Mobile FFmpegKit with all audio/video codecs
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if ((requested.group == "com.arthenica" || requested.group == "com.arthenica.ffmpegkit") && requested.name.startsWith("ffmpeg-kit")) {
            useTarget("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
            because("com.arthenica artifacts were sunset on Maven Central and migrated to dev.ffmpegkit-maintained")
        }
    }
}
