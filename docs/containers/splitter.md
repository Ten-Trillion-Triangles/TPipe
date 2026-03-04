# Splitter - Parallel Processing

Splitter enables fan-out execution where content is distributed to multiple specialized pipelines that run in parallel. Results are automatically collected in a `MultimodalCollection` for subsequent aggregation and analysis.

Think of Splitter as the **Distribution Manifold** that splits a main water line into several specialized treatment filters at once.

## Table of Contents
- [Basic Usage](#basic-usage)
- [Key Methods](#key-methods)
- [The Results Collection (MultimodalCollection)](#the-results-collection-multimodalcollection)
- [Async Coordination and Parallel Flow](#async-coordination-and-parallel-flow)
- [Callback Sensors](#callback-sensors)
- [High-Resolution Tracing](#high-resolution-tracing)
- [P2P Integration](#p2p-integration)
- [Complete Example](#complete-example)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Basic Usage

To use a Splitter, you bind **Activation Keys** to both specialized content and the pipelines that should process them.

```kotlin
val manifold = Splitter()
    .addContent("sentiment", document)
    .addPipeline("sentiment", sentimentPipeline)
    .addContent("legal", document)
    .addPipeline("legal", legalAuditPipeline)
    .addContent("summary", document)
    .addPipeline("summary", summaryPipeline)

// Start all lines in parallel
val flows = manifold.executePipelines()
flows.forEach { it.await() } // Wait for all filters to finish

// Collect the clean results from the tank
val analysisResult = manifold.results.contents["sentiment"]
```

---

## Key Methods

### Infrastructure Management
*   **`addContent(key, content)`**: Primes a specific activation line with data.
*   **`addPipeline(key, pipeline)`**: Installs a specialized mainline onto an activation line.
*   **`removePipeline(pipeline)`** / **`removeKey(key)`**: Decommissions specific components or lines from the manifold.

### Execution Control
*   **`executePipelines()`**: Pushes data through all bound lines simultaneously. Returns a list of `Deferred` jobs for async coordination.

---

## The Results Collection (MultimodalCollection)

Results are stored in a thread-safe repository keyed by your activation strings.

```kotlin
@Serializable
data class MultimodalCollection(
    var contents: MutableMap<String, MultimodalContent> = ConcurrentHashMap()
) {
    fun flush() { contents.clear() }
}
```

---

## Async Coordination and Parallel Flow

Splitter uses Kotlin Coroutines to maximize infrastructure throughput.

```kotlin
// Option 1: Individual await
val jobs = splitter.executePipelines()
jobs.forEach { it.await() }

// Option 2: Unified awaitAll
jobs.awaitAll()
```

---

## Callback Sensors

You can install real-time monitoring on individual lines or the entire manifold.

*   **`onPipeLineFinish`**: Triggered when a single specialized line completes its flow.
*   **`onSplitterFinish`**: Triggered when the final line in the manifold has shut down.

```kotlin
splitter.onPipeLineFinish = { manifold, line, result ->
    println("Filter Line [${line.pipelineName}] is done.")
}
```

---

## High-Resolution Tracing

Splitter emits a comprehensive set of telemetry events, including `SPLITTER_CONTENT_DISTRIBUTION` and `SPLITTER_PARALLEL_START`.

### Independent Tracing
By default, the Splitter merges the trace data from all its child mainlines into one big map. If your swarm is massive, you can enable **Independent Tracing** to keep each line's gauge isolated.

```kotlin
splitter.enableTracing(TraceConfig(
    enabled = true,
    mergeSplitterTraces = false // Isolate the telemetry streams
))
```

---

## P2P Integration

Splitters are perfect for creating Analyst Swarms—P2P agents that can answer multiple questions about a project in parallel.

```kotlin
// Registering a Splitter as a multi-skilled agent
splitter.setP2pDescription(P2PDescriptor(
    agentName = "document-auditor",
    agentDescription = "Analyzes documents for sentiment, legal risk, and summaries in parallel."
))
P2PRegistry.register(splitter)
```

---

## Complete Example

```kotlin
class DocumentAnalysisSystem {
    private val splitter = Splitter().enableTracing()
    
    suspend fun analyze(doc: MultimodalContent): Map<String, String> {
        splitter.results.flush() // Clear the tank
        
        splitter.addContent("risks", doc).addPipeline("risks", riskPipeline)
        splitter.addContent("summary", doc).addPipeline("summary", summaryPipeline)
        
        splitter.executePipelines().awaitAll()
        
        return splitter.results.contents.mapValues { it.value.text }
    }
}
```

---

## Best Practices

*   **Flush the Tank**: Always call `results.flush()` before starting a new parallel run to ensure you don't have old residue in your findings.
*   **Unique Activation Keys**: Use descriptive keys like `pii_scan` or `language_detect` for easy result identification.
*   **Manage System Pressure**: Parallel execution consumes significant CPU/RAM. Avoid running dozens of massive pipelines simultaneously on small infrastructure.
*   **Handle Clogs**: Always combine Splitters with **Timeout and Retry** settings on the child pipelines to prevent a single hanging valve from blocking the entire `awaitAll()`.

---

## Next Steps

Now that you can run processes in parallel, learn about the Manager-Worker hierarchy.

**→ [Manifold - Multi-Agent Orchestration](manifold.md)** - Coordinating specialized workers.
