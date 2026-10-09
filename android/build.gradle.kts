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
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("com.google.protobuf:protobuf-java:3.25.5")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("commons-io:commons-io:2.18.0")
            classpath("org.apache.commons:commons-compress:1.27.1")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
        }
    }
}

plugins {
    id("com.android.application") version "8.8.2" apply false
    id("com.android.library") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
