pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        constraints {
            classpath("io.netty:netty-codec-http2:4.2.20.Final")
            classpath("io.netty:netty-codec-http:4.2.20.Final")
            classpath("io.netty:netty-handler:4.2.20.Final")
            classpath("io.netty:netty-codec:4.2.20.Final")
            classpath("io.netty:netty-common:4.2.20.Final")
            classpath("io.netty:netty-buffer:4.2.20.Final")
            classpath("io.netty:netty-transport:4.2.20.Final")
            classpath("io.netty:netty-handler-proxy:4.2.20.Final")
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("com.google.protobuf:protobuf-java:4.36.2")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("commons-io:commons-io:2.22.0")
            classpath("org.apache.commons:commons-compress:1.28.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // CI resolves Robolectric's framework and native runtime artifacts
        // through Google's public Maven Central mirror first to avoid Central
        // rate limits. Keep the mirror scoped to Robolectric; unrelated app
        // dependencies and local developer repository selection are unchanged.
        if (System.getenv("CI").equals("true", ignoreCase = true)) {
            maven {
                url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                content {
                    includeGroup("org.robolectric")
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "VeilFrame"
include(":app")
include(":opencv-sdk")
