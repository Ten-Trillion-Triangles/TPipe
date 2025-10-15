# First Steps - Hello World with TPipe

This guide walks you through creating your first TPipe application.

## Prerequisites

- TPipe installed in your project ([Installation Guide](installation-and-setup.md))
- JDK 24+ configured
- AWS credentials configured

## Project Setup

### build.gradle.kts

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
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
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.TTT:TPipe:0.0.1")
    implementation("com.TTT:TPipe-Bedrock:0.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
```

## Hello World Examples

### 1. Basic Text Generation

Create `src/main/kotlin/Main.kt`:

```kotlin
import bedrockPipe.BedrockPipe

fun main() {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")
    
    val response = pipe.generateText("Hello, world!")
    println(response)
}
```

### 2. Cross-Region Model with ARN Binding

Some models require cross-region ARN binding to connect:

```kotlin
import bedrockPipe.BedrockPipe
import env.bedrockEnv

fun main() {
    // Required for cross-region models
    bedrockEnv.bindInferenceProfile(
        "anthropic.claude-3-5-sonnet-20241022-v2:0",
        "arn:aws:bedrock:us-west-2:123456789012:inference-profile/my-profile"
    )
    
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        .setRegion("us-east-1")
    
    val response = pipe.generateText("Hello from cross-region model!")
    println(response)
}
```

### 3. Streaming Response

```kotlin
import bedrockPipe.BedrockPipe

fun main() {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")
        .enableStreaming()
        .setStreamingCallback { chunk ->
            print(chunk)
        }
    
    pipe.generateText("Tell me a short story.")
    println("\nDone!")
}
```

## Running Your Application

```bash
./gradlew run
```

## Cross-Region ARN Binding

Models requiring ARN binding:
- `anthropic.claude-3-5-sonnet-20241022-v2:0`
- `anthropic.claude-3-5-haiku-20241022-v1:0`
- `amazon.nova-pro-v1:0`

Use `bedrockEnv.bindInferenceProfile(modelId, arnProfile)` before creating the pipe.

## Next Steps

Ready to dive deeper into TPipe? Continue with the core concepts:

**→ [Pipe Class - Core Concepts](../core-concepts/pipe-class.md)** - Understanding the fundamental Pipe class
