# Pipeline Context Integration

TPipe enables pipes to share context within a pipeline through the pipeline's shared context window. This allows pipes to build upon each other's results and maintain context continuity throughout multi-stage processing.

## Table of Contents
- [What is Pipeline Context?](#what-is-pipeline-context)
- [Context Sources and Merging](#context-sources-and-merging)
- [The Flow Pattern: Pull and Update](#the-flow-pattern-pull-and-update)
- [Practical Example: The Research Mainline](#practical-example-the-research-mainline)
- [Advanced Merge Strategies](#advanced-merge-strategies)
- [Global Synchronization](#global-synchronization)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## What is Pipeline Context?

Pipeline context is a shared `ContextWindow` that belongs to the `Pipeline` instance itself.

```kotlin
class Pipeline {
    var context = ContextWindow() // The shared mainline reservoir
}
```

**Why use it?**
- ** continuity**: Stage 2 knows exactly what Stage 1 discovered.
- **Sharing**: Pass complex metadata (JSON, lists) between stages without polluting the main text output.
- **Cumulative Learning**: Build a comprehensive knowledge base as the pipeline progresses.

---

## Context Sources and Merging

TPipe supports multiple context sources that can be used together:
- **Pipeline context** (`pullPipelineContext()`): Shared within the current mainline.
- **Global context** (`pullGlobalContext()`): Persistent context from **ContextBank**.

**Non-Exclusive Behavior**: Both can be enabled simultaneously. Pipeline context provides the base, and global context is merged into it, adding additional reference information.

---

## The Flow Pattern: Pull and Update

To use the mainline context, pipes follow a simple Valve pattern:

### 1. Pulling (Reading from the Reservoir)
Use `pullPipelineContext()` to tell a pipe to load its memory from the parent pipeline before execution.

### 2. Updating (Writing back to the Reservoir)
Use `updatePipelineContextOnExit()` to tell a pipe to push its new discoveries back into the shared mainline context once it has finished its work.

---

## Practical Example: The Research Mainline

```kotlin
val pipeline = Pipeline()

// Valve 1: The Researcher
val researcher = BedrockPipe()
    .setPipeName("Researcher")
    .updatePipelineContextOnExit() // Save findings to the mainline
    .setTransformationFunction { content ->
        // Store structured facts in the reservoir
        content.context.addLoreBookEntry("findings", content.text, weight = 10)
        content
    }

// Valve 2: The Writer
val writer = BedrockPipe()
    .setPipeName("Writer")
    .pullPipelineContext() // Load findings from the mainline
    .autoInjectContext("Use the research findings to generate the report.")

pipeline.add(researcher).add(writer)
```

---

## Advanced Merge Strategies

When a pipe pushes data back into the mainline, you can control how LoreBook entries are handled:

*   **Emplacement (Default)**: New discoveries replace old ones with the same key. Best for general updates.
*   **Append Mode** (`enableAppendLoreBookScheme()`): New text is added to the end of existing context. Best for **Creative Writing** or **Event Logging**.
*   **Immutable Mode** (`enableImmutableLoreBook()`): The pipe can read from the mainline but is blocked from changing it. Best for protecting **Reference Data**.

---

## Global Synchronization

When a pipeline completes, it can automatically export its local reservoir into the global **ContextBank**, making its findings available to entirely different applications or future sessions.

```kotlin
val pipeline = Pipeline()
    .useGlobalContext("session_archive") // Pushes final results to the bank
```

> [!CAUTION]
> **Full Overwrite**: Using `useGlobalContext()` at the pipeline level performs a **Full Emplace**. It will completely replace the target page in the ContextBank with the pipeline's final context window.

---

## Best Practices

*   **Scrub before Pumping**: Use a transformation function to clean up and structure context data before it gets pushed to the mainline.
*   **Use Page Keys**: If your mainline is complex, use multiple page keys (**MiniBank**) to keep different types of discovered data in separate compartments.
*   **Context Validation**: Check for required context in a `preValidationFunction` to ensure the previous pipe actually produced the data you need.
*   **Reset Counters**: If reusing pipelines, remember that internal token counters (`inputTokensSpent`) will accumulate unless manually cleared.

---

## Next Steps

Now that you can share data across the mainline, learn how to manually intervene in the flow.

**→ [Developer-in-the-Loop Functions](developer-in-the-loop.md)** - Code-based validation and transformation.
