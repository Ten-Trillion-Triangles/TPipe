import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.TTT"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":TPipe-MCP"))

    // Keep AgentCore on the last AWS Kotlin SDK 1.6.x line. AWS SDK Kotlin
    // 1.8.x requires Kotlin 2.4 and is intentionally outside this module's
    // compatibility boundary.
    implementation("aws.sdk.kotlin:bedrockagentcore:1.6.107")
    implementation("aws.sdk.kotlin:bedrockagentcorecontrol:1.6.107")
    implementation("aws.sdk.kotlin:aws-core:1.6.107")
    implementation("aws.smithy.kotlin:http-client-engine-okhttp-jvm:1.6.15")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")

    implementation("io.ktor:ktor-server-core:3.3.3")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
    implementation("io.ktor:ktor-server-websockets:3.3.3")
    implementation("io.ktor:ktor-client-core:3.3.3")
    implementation("io.ktor:ktor-client-cio:3.3.3")
    implementation("io.ktor:ktor-client-websockets:3.3.3")

    implementation("io.opentelemetry:opentelemetry-api:1.54.1")
    implementation("io.opentelemetry:opentelemetry-sdk:1.54.1")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.54.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.ktor:ktor-client-mock:3.3.3")
    testImplementation("io.ktor:ktor-client-websockets:3.3.3")
}

/**
 * Prevent accidental resolution of an AWS Kotlin SDK line that requires a
 * newer Kotlin compiler than the repository currently supports.
 */
val verifyAgentCoreAwsSdkCompatibility by tasks.registering {
    group = "verification"
    description = "Verify AgentCore stays on the pinned AWS Kotlin SDK 1.6.107 line."
    doLast {
        val allowedVersion = "1.6.107"
        val resolved = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group.startsWith("aws.sdk.kotlin") }
        val violations = resolved.filter { it.version != allowedVersion }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "TPipe-AgentCore requires aws.sdk.kotlin $allowedVersion; resolved: " +
                    violations.joinToString { "${it.group}:${it.name}:${it.version}" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyAgentCoreAwsSdkCompatibility)
}

tasks.test {
    useJUnitPlatform()
}
