import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("maven-publish")
    id("java-library")
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

publishing {
    repositories {
        mavenLocal()
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.lagradost"
            artifactId = "nicehttp"
            version = "0.4.15"
            from(components["java"])
        }
        create<MavenPublication>("jitpack") {
            groupId = "com.github.Blatzar" // jipack uses the GitHub username as groupId
            artifactId = "nicehttp"
            version = "0.4.15"
            from(components["java"])
        }
    }
}

kotlin {
    version = "1.0.1"

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-Xannotation-default-target=param-property"
        )
    }

    sourceSets {
        dependencies {
            val kotlinVersion = "2.2.0"

            // Parsing HTML
            api("org.jsoup:jsoup:1.21.2")

            // Parsing JSON
            /** Do not update to 2.13.2 it breaks compatibility with android < 24 !!! */
            // api "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1"

            // Networking
            api("com.squareup.okhttp3:okhttp:5.3.2")
            api("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2")

            // Async
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
            implementation("org.json:json:20250517")
        }
    }
}

java {
    // ensures Gradle uses JDK 17 to compile the code
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// https://docs.gradle.org/current/userguide/toolchains.html#combining_toolchains
tasks.withType<JavaCompile>().configureEach {
    // forces the compiler to generate bytecode compatible with Java 8
    // (prevents accidental use of newer APIs)
    options.release.set(8)
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        val kotlinVersion = "2.2.0"
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}
