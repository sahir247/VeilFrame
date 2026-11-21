buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9's built-in Kotlin chooses its KGP from the buildscript classpath.
        // Keep the previously validated Kotlin 2.4.21 compiler without applying
        // the deprecated org.jetbrains.kotlin.android plugin to Android modules.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        constraints {
            classpath("io.netty:netty-codec-http2:4.2.20.Final")
            classpath("io.netty:netty-codec-http:4.2.20.Final")
            classpath("io.netty:netty-handler:4.2.21.Final")
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

plugins {
    id("com.android.application") version "9.3.3" apply false
    id("com.android.library") version "9.3.3" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
