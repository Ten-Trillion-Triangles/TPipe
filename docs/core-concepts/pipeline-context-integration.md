# Pipeline Context Integration - Sharing the Flow

In a complex industrial infrastructure, the pipes aren't isolated; they all draw from and contribute to the same **Mainline**. **Pipeline Context** is the shared memory reservoir that belongs to the pipeline itself. It allows multiple pipes in a chain to build upon each other's results, ensuring continuity and "cumulative learning" as the data flows from stage to stage.

Instead of passing massive strings between pipes, you can store structured knowledge in the mainline context for every subsequent pipe to access.

## What is Pipeline Context?

Every `Pipeline` instance has its own `ContextWindow` (or `MiniBank` if multiple pages are used).

```kotlin
class Pipeline {
    var context = ContextWindow() // The mainline reservoir
}
```

**Why use it?**
- ** continuity**: Stage 2 knows what Stage 1 discovered.
- **Sharing**: Pass complex metadata (JSON, lists) without polluting the main text output.
- **Efficiency**: Only "pump" the necessary context into each pipe's prompt.

---

## The Flow Pattern: Pull and Update

To use the mainline context, you use a simple "Valve" pattern:

### 1. Pulling (Reading from the Reservoir)
Use `pullPipelineContext()` to tell a pipe to load its memory from the parent pipeline.

### 2. Updating (Writing back to the Reservoir)
Use `updatePipelineContextOnExit()` to tell a pipe to push its new discoveries back into the shared mainline context once it's finished.

---

## Practical Example: The Research Mainline

```kotlin
val pipeline = Pipeline()

// Valve 1: The Researcher
val researcher = BedrockPipe()
    .setPipeName("Researcher")
    .updatePipelineContextOnExit() // Save findings to the mainline
    .setTransformationFunction { content ->
        // Store facts in the reservoir
        content.context.addLoreBookEntry("facts", content.text, weight = 10)
        content
    }

// Valve 2: The Writer
val writer = BedrockPipe()
    .setPipeName("Writer")
    .pullPipelineContext() // Load facts from the mainline
    .autoInjectContext("Use the facts gathered by the Researcher.")

pipeline.add(researcher).add(writer)
```

**How the data flows:**
1.  **Researcher** executes and finds information.
2.  The transformation function stores that info in the Pipe's local context.
3.  Because `updatePipelineContextOnExit` is set, that info is "pumped" into the `Pipeline.context`.
4.  **Writer** begins execution and, because `pullPipelineContext` is set, it draws the Researcher's facts into its own prompt.

---

## Advanced Merge Strategies

When a pipe pushes data back into the mainline, you can control how it's merged:

*   **Emplacement (Default)**: New discoveries replace old ones with the same key. Best for general updates.
*   **Append Mode**: New text is added to the end of existing context (e.g., `existing + " " + new`). Best for **Creative Writing** or **Event Logging**.
*   **Immutable Mode**: The pipe can read from the mainline but is blocked from changing it. Best for **Reference Data** that must remain pure.

```kotlin
pipe.enableAppendLoreBookScheme() // Switch to cumulative append mode
```

---

## Global Synchronization

When a pipeline is finished, it can automatically "empty" its local reservoir into the global **ContextBank**, making its findings available to entirely different applications or future sessions.

```kotlin
val pipeline = Pipeline()
    .useGlobalContext("session_history") // Pushes final results to the bank
```

> [!CAUTION]
> **Full Overwrite**: Using `useGlobalContext()` at the pipeline level performs a **Full Emplace**. It will completely replace the target page in the ContextBank with the pipeline's final context.

---

## Best Practices

*   **Scrub before Pumping**: Use a transformation function to clean up and structure the context data *before* it gets pushed to the mainline.
*   **Use Page Keys**: If your mainline is complex, use multiple page keys (MiniBank) to keep different types of discovered data (e.g., `technical_specs`, `user_mood`) in separate compartments.
*   **Validation**: Check the context in a `preValidationFunction` to ensure the previous pipe actually found the data you need before the current pipe starts an expensive generation.

---

## Next Steps

Now that you can share data across the mainline, learn how to manually intervene in the flow.

**→ [Developer-in-the-Loop Functions](developer-in-the-loop.md)** - Code-based validation and transformation.
