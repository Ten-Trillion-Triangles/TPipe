# First Steps - Opening the Mainline

Now that your project is configured with TPipe, it is time to open the valves and start the flow of intelligence. In this guide, we build a basic text generator, handle cross-region model routing, and set up a real-time streaming response.

## Prerequisites

- TPipe installed and configured ([See Setup Guide](installation-and-setup.md)).
- **AWS Credentials**: Ensure your environment variables or AWS profile are configured for Bedrock access.

## Basic Flow: Text Generation

The **Pipe** is the starting point for any TPipe application. Think of a Pipe as a single valve connecting your code to a model.

Create `src/main/kotlin/Main.kt`:

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() {
    // 1. Initialize the valve
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        .setRegion("us-west-2")
    
    // 2. Start the flow
    runBlocking {
        val response = pipe.generateText("Hello, world!")
        println("Model Response: ${response.text}")
    }
}
```

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
        println(response.text)
    }
}
```

> [!NOTE]
> Models like **Claude 3.5 Sonnet** and **Amazon Nova Pro** typically require these ARN bindings for optimal performance and capacity access.

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
        pipe.generateText("Explain quantum computing like I am five.")
        println("\n[Flow Complete]")
    }
}
```

> [!TIP]
> For complex scenarios involving multiple models or reasoning outputs, see the [Streaming Callbacks Guide](../core-concepts/streaming-callbacks.md).

## Helper: Terminal Output

Use the `streamOutputToTerminal` helper during development to see all tokens, including internal reasoning logs, directly in your console:

```kotlin
import bedrockPipe.BedrockPipe
import bedrockPipe.streamOutputToTerminal
import kotlinx.coroutines.runBlocking

fun main() {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        .setRegion("us-west-2")

    // Automatically handles primary response and reasoning tokens
    streamOutputToTerminal(pipe)

    runBlocking {
        pipe.generateText("Analyze this architecture diagram.")
    }
}
```

## Running the Flow

Execute your application using the standard Gradle task:

```bash
./gradlew run
```

## Next Steps

Now that you have opened the valves, learn how to precisely control the flow with the foundational **Pipe** class.

**→ [Pipe Class - Core Concepts](../core-concepts/pipe-class.md)** - Understanding the fundamental Pipe class.
