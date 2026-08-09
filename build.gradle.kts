import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.GradleException
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.authentication.http.BasicAuthentication
import org.gradle.external.javadoc.StandardJavadocDocletOptions

/**
 * Support for GraalVM  Native is planned but not yet implemented. This will allow us to deploy this library
 * as a .so to any platform where a jar can't be used. Enabling deployment to edge devices, iot, phones, and
 * other systems as well as ensuring shims for other languages can be built to enable further direct to codebase
 * integrations of TPipe.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.dokka)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))   // compileJava → 24
    }
    withSourcesJar()
    // No `withJavadocJar()` — Gradle's stock Javadoc tool rejects Kotlin
    // source files (`.kt` extension) and produces a 261-byte stub jar.
    // The `-javadoc.jar` artifact is built by Dokka instead, via the
    // `dokkaJavadoc` task defined below.
}

// Dokka produces a real Java-tooling-compatible `-javadoc.jar` containing
// HTML pages generated from Kotlin source (KDoc → HTML). The output jar
// shape matches what javadoc would emit, so consumers' IDEs pick it up
// automatically when published alongside the main jar.
tasks.named<org.jetbrains.dokka.gradle.DokkaTask>("dokkaJavadoc") {
    dokkaSourceSets.configureEach {
        sourceRoots.from("src/main/kotlin")
        jdkVersion.set(24)
        // KDoc on root-level files (Application.kt, Routing.kt,
        // Serialization.kt) that declare `package com.TTT` lives at
        // a path that doesn't match — Dokka handles this gracefully
        // (it documents them under the synthetic `com.TTT` package)
        // without the "illegal package name" errors Gradle's javadoc
        // task emits.
    }
}

// Wire Dokka into the javadoc jar slot so the published artifact set
// includes `-javadoc.jar` as consumers expect.
val dokkaTask = tasks.named<org.jetbrains.dokka.gradle.DokkaTask>("dokkaJavadoc")
val javadocJar by tasks.registering(Jar::class) {
    group = "documentation"
    description = "Assembles the -javadoc.jar artifact from Dokka's HTML output."
    archiveClassifier.set("javadoc")
    from(dokkaTask.flatMap { it.outputDirectory })
    dependsOn(dokkaTask)
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
    testImplementation(project(":TPipe-MCP"))

    // MCP Server Hosting
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.1")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.ktor:ktor-server-auth:3.3.3")

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.11.4")

    testImplementation(project(":TPipe-GenericOpenAI"))
    // Live-LLM test dependencies (only used by PumpStationLiveLLMTest when TPIPE_LIVE_LLM_TEST=true)
    testImplementation(project(":TPipe-Defaults"))
    testImplementation(project(":TPipe-OpenRouter"))
    testImplementation(project(":TPipe-Ollama"))
    testImplementation(project(":TPipe-Bedrock"))
}

tasks.test {
    useJUnitPlatform()
    val testHeapSize = (project.findProperty("testHeapSize") as String?) ?: "512m"
    jvmArgs("-Xmx$testHeapSize")
    // JDWP listener for debugging the test JVM (only when TPIPE_TEST_JDWP_PORT is set).
    // attach via: jdb -sourcepath src/main/kotlin -connect com.sun.jdi.SocketAttach:hostname=localhost,port=$PORT
    val debugPort = System.getenv("TPIPE_TEST_JDWP_PORT")
    if (debugPort != null) {
        val suspend = System.getenv("TPIPE_TEST_JDWP_SUSPEND") ?: "n"
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspend,address=*:$debugPort")
        println("[build.gradle.kts] test JVM JDWP listener enabled on port $debugPort (suspend=$suspend)")
    }
}

// =====================================================================
// CodeArtifact publishing — Startup edition. Same shape as the Community
// block on main, but with Startup-specific POM name and default repo URL.
// Internal `com.TTT:1.0.0` stays for inter-module resolution. Consumer-
// facing published coord is `com.github.ten-trillion-triangles:TPipe:<version>`.
// =====================================================================

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.ten-trillion-triangles"
            artifactId = "TPipe"
            version = (project.findProperty("publishVersion") as String?) ?: project.version.toString()
            pom {
                name.set("TPipe (Startup)")
                description.set("TPipe - Agent Operating Environment for LLM orchestration - Startup tier")
            }
            from(components["java"])
            artifact(tasks.named("licenseJar"))
            artifact(tasks.named("javadocJar"))
        }
    }
    repositories {
        maven {
            name = "CodeArtifact"
            val repoUrl = (project.findProperty("codeArtifactRepoUrl") as String?)
                ?: "https://tpipe-521369004927.d.codeartifact.us-east-1.amazonaws.com/maven/tpipe-startup-maven/"
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

// The kotlin 2.2.20 serialization compiler plugin previously refused to compile
// CoercionTest and JsonRepairTest because it could not read the kotlinx-serialization-core
// version from the classpath. With the Kotlin 2.3 readiness test set in place
// (see .hermes/plans/kotlin-23-test-readiness/plan.md) we are NOT removing the
// quarantine here — these tests still fail to compile under Kotlin 2.2.20 and
// that is a pre-existing on-main condition, not a regression from this plan.
// Re-enabling them is a separate task for the actual Kotlin 2.3 upgrade.
sourceSets.test {
    kotlin.exclude(
        "**/CoercionTest.kt",
        "**/JsonRepairTest.kt"
    )
}
