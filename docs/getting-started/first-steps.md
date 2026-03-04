# First Steps - Opening the Mainline

Now that your project is configured with TPipe, it is time to open the valves and start the flow of intelligence. In this guide, we build a basic text generator, handle cross-region model routing, and set up a real-time streaming response.

## Prerequisites

- TPipe installed and configured ([See Setup Guide](installation-and-setup.md)).
- JDK 24+ configured.
- **AWS Credentials**: Ensure your environment variables or AWS profile are configured for Bedrock access.

## Project Setup: build.gradle.kts

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

---

## Basic Flow: Text Generation

The **Pipe** is the starting point for any TPipe application. Think of a Pipe as a single valve connecting your code to a model.

Create `src/main/kotlin/Main.kt`:

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() {
    // 1. Initialize the valve
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")
    
    // 2. Start the flow
    runBlocking {
        val response = pipe.generateText("Hello, world!")
        println("Model Response: ${response}")
    }
}
```

---

## Advanced Routing: Cross-Region Inference

High-performance models often require specific **Inference Profiles** (ARNs) to manage throughput across different regions. TPipe handles this through the `bedrockEnv` singleton.

```kotlin
import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking

fun main() {
    // Bind the model to its specific inference profile ARN
    bedrockEnv.bindInferenceProfile(
        "anthropic.claude-3-5-sonnet-20241022-v2:0",
        "arn:aws:bedrock:us-west-2:123456789012:inference-profile/my-profile"
    )
    
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        .setRegion("us-east-1") // The local entry region for your request
    
    runBlocking {
        val response = pipe.generateText("Requesting via cross-region mainline.")
        println(response)
    }
}
```

> [!NOTE]
> Models like **Claude 3.5 Sonnet** and **Amazon Nova Pro** typically require these ARN bindings for optimal performance and capacity access.

---

## Real-Time Output: Streaming

For lower perceived latency, you can stream model tokens as they are generated. TPipe's fluent builder makes it simple to attach a callback to the live stream.

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")
        .enableStreaming()
        .setStreamingCallback { chunk ->
            print(chunk) // Process tokens as they arrive
        }
    
    runBlocking {
        pipe.generateText("Tell me a short story.")
        println("\n[Flow Complete]")
    }
}
```

> [!TIP]
> For advanced streaming features including multiple callbacks, error handling, and concurrent execution, see the [Streaming Callbacks Guide](../core-concepts/streaming-callbacks.md).

---

## Helper: Terminal Output

Use the `streamOutputToTerminal` helper during development to see all tokens, including internal reasoning logs, directly in your console:

```kotlin
import bedrockPipe.BedrockPipe
import bedrockPipe.streamOutputToTerminal
import kotlinx.coroutines.runBlocking

fun main() {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")

    // Automatically handles primary response and reasoning tokens
    streamOutputToTerminal(pipe)

    runBlocking {
        pipe.generateText("Explain how streaming output is routed through reasoning pipes.")
    }
}
```

The helper above automatically enables streaming on the supplied `BedrockPipe` and any reasoning pipes it has been configured with, printing chunked tokens for both the primary response and any internal reasoning output.

If you are working with a [`Pipeline`](../core-concepts/pipeline-class.md), use `streamPipelineOutputToTerminal(pipeline)` instead so every pipe already registered on the pipeline participates in streaming output.

---

## Running the Flow

Execute your application using the standard Gradle task:

```bash
./gradlew run
```

---

## Next Steps

Now that you have opened the valves, learn how to precisely control the flow with the foundational **Pipe** class.

**→ [Pipe Class - Core Concepts](../core-concepts/pipe-class.md)** - Understanding the fundamental Pipe class.
