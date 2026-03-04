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
> Adjust the path relative to your project location. If TPipe is in a sibling directory, use `../TPipe/TPipe`.

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
    // Core library (Required)
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

## Module Reference

| Module | Purpose | Operational Role |
| :--- | :--- | :--- |
| `TPipe` | Core classes and flow logic. | **Required.** The foundation of the system. |
| `TPipe-Bedrock` | High-performance inference via AWS. | Enterprise-grade cloud models. |
| `TPipe-Ollama` | Local LLM execution. | Local development and private hosting. |
| `TPipe-MCP` | Model Context Protocol bridge. | Connectivity to external tools and data. |
| `TPipe-Defaults` | Sensible pre-sets and utilities. | Accelerated configuration for common tasks. |

## Deployment & Native Support

TPipe is engineered for long-term scalability. While currently running on the JVM, it is designed for **GraalVM Native Image** compilation. This enables deployment as high-efficiency native libraries (.so, .dll) for:
- Edge and IoT devices
- Mobile platforms
- High-density, low-latency serverless environments

## Common Troubleshooting

> [!CAUTION]
> **Unsupported class file major version**: This indicates you are attempting to run TPipe on a JDK older than 24. Verify your environment with `java -version`.

> [!IMPORTANT]
> **Build Failures**: Ensure you are using exactly Kotlin **2.2.0** and Gradle **8.14.3+**. Using mismatched versions often leads to subtle compilation errors in the DSL.

## Next Steps

With the infrastructure in place, you can now open the valves and start the data flow.

**→ [First Steps](first-steps.md)** - Create your first pipe and pipeline.
