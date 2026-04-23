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

## Running TPipe as an MCP Server

TPipe can act as an MCP server, exposing registered PCP functions as MCP tools to connected MCP clients (such as Claude Desktop, Cursor, and other MCP-enabled applications).

### Command-Line Modes

| Mode | Description |
|------|-------------|
| `--mcp-stdio-once` | Run MCP server once, processing a single stdio request |
| `--mcp-stdio-loop` | Run MCP server in loop, continuously processing stdio requests |
| `--mcp-http` | Run MCP server with HTTP transport on port 8090 |

### STDIO Mode

```bash
# Run once (process single request then exit)
java -jar TPipe-*.jar --mcp-stdio-once

# Run in loop (keep processing requests until EOF)
java -jar TPipe-*.jar --mcp-stdio-loop
```

### HTTP Mode

```bash
# Default (127.0.0.1:8090)
java -jar TPipe-*.jar --mcp-http

# Custom port
java -jar TPipe-*.jar --mcp-http --mcp-http-port=3000

# With authentication
java -jar TPipe-*.jar --mcp-http --mcp-http-auth-key=your-secret-key

# Custom bind address
java -jar TPipe-*.jar --mcp-http --mcp-http-bind=0.0.0.0
```

**Environment Variables (alternative to flags):**
| Variable | Description | Default |
|----------|-------------|---------|
| `TPIPE_MCP_HTTP_PORT` | HTTP port | 8090 |
| `TPIPE_MCP_HTTP_AUTH_KEY` | Bearer token auth | none |
| `TPIPE_MCP_HTTP_BIND` | Bind address | 127.0.0.1 |

### MCP Bridge Server Modes

The MCP bridge server accepts raw MCP JSON configuration via the `TPIPE_MCP_JSON` environment variable. **Important:** Bridge modes require the **TPipe-MCP standalone JAR**, not the main TPipe JAR. The main TPipe JAR's `--mcp-bridge-*` flags will throw `IllegalStateException`.

**Build the TPipe-MCP standalone JAR:**
```bash
cd TPipe-MCP
./gradlew :shadowJar
```

```bash
# Set MCP JSON configuration
export TPIPE_MCP_JSON='{"tools":[{"name":"my_tool","description":"A tool","inputSchema":{"type":"object"}}]}'

# Bridge stdio modes (TPipe-MCP JAR only)
java -jar TPipe-MCP-*-all.jar --mcp-bridge-stdio-once
java -jar TPipe-MCP-*-all.jar --mcp-bridge-stdio-loop

# Bridge HTTP mode (TPipe-MCP JAR only)
java -jar TPipe-MCP-*-all.jar --mcp-bridge-http --port 8080
```

**Bridge HTTP Options:**
| Option | Description | Default |
|--------|-------------|---------|
| `--port` | HTTP port | 8080 |
| `--auth-key` | Bearer token auth | none |
| `--bind-address` | Bind address | 127.0.0.1 |

**Note:** The main TPipe JAR (`TPipe-*.jar`) does NOT support bridge modes. Attempting to use `--mcp-bridge-*` flags with the main JAR will throw `IllegalStateException` with message "MCP bridge... modes require TPIPE_MCP_JSON environment variable. Use TPipe-MCP standalone jar instead."

### MCP Server Capabilities

When running as an MCP server, TPipe exposes:

| Capability | Description |
|------------|-------------|
| **Tools** | All PCP-registered functions via FunctionRegistry |
| **Resources** | StdioContextOptions exposed as file:// and http:// resources |
| **Prompts** | Functions prefixed with `prompt_` as MCP prompts |

### Path Security

Resource access is restricted by default:
- File access limited to `user.dir` and `/tmp`
- Configure via `TPIPE_MCP_ALLOWED_FILE_PATHS` (colon-separated on Unix, semicolon-separated on Windows)

### Next Steps

- [TPipe-MCP Package API](../api/tpipe-mcp-package.md) - Complete MCP server API reference

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
