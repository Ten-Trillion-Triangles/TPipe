# Streaming Callbacks

TPipe supports streaming responses from AI models, allowing you to receive and process tokens as they arrive rather than waiting for the complete response. This enables real-time UI updates, progressive content display, and significantly lower perceived latency.

## Table of Contents
- [Basic Streaming (Single Callback)](#basic-streaming-single-callback)
- [Multiple Streaming Callbacks](#multiple-streaming-callbacks)
- [The Distribution Manifold](#the-distribution-manifold)
- [Error Isolation: Preventing Blowouts](#error-isolation-preventing-blowouts)
- [Helper Functions](#helper-functions)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Basic Streaming (Single Callback)

The simplest way to start the flow is with a single callback that receives individual text chunks.

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-west-2")
        .enableStreaming()
        .setStreamingCallback { chunk ->
            print(chunk)  // Print each token as it arrives
        }
    
    pipe.generateText("Tell me a short story.")
}
```

> [!TIP]
> **Suspending Callbacks**: If you need to perform database writes, network calls, or other async operations within your callback, use the suspending version of `setStreamingCallback`.

---

## Multiple Streaming Callbacks

For complex infrastructures, you may need to send the same stream to multiple destinations simultaneously (e.g., a UI, a log file, and a metrics collector).

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .streamingCallbacks {
        add { chunk -> print(chunk) }           // user feedback
        add { chunk -> logToFile(chunk) }       // audit trail
        add { chunk -> updateMetrics(chunk) }   // performance monitoring
    }
```

---

## The Distribution Manifold

TPipe's `StreamingCallbackManager` acts as a manifold, allowing you to configure exactly how your callbacks execute.

### Execution Modes
*   **Sequential (Default)**: Callbacks execute one after another in the order they were registered. Best if one callback depends on the state of another.
*   **Concurrent**: Callbacks execute in parallel. Best for performance when your destinations (like UI and logging) are independent.

```kotlin
pipe.streamingCallbacks {
    add { ... }
    add { ... }
    concurrent() // Open all lines in parallel
}
```

---

## Error Isolation: Preventing Blowouts

One of the most powerful features of TPipe's streaming system is **Error Isolation**. If your "Log to File" callback crashes due to a full disk, it will not stop the "Print to UI" callback from receiving its data. The manifold logs the failure and continues to all other healthy destinations.

```kotlin
pipe.streamingCallbacks {
    add { ... }
    onError { exception, chunk ->
        println("A manifold line failed on chunk: $chunk")
        println("Error: ${exception.message}")
    }
}
```

---

## Helper Functions

### streamOutputToTerminal
A convenience function to enable streaming and print both the primary response and any internal reasoning output to the console.

```kotlin
import bedrockPipe.streamOutputToTerminal

streamOutputToTerminal(myPipe)
myPipe.generateText("Analyze this architecture.")
```

### streamPipelineOutputToTerminal
Automatically opens the taps for every pipe in an entire mainline.

```kotlin
import bedrockPipe.streamPipelineOutputToTerminal

streamPipelineOutputToTerminal(myPipeline)
```

---

## Best Practices

*   **Keep it Lightweight**: Callbacks are executed on the same stream. If you need to perform heavy processing, offload it to a background thread to prevent "Clogging" the flow of tokens.
*   **Handle Errors**: Always use an `onError` block in the manifold to ensure you have visibility into specific line failures.
*   **Cleanup**: When you are finished, you can call `disableStreaming()` to shut the valves and clear all registered callbacks, preventing memory leaks.
*   **Manager Access**: For dynamic callback management, use `pipe.obtainStreamingCallbackManager()` to add or remove callbacks at runtime.

---

## Next Steps

Now that you can stream data in real-time, learn about how to monitor the healthy flow of your system.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
