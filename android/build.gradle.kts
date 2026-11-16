buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        constraints {
            classpath("io.netty:netty-codec-http2:4.2.19.Final")
            classpath("io.netty:netty-codec-http:4.2.19.Final")
            classpath("io.netty:netty-handler:4.2.19.Final")
            classpath("io.netty:netty-codec:4.2.19.Final")
            classpath("io.netty:netty-common:4.2.19.Final")
            classpath("io.netty:netty-buffer:4.2.19.Final")
            classpath("io.netty:netty-transport:4.2.19.Final")
            classpath("io.netty:netty-handler-proxy:4.2.19.Final")
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("com.google.protobuf:protobuf-java:3.25.5")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("commons-io:commons-io:2.22.0")
            classpath("org.apache.commons:commons-compress:1.28.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
        }
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
