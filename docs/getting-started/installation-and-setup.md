# Installation and Setup

## Prerequisites

### Required Versions

- **Java**: JDK 24 or higher (TPipe targets JVM 24 bytecode)
- **Kotlin**: Version 2.2.20 (as specified in build configuration)
- **Gradle**: Version 8.14.3 or higher
- **Build System**: **Kotlin Gradle DSL only** - TPipe does not support Maven, Groovy Gradle, or any other build systems

### Recommended JVM

- **GraalVM**: Recommended for optimal performance and future native compilation support
- **OpenJDK 24+**: Alternative if GraalVM is not available
- **Oracle JDK 24+**: Also supported

## TPipe Project Structure

TPipe is a multi-module Kotlin project:

```
TPipe/
├── TPipe/                 # Main library (core functionality)
├── TPipe-Bedrock/         # AWS Bedrock integration
├── TPipe-Ollama/          # Ollama integration  
├── TPipe-MCP/             # MCP integration
└── TPipe-Defaults/        # Default configurations
```

## Adding TPipe to Your Project

### Project Configuration (settings.gradle.kts)

First, configure your `settings.gradle.kts` to include TPipe as a composite build:

```kotlin
rootProject.name = "your-project-name"

// Include TPipe as a composite build
includeBuild("../TPipe/TPipe")
```

**Path Notes:**
- Adjust the path relative to your project location
- Example: If TPipe is in a sibling directory, use `"../TPipe/TPipe"`
- Example: If TPipe is in a subdirectory, use `"TPipe/TPipe"`

### Kotlin Gradle DSL (build.gradle.kts)

TPipe **only supports Kotlin Gradle DSL**. Add TPipe dependencies to your `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
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
    // Main TPipe library (required)
    implementation("com.TTT:TPipe:0.0.1")
    
    // Provider-specific modules (choose what you need)
    implementation("com.TTT:TPipe-Bedrock:0.0.1")  // AWS Bedrock support
    implementation("com.TTT:TPipe-Ollama:0.0.1")   // Ollama support
    implementation("com.TTT:TPipe-MCP:0.0.1")      // MCP support
    implementation("com.TTT:TPipe-Defaults:0.0.1") // Default configurations
    
    // Required dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
}
```

### Version Catalog (Optional)

For better dependency management, you can use a version catalog like TPipe does:

**gradle/libs.versions.toml:**
```toml
[versions]
kotlin-version = "2.2.20"
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

## Module Overview

| Module | Description | When to Include |
|--------|-------------|-----------------|
| `TPipe` | Main library with core classes and interfaces | Always required |
| `TPipe-Bedrock` | AWS Bedrock integration | When using AWS Bedrock models |
| `TPipe-Ollama` | Ollama integration | When using local Ollama models |
| `TPipe-MCP` | Model Context Protocol support | When using MCP servers |
| `TPipe-Defaults` | Default configurations and utilities | Optional, provides sensible defaults |

## Required JVM Configuration

TPipe requires specific JVM configuration:

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

## GraalVM Native Support (Planned)

TPipe is designed for future GraalVM Native compilation to enable deployment as native libraries (.so files) for:
- Edge devices
- IoT systems  
- Mobile platforms
- Systems where JARs cannot be used

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

Verify your environment meets TPipe requirements:

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

## Common Setup Issues

### Build System Compatibility

**Issue**: Trying to use Maven or Groovy Gradle
**Solution**: TPipe only supports Kotlin Gradle DSL. Convert your project to use `build.gradle.kts`

### JDK Version Issues

**Issue**: "Unsupported class file major version" errors
**Solution**: Ensure JDK 24+ is installed and configured

```bash
# Check Java version
java -version
# Should show version 24 or higher
```

### Kotlin Version Mismatch

**Issue**: Kotlin compilation errors
**Solution**: Use exactly Kotlin 2.2.20 as specified in TPipe

```kotlin
plugins {
    kotlin("jvm") version "2.2.20"  // Must match TPipe's version
}
```

### Gradle Version Issues

**Issue**: Gradle compatibility problems  
**Solution**: Use Gradle 8.14.3 or higher

```bash
# Update Gradle wrapper
./gradlew wrapper --gradle-version 8.14.3
```

## Next Steps

Now that you have TPipe installed and configured, continue with:

**→ [First Steps](first-steps.md)** - Create your first pipe and pipeline
