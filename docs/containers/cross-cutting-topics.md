# Cross-Cutting Topics - Infrastructure Patterns

Across the different types of **Orchestration Containers** in TPipe, several foundational patterns remain consistent. Whether you are building a simple **Connector** or a massive **Manifold** project, understanding these cross-cutting infrastructure topics is essential for maintaining a high-performance, deterministic flow.

## Table of Contents
- [Tracing and Telemetry](#tracing-and-telemetry)
- [P2P Integration Patterns](#p2p-integration-patterns)
- [Container State Management](#container-state-management)
- [Error Handling: The Pressure Relief](#error-handling-the-pressure-relief)
- [Performance and Concurrency](#performance-and-concurrency)

---

## Tracing and Telemetry

In a complex facility, you need unified monitoring. TPipe containers propagate tracing configurations to all their child mainlines, ensuring that a single Gauge can capture the entire multi-stage process.

### Operational Events
Each container emits specialized telemetry events into the trace stream:
*   **Manifold**: `MANAGER_DECISION`, `AGENT_DISPATCH`, `TASK_PROGRESS_UPDATE`.
*   **Splitter**: `SPLITTER_CONTENT_DISTRIBUTION`, `SPLITTER_PARALLEL_START`.
*   **Connector**: `CONNECTOR_ROUTE_SELECTION`.

### Enabling the Gauges
```kotlin
// Propagates tracing to every pipe in every branch
connector.enableTracing(TraceConfig(detailLevel = TraceDetailLevel.DETAILED))
```

---

## P2P Integration Patterns

Containers are the primary way to build "Multi-Stage Agents." They allow you to hide complex internal Plumbing behind a simple P2P interface.

*   **Connector P2P**: Routes incoming requests to the most recently used connection or a predefined default path.
*   **Splitter P2P**: Fans out an incoming request to multiple pipelines and returns a compound metadata response containing all findings.
*   **MultiConnector P2P**: Typically routes to the first primary mainline in its switching yard.

---

## Container State Management

Unlike a single Pipe, Containers often hold Inventory or "History" that must be managed between runs.

*   **Flushing the Tank**: For containers like the **Splitter**, it is critical to call `results.flush()` before starting a new operation. This ensures that old data doesn't Contaminate the new result collection.
*   **Pipeline Resets**: If you are reusing pipelines within a container, remember that their internal token counters (`inputTokensSpent`) will continue to accumulate unless manually reset.

---

## Error Handling: The Pressure Relief

TPipe containers prioritize **Deterministic Fault Handling** over raw exceptions.

### The Termination Pattern
Instead of crashing your application, containers like the **Connector** will set `terminatePipeline = true` on the content object if a route is missing or a valve is shut.
*   **Best Practice**: Always check `result.shouldTerminate()` after calling a container's `execute` method to handle routing failures gracefully.

### Parallel Failure Management
In a **Splitter**, one line might Burst (fail) while others succeed. The Splitter uses standard Kotlin Coroutine error handling to ensure that a single failure is logged and surfaced without necessarily killing the other parallel processes.

---

## Performance and Concurrency

### Memory Control
In long-running agent projects (like a **Manifold** loop), the Water Level (token count) of the conversation history will rise.
*   **Strategy**: Use `autoTruncateContext()` on your container to ensure the supervisor doesn't exceed its context window after many turns of worker delegation.

### Concurrency
TPipe containers are built on **Kotlin Coroutines**. When you run a `Splitter`, it uses `async` blocks to ensure that the AI models are called in parallel, maximizing the Throughput of your infrastructure and reducing the total time your application waits for a response.

## Next Steps

Now that you understand the patterns shared by all containers, explore the specific blueprints for each one.

**→ [Container Overview](container-overview.md)** - An introduction to the orchestration catalog.
