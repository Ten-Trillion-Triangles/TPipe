# Installation and Setup

Welcome to the TPipe ecosystem. This guide helps you lay the foundation for your agentic infrastructure. We cover requirements, module organization, and how to get the plumbing connected in your Kotlin project.

## Prerequisites

Before building your infrastructure, ensure your environment meets these industrial standards:

### Required Versions

- **Java**: JDK 24 or higher. TPipe leverages modern JVM features and targets JVM 24 bytecode for maximum performance and security.
- **Kotlin**: Version 2.2.0.
- **Gradle**: Version 8.14.3 or higher.
- **Build System**: **Kotlin Gradle DSL only**. TPipe is designed for the type-safety and power of the Kotlin DSL; Maven and Groovy Gradle are not supported.

> [!TIP]
> **GraalVM** (CE 24+) is highly recommended for optimal performance and future native compilation support. If GraalVM is not available, standard OpenJDK 24+ works perfectly.

## TPipe Infrastructure

TPipe is a modular system, allowing you to include only the components your project requires:

```text
TPipe/
├── TPipe/                 # Core library: The fundamental pipes and valves
├── TPipe-Bedrock/         # AWS Bedrock integration: The high-pressure refinery
├── TPipe-Ollama/          # Ollama integration: Local execution and privacy
├── TPipe-MCP/             # MCP integration: External tool connectivity
└── TPipe-Defaults/        # Standard configurations: Ready-to-use fittings
```

## Connecting TPipe to Your Project

### 1. Project Configuration (`settings.gradle.kts`)

Include TPipe as a composite build in your project. This allows you to work with the TPipe source directly or treat it as a pre-built dependency.

```kotlin
rootProject.name = "your-project-name"

// Include TPipe as a composite build
includeBuild("../TPipe/TPipe")
```

> [!NOTE]
> **Path Notes**:
> - Adjust the path relative to your project location.
> - Example: If TPipe is in a sibling directory, use `../TPipe/TPipe`.
> - Example: If TPipe is in a subdirectory, use `TPipe/TPipe`.

### 2. Dependency Management (`build.gradle.kts`)

TPipe strictly requires **Kotlin Gradle DSL**. Add the following configuration to your `build.gradle.kts` to set up the toolchain and core dependencies:

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
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

repositories {
    mavenCentral()
}

dependencies {
    // Main TPipe library (Required)
    implementation("com.TTT:TPipe:0.0.1")
    
    // Provider modules (Choose your source)
    implementation("com.TTT:TPipe-Bedrock:0.0.1")  // AWS Bedrock
    implementation("com.TTT:TPipe-Ollama:0.0.1")   // Local Ollama
    implementation("com.TTT:TPipe-MCP:0.0.1")      // MCP Support
    implementation("com.TTT:TPipe-Defaults:0.0.1") // Standard fittings
    
    // Core runtime dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
}
```

### Version Catalog (Optional)

For more robust dependency management, you can use a version catalog:

**gradle/libs.versions.toml:**
```toml
[versions]
kotlin-version = "2.2.0"
ktor-version = "3.1.3"
tpipe-version = "0.0.1"

[libraries]
tpipe-main = { module = "com.TTT:TPipe", version.ref = "tpipe-version" }
tpipe-bedrock = { module = "com.TTT:TPipe-Bedrock", version.ref = "tpipe-version" }
tpipe-ollama = { module = "com.TTT:TPipe-Ollama", version.ref = "tpipe-version" }
tpipe-mcp = { module = "com.TTT:TPipe-MCP", version.ref = "tpipe-version" }
tpipe-defaults = { module = "com.TTT:TPipe-Defaults", version.ref = "tpipe-version" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin-version" }
kotlin-plugin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin-version" }
```

**build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    implementation(libs.tpipe.main)
    implementation(libs.tpipe.bedrock)
    implementation(libs.tpipe.ollama)
    implementation(libs.tpipe.mcp)
    implementation(libs.tpipe.defaults)
}
```

## Module Reference

| Module | Description | Operational Role |
| :--- | :--- | :--- |
| `TPipe` | Main library with core classes and interfaces. | **Required.** The foundation of the system. |
| `TPipe-Bedrock` | AWS Bedrock integration. | Enterprise-grade cloud models. |
| `TPipe-Ollama` | Local LLM execution via Ollama. | Local development and private hosting. |
| `TPipe-MCP` | Model Context Protocol bridge. | Connectivity to external tools and data. |
| `TPipe-Defaults` | Pre-configured components and utilities. | Accelerated configuration for common tasks. |

## Required JVM Configuration

TPipe requires specific JVM configuration to ensure compatibility with JVM 24 bytecode:

```kotlin
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))   // JDK 24 required
    }
}

kotlin {
    jvmToolchain(24)                                      // Kotlin toolchain JDK 24
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)                   // Target JVM 24 bytecode
    }
}
```

## Deployment & Native Support

TPipe is engineered for long-term scalability. While currently running on the JVM, it is designed for **GraalVM Native Image** compilation. This enables deployment as high-efficiency native libraries (.so, .dll) for:
- Edge and IoT devices
- Mobile platforms
- High-density, low-latency serverless environments

## Minimal Setup Example

```kotlin
import bedrockPipe.BedrockPipe

fun main() {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")

    val response = runBlocking { pipe.generateText("Hello, world!") }
    println(response)
}
```

## Environment Verification

Use this helper to verify your environment meets TPipe's industrial standards:

```kotlin
fun verifyEnvironment() {
    println("Java Version: ${System.getProperty("java.version")}")
    println("Kotlin Version: ${KotlinVersion.CURRENT}")

    // Verify JDK 24+
    val javaVersion = System.getProperty("java.version")
    val majorVersion = javaVersion.split(".")[0].toInt()

    require(majorVersion >= 24) {
        "TPipe requires JDK 24+, found: $javaVersion"
    }

    println("✓ Environment meets TPipe requirements")
}
```

## Common Troubleshooting

### Build System Compatibility
> [!CAUTION]
> **Issue**: Attempting to use Maven or Groovy Gradle.
> **Solution**: TPipe strictly requires **Kotlin Gradle DSL**. Convert your project to use `build.gradle.kts`.

### JDK Version Issues
> [!CAUTION]
> **Unsupported class file major version**: This indicates you are attempting to run TPipe on a JDK older than 24. Verify your environment with `java -version`.
```bash
# Check Java version
java -version
# Should show version 24 or higher
```

### Kotlin Version Mismatch
> [!IMPORTANT]
> **Issue**: Kotlin compilation errors.
> **Solution**: Use exactly Kotlin **2.2.0** as specified in TPipe.
```kotlin
plugins {
    kotlin("jvm") version "2.2.0"  // Must match TPipe's version
}
```

### Gradle Version Issues
> [!IMPORTANT]
> **Issue**: Gradle compatibility problems.
> **Solution**: Use Gradle **8.14.3** or higher.
```bash
# Update Gradle wrapper
./gradlew wrapper --gradle-version 8.14.3
```

## Next Steps

With the infrastructure in place, you can now open the valves and start the data flow.

**→ [First Steps](first-steps.md)** - Create your first pipe and pipeline.
