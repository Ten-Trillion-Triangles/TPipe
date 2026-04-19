import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Support for GraalVM  Native is planned but not yet implemented. This will allow us to deploy this library
 * as a .so to any platform where a jar can't be used. Enabling deployment to edge devices, iot, phones, and
 * other systems as well as ensuring shims for other languages can be built to enable further direct to codebase
 * integrations of TPipe.
 */

plugins {
    alias(libs.plugins.kotlin.jvm) version "2.2.20"
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))   // compileJava → 24
    }
}

kotlin {
    // Pick a JDK 24 toolchain for kotlinc itself
    jvmToolchain(24)

    // Emit 24-bytecode
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)                   // compileKotlin → 24
    }
}

group = "com.TTT"
version = "0.0.1"

application {
    mainClass = "com.TTT.ApplicationKt"
}

repositories {
    mavenCentral()
}

dependencies {
    // Server
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Client
    implementation("io.ktor:ktor-client-core:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-client-cio:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.version.get()}")

    // Scripting
    implementation(kotlin("scripting-jsr223"))
    implementation(kotlin("scripting-jvm-host"))

    // Logging
    implementation(libs.logback.classic)

    // TPipe-MCP for server hosting modes
    // Using runtimeOnly to avoid circular dependency at compile time.
    runtimeOnly(project(":TPipe-MCP"))

    // MCP Server Hosting
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.1")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.ktor:ktor-server-auth:3.3.3")

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    jvmArgs("-Xmx512m")
}
