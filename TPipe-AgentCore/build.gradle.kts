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

    implementation("aws.sdk.kotlin:bedrockagentcore:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.sdk.kotlin:bedrockagentcorecontrol:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.sdk.kotlin:agentregistry:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.sdk.kotlin:agentregistrycontrol:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.sdk.kotlin:aws-core:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.smithy.kotlin:http-client-engine-okhttp-jvm:${libs.versions.smithy.kotlin.get()}")
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
 * Prevent accidental resolution of a mixed AWS Kotlin SDK generation.
 */
val verifyAgentCoreAwsSdkCompatibility by tasks.registering {
    group = "verification"
    description = "Verify AgentCore resolves one common AWS Kotlin SDK generation."
    doLast {
        val expectedVersion = libs.versions.aws.sdk.kotlin.get()
        val expectedSmithyVersion = libs.versions.smithy.kotlin.get()
        val resolved = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group.startsWith("aws.sdk.kotlin") }
        val violations = resolved.filter { it.version != expectedVersion }
        val resolvedSmithy = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group == "aws.smithy.kotlin" }
        val smithyViolations = resolvedSmithy.filter { it.version != expectedSmithyVersion }
        if (violations.isNotEmpty() || smithyViolations.isNotEmpty()) {
            throw GradleException(
                "TPipe-AgentCore requires aws.sdk.kotlin $expectedVersion and Smithy Kotlin " +
                    "$expectedSmithyVersion; resolved violations: " +
                    (violations + smithyViolations).joinToString {
                        "${it.group}:${it.name}:${it.version}"
                    }
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
