import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.GradleException
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.authentication.http.BasicAuthentication

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
    alias(libs.plugins.shadow)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))   // compileJava → 24
    }
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    // Pick a JDK 24 toolchain for kotlinc itself
    jvmToolchain(24)

    // Emit 24-bytecode
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)                   // compileKotlin → 24
    }
}

// Bundle LICENSE into the main JAR so published artifacts include it
val licenseJar by tasks.registering(Jar::class) {
    archiveClassifier.set("license")
    from(rootProject.file("LICENSE"))
}

artifacts {
    add("archives", licenseJar)
}

group = "com.TTT"
version = "1.0.0"

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

// =====================================================================
// CodeArtifact publishing — overrides internal group/version for the
// published artifact. Internal `com.TTT:1.0.0` stays for inter-module
// resolution. Consumer-facing published coord is `com.github.ten-trillion-triangles:TPipe:<version>`.
// =====================================================================

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.ten-trillion-triangles"
            artifactId = "TPipe"
            version = (project.findProperty("publishVersion") as String?) ?: project.version.toString()
            pom {
                name.set("TPipe (Community)")
                description.set("TPipe - Agent Operating Environment for LLM orchestration - Community/AGPL tier")
            }
            from(components["java"])
            artifact(tasks.named("licenseJar"))
        }
    }
    repositories {
        maven {
            name = "CodeArtifact"
            val repoUrl = (project.findProperty("codeArtifactRepoUrl") as String?)
                ?: "https://tpipe-521369004927.d.codeartifact.us-east-1.amazonaws.com/maven/tpipe-community-maven/"
            url = uri(repoUrl)
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "aws"
                // Lazy resolution — password is only read at publish time, not at config time.
                password = providers.environmentVariable("CODEARTIFACT_AUTH_TOKEN")
                    .orElse(providers.gradleProperty("codeArtifactAuthToken"))
                    .getOrElse("")
            }
        }
    }
}