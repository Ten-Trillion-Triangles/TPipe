# Streaming Callbacks

> 💡 **Tip:** Streaming is like turning on the tap and watching the water flow in real-time. You can attach multiple hoses (callbacks) to route chunks wherever you need them.


TPipe supports streaming responses from AI models, allowing you to receive and process tokens as they arrive rather than waiting for the complete response. This enables real-time UI updates, progressive content display, and lower perceived latency.

## Overview

Streaming callbacks are functions that receive individual text chunks (tokens) as they arrive from the AI model. TPipe supports:

- **Single callback** - Legacy API for simple use cases
- **Multiple callbacks** - Register multiple independent callbacks for different purposes
- **Configurable execution** - Choose sequential or concurrent callback execution
- **Error isolation** - One callback's exception doesn't affect others
- **Backward compatibility** - Existing code continues to work unchanged

## Basic Streaming (Single Callback)

The simplest way to enable streaming is with a single callback:

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
    println("\nDone!")
}
```

### Suspending Callbacks

For async operations within callbacks, use suspending lambdas:

```kotlin
pipe.setStreamingCallback { chunk: String ->
    // Can use suspend functions here
    delay(10)
    logToDatabase(chunk)
}
```

## Multiple Streaming Callbacks

Register multiple independent callbacks to handle streaming chunks for different purposes without interference:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .streamingCallbacks {
        add { chunk -> print(chunk) }           // Print to console
        add { chunk -> logToFile(chunk) }       // Log to file
        add { chunk -> updateMetrics(chunk) }   // Update metrics
    }
```

### Sequential vs Concurrent Execution

Control how callbacks execute:

**Sequential** (default) - Callbacks execute one after another in registration order:

```kotlin
pipe.streamingCallbacks {
    add { chunk -> print(chunk) }
    add { chunk -> logToFile(chunk) }
    sequential()  // Execute in order
}
```

**Concurrent** - Callbacks execute in parallel:

```kotlin
pipe.streamingCallbacks {
    add { chunk -> print(chunk) }
    add { chunk -> logToFile(chunk) }
    concurrent()  // Execute in parallel
}
```

Use concurrent mode for better performance when callbacks are independent. Use sequential mode when order matters or in thread-limited environments.

### Error Handling

Callbacks are automatically isolated - one callback's exception doesn't affect others:

```kotlin
pipe.streamingCallbacks {
    add { chunk -> print(chunk) }
    add { chunk -> 
        // This might fail, but won't stop other callbacks
        riskyOperation(chunk)
    }
    add { chunk -> logToFile(chunk) }  // Still executes
    
    onError { exception, chunk ->
        println("Callback failed on chunk: $chunk")
        println("Error: ${exception.message}")
    }
}
```

## Advanced Usage

### Direct Manager Access

For dynamic callback management:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")

val manager = pipe.obtainStreamingCallbackManager()

// Add callbacks dynamically
val metricsCallback: suspend (String) -> Unit = { chunk -> 
    updateMetrics(chunk) 
}
manager.addCallback(metricsCallback)

// Remove callbacks later
manager.removeCallback(metricsCallback)

// Check callback state
if (manager.hasCallbacks()) {
    println("Active callbacks: ${manager.callbackCount()}")
}

// Clear all callbacks
manager.clearCallbacks()
```

### Mixed Legacy and New API

The legacy single-callback API works alongside the new multi-callback API:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setStreamingCallback { chunk -> print(chunk) }  // Legacy API
    .streamingCallbacks {                             // New API
        add { chunk -> logToFile(chunk) }
        add { chunk -> updateMetrics(chunk) }
    }

// All three callbacks execute
```

### Non-Suspending Callbacks

For simple synchronous callbacks, use the non-suspending overload:

```kotlin
val simpleCallback: (String) -> Unit = { chunk -> 
    print(chunk)  // No suspend needed
}

pipe.streamingCallbacks {
    add(simpleCallback)  // Automatically wrapped
}
```

## Helper Functions

### streamOutputToTerminal

Convenience function to enable streaming on pipes and their reasoning pipes:

```kotlin
import bedrockPipe.streamOutputToTerminal

val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")

streamOutputToTerminal(pipe)  // Enables streaming with console output

pipe.generateText("Explain quantum computing.")
```

For pipelines:

```kotlin
import bedrockPipe.streamPipelineOutputToTerminal

val pipeline = Pipeline()
    .add(pipe1)
    .add(pipe2)

streamPipelineOutputToTerminal(pipeline)  // Enables streaming on all pipes
```

## Streaming Callback Propagation

When a pipe has child pipes (validator, transformation, branch, or reasoning), registering a streaming callback on the parent pipe automatically propagates it to every descendant. This ensures that chunks emitted by any pipe in the tree flow through the same registered callback.

```kotlin
val parentPipe = GenericOpenAIPipe()
    .setStreamingCallback { chunk -> print(chunk) }  // propagates to all children

// These child pipes share the parent's streaming callback automatically
val reasoning = GenericOpenAIPipe()
    .setReasoning(ReasoningMethod.ExplicitCot)
val validator = GenericOpenAIPipe()

parentPipe.setReasoningPipe(reasoning)
parentPipe.setValidatorPipe(validator)

// All three pipes stream through the same callback
parentPipe.generateText("What is 2+2?")
```

Child pipes that are attached after the parent already has callbacks inherit those callbacks automatically. The propagation is cycle-safe — if a pipe tree contains a cycle due to a misconfigured builder, the visited set prevents infinite recursion.

Callback deduplication prevents double-firing: if the same lambda is registered both on the parent directly and again via a child's propagation path, it fires exactly once per chunk.

## Disabling Streaming

Disable streaming and clear all callbacks:

```kotlin
pipe.disableStreaming()
```

This clears both legacy single callbacks and all multi-callback manager callbacks.

## Best Practices

1. **Use concurrent mode** when callbacks are independent and performance matters
2. **Use sequential mode** when order matters or in thread-limited environments
3. **Always handle errors** with `onError()` to prevent silent failures
4. **Keep callbacks lightweight** - offload heavy processing to background threads
5. **Use suspending callbacks** for async operations (database writes, network calls)
6. **Test error isolation** - ensure one callback's failure doesn't break others

## Common Use Cases

### Real-Time UI Updates

```kotlin
pipe.streamingCallbacks {
    add { chunk -> 
        uiThread.post { textView.append(chunk) }
    }
    sequential()
}
```

### Logging and Metrics

```kotlin
pipe.streamingCallbacks {
    add { chunk -> print(chunk) }              // User feedback
    add { chunk -> logger.debug(chunk) }       // Debug logging
    add { chunk -> metrics.recordToken() }     // Token counting
    concurrent()  // Independent operations
}
```

### Multi-Destination Output

```kotlin
pipe.streamingCallbacks {
    add { chunk -> fileWriter.write(chunk) }   // Save to file
    add { chunk -> websocket.send(chunk) }     // Send to client
    add { chunk -> cache.append(chunk) }       // Cache response
    concurrent()
}
```

## Bedrock Streaming: Tool Use, Citations, and Guard Assessments

When `BedrockPipe.useConverseApi()` and `enableStreaming()` are both active, the AWS Converse stream delivers tool-use inputs, citation fragments, and inline guardrail content across multiple `ContentBlockDelta` events — each tagged with a `contentBlockIndex`. The pipe reassembles these per-block streams into typed artifacts, exposed through [Per-Call Metadata](../bedrock/getting-started.md#per-call-metadata) (`getLastCallMetadata()`) instead of through the streaming callback.

### Per-Block Reassembly

The Converse stream may interleave text, reasoning, tool-use, citations, and guard content across multiple content blocks. TPipe uses each block's `contentBlockIndex` as the key to keep each artifact's fragments separate during reassembly:

- `ContentBlockStart` seeds the accumulator for that block index (captures `toolUseId` and name for tool-use blocks).
- `ContentBlockDelta` appends `input` JSON fragments to the accumulator for its block index.
- `ContentBlockStop` finalizes the accumulator into a typed artifact and appends it to `BedrockCallMetadata`.

A single stream may contain any number of content blocks in any order — the block index keeps text deltas, tool-use input fragments, citation fragments, and guard content from different blocks from mixing with each other.

```kotlin
import aws.smithy.kotlin.runtime.content.Document
import bedrockPipe.BedrockCallMetadata
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .useConverseApi()
    .enableStreaming()
    .setStreamingCallback { chunk -> print(chunk) }
    .setTools(listOf(/* Claude tool definitions */))

runBlocking {
    pipe.init()
    pipe.execute("What is the warranty period for product X?")
}

val meta: BedrockCallMetadata? = pipe.getLastCallMetadata()

// 1. Reassembled tool-use blocks — one per tool-use block, with full input JSON
meta?.toolUse?.forEach { tool ->
    println("Tool: ${tool.name} (id=${tool.toolUseId})")
    println("Input: ${(tool.input as Document).toString()}")
}

// 2. Reassembled citations — title/source/location + concatenated sourceContent
meta?.citations?.forEach { citation ->
    println("Citation: ${citation.title} (source=${citation.source})")
    println("Location: ${citation.location?.documentChar?.toString()}")
    citation.sourceContent?.forEach { sc ->
        // CitationSourceContent.Text(TextCitationLink)
        // The .text fragment is accumulated across the block's deltas
        println("Snippet: ${sc.text?.toString()}")
    }
}

// 3. Inline guardrail assessments — one per GuardContent block
meta?.guardAssessments?.forEach { assessment ->
    println("Assessment topic: ${assessment.topicPolicy?.name}")
    println("Filters: ${assessment.contentPolicy?.filters?.size}")
}
```

### Streaming Metadata Fields

| Field | Source | Description |
|-------|--------|-------------|
| `latencyMs` | `ConverseStreamMetrics.latencyMs` | End-to-end stream latency |
| `stopReason` | `MessageStop.stopReason` | `end_turn`, `max_tokens`, `tool_use`, etc. |
| `cacheReadInputTokens` | `ContentBlockDelta` cache events | Prompt-cache read tokens |
| `cacheWriteInputTokens` | `ContentBlockDelta` cache events | Prompt-cache write tokens |
| `toolUse` | Per-block `ContentBlockStart/Delta/Stop` | Reassembled tool-use blocks |
| `citations` | Per-block `ContentBlockDelta` citation fragments | Reassembled citations |
| `guardAssessments` | Per-block `ContentBlockDelta` guard fragments | Inline guard assessments |

## API Reference

See [Pipe API Documentation](../api/pipe.md) for complete method signatures and details.

## Related Topics

- [Pipe Class - Core Concepts](pipe-class.md)
- [Tracing and Debugging](tracing-and-debugging.md)
- [Bedrock Getting Started](../bedrock/getting-started.md)
- [Bedrock Guardrails](../bedrock/guardrails.md)

## Next Steps

- [Pipeline Flow Control](pipeline-flow-control.md) - Continue with routing and control flow.
