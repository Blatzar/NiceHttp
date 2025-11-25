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
        withType<MavenPublication> {
            groupId = "com.lagradost"
            artifactId= "nicehttp"
            version= "0.4.13"
            //from(components.java)
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
    sourceCompatibility = JavaVersion.toVersion(JavaVersion.VERSION_1_8)
    targetCompatibility = JavaVersion.toVersion(JavaVersion.VERSION_1_8)
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
