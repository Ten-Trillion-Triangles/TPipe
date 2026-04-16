# KillSwitch - Token Limit Enforcement

> 💡 **Tip:** The **KillSwitch** is an emergency safety mechanism that halts agent execution when token consumption exceeds configured limits. It provides absolute termination that bypasses all retry policies and exception handlers.

## Table of Contents

- [Overview](#overview)
- [Core Concepts](#core-concepts)
- [API Reference](#api-reference)
- [Usage Patterns](#usage-patterns)
- [Container Support](#container-support)
- [DSL Builder Support](#dsl-builder-support)
- [Error Handling](#error-handling)
- [Best Practices](#best-practices)

## Overview

KillSwitch monitors input and output token usage across agent pipelines and immediately terminates execution when limits are exceeded. Unlike standard error handling, KillSwitch is absolute:

- **Bypasses all retry policies** - No retry, loop re-entry, or generic exception handlers intercept it
- **Propagates through the call chain** - `KillSwitchException` propagates as an uncaught exception
- **Works at all container levels** - Can be set on individual pipes, pipelines, or entire containers

## Core Concepts

### Token Limits

KillSwitch tracks two types of token consumption:

| Limit | Description | Use Case |
|-------|-------------|----------|
| `inputTokenLimit` | Input tokens (prompt + context) | Prevents runaway context accumulation |
| `outputTokenLimit` | Output tokens (response + reasoning) | Prevents excessive model output |

### KillSwitchContext

When tripped, the callback receives a `KillSwitchContext` with details:

```kotlin
data class KillSwitchContext(
    val p2pInterface: P2PInterface,      // The agent that tripped
    val inputTokensSpent: Int,          // Input tokens at trip point
    val outputTokensSpent: Int,           // Output tokens at trip point
    val elapsedMs: Long,                  // Time since execution started
    val reason: String,                  // "input_exceeded", "output_exceeded", or "input_and_output_exceeded"
    val accumulatedInputTokens: Int = inputTokensSpent,  // Total from root
    val accumulatedOutputTokens: Int = outputTokensSpent, // Total from root
    val depth: Int = 0                   // Nesting depth in agent hierarchy
)
```

### KillSwitchException

The exception that propagates when a kill switch trips:

```kotlin
class KillSwitchException(val context: KillSwitchContext) : RuntimeException(
    "KillSwitch tripped: input_exceeded | inputTokens=150000 | outputTokens=50000 | elapsedMs=2340"
)
```

## API Reference

### KillSwitch Constructor

```kotlin
KillSwitch(
    inputTokenLimit: Int? = null,        // Maximum input tokens, null = no limit
    outputTokenLimit: Int? = null,       // Maximum output tokens, null = no limit
    onTripped: (KillSwitchContext) -> Nothing = { ctx -> throw KillSwitchException(ctx) }
)
```

### Setting KillSwitch on Containers

All containers implement `P2PInterface` which exposes the `killSwitch` property:

```kotlin
// On Pipeline
pipeline.killSwitch = KillSwitch(inputTokenLimit = 100_000)

// On Manifold (propagates to manager + workers)
manifold.killSwitch = KillSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)

// On Junction (propagates to moderator + participants)
junction.killSwitch = KillSwitch(inputTokenLimit = 100_000)

// On DistributionGrid (propagates to router + workers)
distributionGrid.killSwitch = KillSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)
```

## Usage Patterns

### Basic Usage

```kotlin
val manifold = manifold {
    manager {
        pipeline { /* ... */ }
    }
    worker("analyzer") {
        pipeline { /* ... */ }
    }
    // Set token limits for entire manifold
    killSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)
}
```

### Custom Callback

```kotlin
val pipeline = Pipeline()
pipeline.killSwitch = KillSwitch(
    inputTokenLimit = 50_000,
    outputTokenLimit = 25_000,
    onTripped = { ctx ->
        logger.warn("KillSwitch tripped: ${ctx.reason} at ${ctx.elapsedMs}ms")
        // Custom handling before termination
        telemetry.reportKillSwitchEvent(ctx)
        // Must throw to terminate
        throw KillSwitchException(ctx)
    }
)
```

### Setting After Construction

```kotlin
val junction = junction {
    moderator("mod", moderatorPipeline)
    participant("worker", workerPipeline)
    rounds(3)
}

// Set kill switch after construction
junction.killSwitch = KillSwitch(inputTokenLimit = 200_000)
```

## Container Support

KillSwitch is supported on all TPipe containers with automatic propagation:

| Container | Propagation | Notes |
|-----------|------------|-------|
| `Pipeline` | N/A | Checks tokens after each pipe |
| `Pipe` | Via Pipeline | Pipe-level checking |
| `Connector` | To branches | Sequential token accumulation |
| `MultiConnector` | To connectors | Sequential and parallel modes |
| `Splitter` | To pipelines | Parallel token accumulation |
| `Manifold` | To manager + workers | Full hierarchy propagation |
| `Junction` | To moderator + participants | Full hierarchy propagation |
| `DistributionGrid` | To router + workers | Full hierarchy propagation |

### How Propagation Works

When you set `killSwitch` on a container, it automatically propagates to all child components:

```kotlin
manifold.killSwitch = KillSwitch(inputTokenLimit = 100_000)
// Automatically sets:
// - manifold.killSwitch = KillSwitch(...)
// - manifold.managerPipeline.killSwitch = KillSwitch(...)
// - manifold.workerPipelines[0].killSwitch = KillSwitch(...)
// - etc.
```

### Token Accumulation

Containers accumulate tokens from all child executions:

- **Sequential execution** (Connector, Junction): Tokens accumulated after each child completes
- **Parallel execution** (Splitter, MultiConnector): Each branch checked individually after completion

## DSL Builder Support

### Manifold DSL

```kotlin
manifold {
    killSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)
    // ... rest of configuration
}
```

### Junction DSL

```kotlin
junction {
    killSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)
    // ... rest of configuration
}
```

### DistributionGrid DSL

```kotlin
distributionGrid {
    killSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)
    // ... rest of configuration
}
```

## Error Handling

### KillSwitchException Must Propagate

KillSwitchException is intentionally an uncaught exception. **Do not catch it** in your exception handlers:

```kotlin
// WRONG - catching KillSwitchException defeats the purpose
try {
    manifold.execute(content)
} catch (e: KillSwitchException) {
    // This will NOT catch KillSwitchException in most execution paths
    // because it's designed to propagate
}

// CORRECT - let it propagate or handle at the top level
runBlocking {
    try {
        manifold.execute(content)
    } catch (e: KillSwitchException) {
        // Handle at top level - log, metrics, etc.
        logger.error("Agent terminated: ${e.context.reason}")
    }
}
```

### Custom Callbacks Must Throw

If you provide a custom `onTripped` callback, it must throw:

```kotlin
// WRONG - callback must throw to actually terminate
killSwitch(inputTokenLimit = 100_000, onTripped = { ctx ->
    println("Tripped!")
    // Missing throw - execution continues!
})

// CORRECT
killSwitch(inputTokenLimit = 100_000, onTripped = { ctx ->
    println("Tripped!")
    throw KillSwitchException(ctx)  // Must throw
})
```

## Best Practices

### 1. Set Limits Conservatively

Start with conservative limits and adjust based on observed usage:

```kotlin
// Conservative starting point
killSwitch(inputTokenLimit = 50_000, outputTokenLimit = 10_000)

// Adjust based on actual usage patterns
```

### 2. Use Input Limits for Context Safety

Input limits prevent runaway context accumulation which is the primary cause of runaway costs:

```kotlin
// Protect against context overflow
killSwitch(inputTokenLimit = 100_000)
```

### 3. Use Output Limits for Response Safety

Output limits prevent excessive model output:

```kotlin
// Protect against excessive responses
killSwitch(outputTokenLimit = 50_000)
```

### 4. Set at the Container Level

Setting kill switch at the highest relevant container ensures consistent enforcement:

```kotlin
// Instead of setting on every pipe:
manifold.killSwitch = KillSwitch(...)  // Set on manifold

// Not individual pipes:
pipe.killSwitch = KillSwitch(...)  // Avoid - harder to manage
```

### 5. Monitor via Callbacks

Use callbacks for observability without preventing termination:

```kotlin
killSwitch(
    inputTokenLimit = 100_000,
    onTripped = { ctx ->
        metrics.record("kill_switch_tripped", ctx.reason)
        logger.warn("KillSwitch: ${ctx.reason}")
        throw KillSwitchException(ctx)  // Always throw
    }
)
```

### 6. Test with Actual Limits

Test your kill switch configuration with realistic workloads:

```kotlin
@Test
fun killSwitchTripsAtLimit() = runBlocking {
    val manifold = manifold {
        killSwitch(inputTokenLimit = 1000)  // Small limit for testing
        // ...
    }

    assertFailsWith<KillSwitchException> {
        manifold.execute(largeInput)  // Should exceed 1000 tokens
    }
}
```
