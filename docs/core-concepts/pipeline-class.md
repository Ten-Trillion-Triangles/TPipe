# Pipeline Class - Orchestrating Multiple Pipes

If a `Pipe` is a single valve, a `Pipeline` is the **Mainline**. It is the primary infrastructure that allows you to chain multiple pipes together, creating a sophisticated flow where the output of one stage is automatically processed as the input for the next.

Pipelines provide the framework for multi-stage AI reasoning, complex data transformations, and high-reliability validation loops.

```kotlin
class Pipeline : P2PInterface {
    // Pipeline orchestration logic
}
```

## Table of Contents
- [Building the Mainline](#building-the-mainline)
- [The Flow Process](#the-flow-process)
- [Advanced Mainline Features](#advanced-mainline-features)
- [Why use a Pipeline?](#why-use-a-pipeline)
- [Advanced Routing](#advanced-routing)
- [Next Steps](#next-steps)

---

## Building the Mainline

Constructing a pipeline involves adding pipes in the specific order you want data to flow. Like the `Pipe` class, `Pipeline` uses a fluent builder pattern to keep your infrastructure logic centralized and readable.

```kotlin
val mainline = Pipeline()
    .add(BedrockPipe().setPipeName("Researcher").setSystemPrompt("Extract technical data from..."))
    .add(BedrockPipe().setPipeName("Analyst").setSystemPrompt("Analyze the data for security risks..."))
    .add(BedrockPipe().setPipeName("Writer").setSystemPrompt("Summarize the final findings into Markdown."))
```

---

## The Flow Process

To start data moving through the pipeline, you call `execute()`. This initiates a sequence of events:

1.  **Entry Point**: Your initial input is pumped into the first pipe in the list.
2.  **Processing**: The first pipe generates an output based on its configuration (system prompt, temperature, etc.).
3.  **Handoff**: TPipe automatically passes that output as the input to the second pipe.
4.  **Completion**: The flow continues until the final pipe in the chain completes its work, at which point the result is returned to the caller.

---

## Advanced Mainline Features

### 1. Shared Memory Reservoirs
In large systems, every pipe often needs access to the same background information. Pipelines can share a single `ContextWindow` across all their constituent pipes, ensuring that knowledge discovered in Step 1 is available in Step 10.

```kotlin
val sharedMemory = ContextWindow()
val pipeline = Pipeline()
    .setContextWindow(sharedMemory) // Every valve in this mainline now draws from this reservoir
```

### 2. High-Resolution Callbacks
You can monitor the flow in real-time by adding a `PipelineCallback`. This is essential for telemetry, updating user interfaces, or logging the status of each valve as it processes the stream.

```kotlin
pipeline.setPipelineCallback { pipe, result ->
    println("Valve [${pipe.pipeName}] completed successfully.")
}
```

### 3. Pause and Resume: Flow Control
TPipe supports **Pause Points** within the mainline. This allows you to stop the flow and wait for a manual check (DITL) or a specific external condition before resuming.

```kotlin
val pipeline = Pipeline()
    .add(stepOne)
    .addPausePoint("manual-approval") // The mainline stops here
    .add(stepTwo)

val result = pipeline.execute("Initiate Project")

if (result.isPaused) {
    // The pipeline is now waiting. You can resume it with new data:
    pipeline.resume("Approved. Proceed to production.")
}
```

---

## Why use a Pipeline?

*   **Logic Decomposition**: Break massive reasoning tasks into smaller, specialized stages that are easier for models to handle accurately.
*   **Model Specialization**: Use a fast, cost-effective model (like Haiku) for initial data extraction and a powerful model (like Sonnet or Opus) for final synthesis.
*   **Observability**: Monitoring a multi-stage pipeline allows you to see exactly where a reasoning failure occurred, rather than trying to debug a single 5,000-word prompt.
*   **Error Isolation**: If one valve in the mainline fails, TPipe's error handling allows you to catch the fault and potentially reroute the flow before the entire system terminates.

---

## Advanced Routing

While a standard pipeline is linear, TPipe supports complex, non-linear infrastructure using **Orchestration Containers** like Connectors and Splitters. These allow you to branch the flow, run mainlines in parallel, or merge multiple results.

**→ [Container Overview](../containers/container-overview.md)** - Learn about branching, parallelization, and swarm orchestration.

## Next Steps

Now that you understand the mainline, learn how to provide your pipes with a memory reservoir.

**→ [Context Window - Memory Storage and Retrieval](context-window.md)** - Managing state and long-term memory.
