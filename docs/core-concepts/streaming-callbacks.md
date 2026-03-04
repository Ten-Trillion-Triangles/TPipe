# Streaming Callbacks - Real-Time Flow

In an industrial plumbing system, you don't always wait for a tank to fill before using the water; sometimes you need a continuous, real-time flow. **Streaming Callbacks** allow your application to receive and process model tokens (chunks of text) as they arrive, rather than waiting for the entire generation to complete.

This reduces perceived latency and allows for real-time UI updates, logging, and metrics gathering.

## Basic Streaming: The Single Tap

The simplest way to start the flow is with a single callback.

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")
        .enableStreaming()
        .setStreamingCallback { chunk ->
            print(chunk) // Print tokens as they arrive
        }
    
    pipe.generateText("Tell me a short story.")
}
```

> [!TIP]
> **Async Flow**: If you need to perform database writes or network calls within your callback, use the suspending version of `setStreamingCallback`.

---

## Multiple Callbacks: The Distribution Manifold

For more complex systems, you might need to send the same stream to multiple destinations (e.g., a UI, a log file, and a metrics collector). TPipe's **Streaming Distribution Manifold** handles this with automatic error isolation.

```kotlin
pipe.streamingCallbacks {
    add { chunk -> print(chunk) }           // user feedback
    add { chunk -> logToFile(chunk) }       // audit trail
    add { chunk -> updateMetrics(chunk) }   // performance monitoring

    // Choose how the manifold executes
    concurrent() // Run all callbacks in parallel (Fastest)
    
    // Handle individual line failures
    onError { exception, chunk ->
        println("A manifold line failed on: $chunk")
    }
}
```

### Execution Modes:
*   **Sequential (Default)**: Callbacks execute one by one in the order they were added. Best if one callback depends on another.
*   **Concurrent**: Callbacks execute in parallel. Best for performance when your destinations are independent.

---

## Error Isolation: Preventing Blowouts

One of the most powerful features of TPipe's streaming system is **Error Isolation**. If your "Log to File" callback crashes, it won't stop the "Print to UI" callback from receiving its data. The flow continues to all healthy destinations.

---

## Helpers: Opening the Mainline

If you just want to see the flow in your terminal during development, use the built-in helpers:

```kotlin
import bedrockPipe.streamOutputToTerminal

// Automatically enables streaming and prints both reasoning and response
streamOutputToTerminal(myPipe)
```

For entire mainlines:
```kotlin
import bedrockPipe.streamPipelineOutputToTerminal

// Opens the taps for every pipe in the pipeline
streamPipelineOutputToTerminal(myPipeline)
```

---

## Best Practices

*   **Keep it Lightweight**: Callbacks should be fast. If you need to do heavy processing (like complex AI analysis), offload it to a background thread or a different pipe.
*   **Handle Errors**: Always use `onError` in the manifold to ensure you're alerted when a specific streaming destination fails.
*   **Cleanup**: When you're done, you can call `disableStreaming()` to shut the valves and clear all registered callbacks.

---

## Next Steps

Now that you can stream data in real-time, learn about how to monitor the health of your infrastructure.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
