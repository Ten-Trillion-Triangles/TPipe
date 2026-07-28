plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
}

group = "com.TTT"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-core:${libs.versions.ktor.version.get()}")
    testImplementation("io.ktor:ktor-server-netty:${libs.versions.ktor.version.get()}")
    testImplementation(project(":TPipe-Defaults"))
    implementation(project(":"))
    
    // AWS SDK for Bedrock
    // 1.6.107 = last 1.6.x release (2026-07-06). Stays on Kotlin 2.3.21.
    // 1.8.x requires Kotlin 2.4.0 — separate epic.
    // The okhttp HTTP client engine was renamed in 1.6.x: the KMP root artifact
    // `http-client-engine-okhttp` no longer pulls the JVM classes — we have to
    // pin the `-jvm` variant explicitly so OkHttpEngine resolves.
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.107")
    implementation("aws.sdk.kotlin:aws-core:1.6.107")
    implementation("aws.smithy.kotlin:http-client-engine-okhttp-jvm:1.6.15")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
