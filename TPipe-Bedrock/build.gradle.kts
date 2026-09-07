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
    
    // AWS SDK and Smithy versions are shared with TPipe-AgentCore through the
    // root version catalog so AWS-facing modules remain on one generation.
    implementation("aws.sdk.kotlin:bedrockruntime:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.sdk.kotlin:aws-core:${libs.versions.aws.sdk.kotlin.get()}")
    implementation("aws.smithy.kotlin:http-client-engine-okhttp-jvm:${libs.versions.smithy.kotlin.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
