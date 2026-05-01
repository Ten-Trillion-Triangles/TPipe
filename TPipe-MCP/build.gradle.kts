import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
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

application {
    mainClass = "com.TTT.MCP.Bridge.McpBridgeMain"
}

group = "com.TTT"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.1")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.ktor:ktor-server-auth:3.3.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
}

// Ensure proper source set configuration
sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
    test {
        kotlin.srcDirs("src/test/kotlin")
    }
}

tasks.test {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )
}
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Create standalone uber-jar using standard Gradle Zip API
// NOTE: Excludes Netty jars because TPipe bundles Netty 4.1.x which conflicts with
// Armeria 1.38.0's Netty 4.2.x requirement in server-extend.
tasks.named<Jar>("jar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    manifest {
        attributes["Main-Class"] = "com.TTT.MCP.Bridge.McpBridgeMain"
    }
    from({
        configurations.runtimeClasspath.get().files
            .filter { it.exists() && !it.name.startsWith("TPipe-MCP-") }
            .filter { !it.name.startsWith("netty-") && !it.name.contains("netty-") }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}