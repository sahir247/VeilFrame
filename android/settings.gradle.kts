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
            classpath("io.netty:netty-codec-http2:4.1.118.Final")
            classpath("io.netty:netty-codec-http:4.1.118.Final")
            classpath("io.netty:netty-handler:4.1.118.Final")
            classpath("io.netty:netty-codec:4.1.118.Final")
            classpath("io.netty:netty-common:4.1.118.Final")
            classpath("io.netty:netty-buffer:4.1.118.Final")
            classpath("io.netty:netty-transport:4.1.118.Final")
            classpath("io.netty:netty-handler-proxy:4.1.118.Final")
            classpath("org.bouncycastle:bcprov-jdk18on:1.79")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.79")
            classpath("com.google.protobuf:protobuf-java:3.25.5")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("commons-io:commons-io:2.18.0")
            classpath("org.apache.commons:commons-compress:1.28.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VeilFrame"
include(":app")
include(":opencv-sdk")
