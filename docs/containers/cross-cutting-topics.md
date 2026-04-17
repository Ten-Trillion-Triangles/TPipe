# Cross-Cutting Topics

## Table of Contents
- [Tracing Support](#tracing-support)
- [KillSwitch Safety Mechanism](#killswitch-safety-mechanism)
- [P2P Integration Patterns](#p2p-integration-patterns)
- [Container State Management](#container-state-management)
- [Error Handling Patterns](#error-handling-patterns)
- [Testing Strategies](#testing-strategies)
- [Performance Considerations](#performance-considerations)
- [Implementation Status Summary](#implementation-status-summary)
- [Best Practices Based on Actual APIs](#best-practices-based-on-actual-apis)

This document covers patterns and considerations that apply across container types in TPipe, based on actual implementations.

## Tracing Support

### Actual Trace Events
These are the real trace events defined in `TraceEventType.kt`:

```kotlin
// Manifold Events
MANIFOLD_START, MANIFOLD_END, MANIFOLD_SUCCESS, MANIFOLD_FAILURE
MANAGER_DECISION, MANAGER_TASK_ANALYSIS, MANAGER_AGENT_SELECTION
TASK_PROGRESS_UPDATE, TASK_COMPLETION_CHECK, TASK_NEXT_STEPS
AGENT_DISPATCH, AGENT_RESPONSE, AGENT_REQUEST_VALIDATION
MANIFOLD_LOOP_ITERATION, MANIFOLD_TERMINATION_CHECK
CONVERSE_HISTORY_UPDATE

// Splitter Events  
SPLITTER_START, SPLITTER_END, SPLITTER_SUCCESS, SPLITTER_FAILURE
SPLITTER_CONTENT_DISTRIBUTION, SPLITTER_PIPELINE_DISPATCH
SPLITTER_PIPELINE_COMPLETION, SPLITTER_PIPELINE_CALLBACK
SPLITTER_COMPLETION_CALLBACK, SPLITTER_PARALLEL_START
SPLITTER_PARALLEL_AWAIT, SPLITTER_RESULT_COLLECTION

// P2P Events
P2P_REQUEST_START, P2P_REQUEST_SUCCESS, P2P_REQUEST_FAILURE
P2P_TRANSPORT_SEND, P2P_TRANSPORT_RECEIVE, PCP_CONTEXT_TRANSFER

// Junction Events
JUNCTION_START, JUNCTION_END, JUNCTION_SUCCESS, JUNCTION_FAILURE
JUNCTION_PAUSE, JUNCTION_RESUME
JUNCTION_ROUND_START, JUNCTION_ROUND_END
JUNCTION_VOTE_TALLY, JUNCTION_CONSENSUS_CHECK
JUNCTION_PARTICIPANT_DISPATCH, JUNCTION_PARTICIPANT_RESPONSE
JUNCTION_WORKFLOW_START, JUNCTION_WORKFLOW_END
JUNCTION_WORKFLOW_SUCCESS, JUNCTION_WORKFLOW_FAILURE
JUNCTION_PHASE_START, JUNCTION_PHASE_END
JUNCTION_HANDOFF
```

### Tracing Implementation Status

| Container | Tracing Support | Method |
|-----------|----------------|---------|
| Connector | ✅ Yes | `enableTracing(config)` |
| MultiConnector | ✅ Yes | `enableTracing(config)` |
| Splitter | ✅ Yes | `enableTracing(config)` |
| Manifold | ✅ Yes | Built-in tracing |
| DistributionGrid | ✅ Yes | Phase 8 runtime supports trace configuration, execution/discovery/hardening events, `clearTrace()`, `getTraceReport(...)`, and `getFailureAnalysis()` |
| Junction | ✅ Yes | `enableTracing(config)` |

### Tracing Events for KillSwitch

When a KillSwitch is configured and tracing is enabled, the following events are emitted:

- **`KILLSWITCH_CHECK`** - Emitted on every token check with current counts and limits
- **`KILLSWITCH_TRIPPED`** - Emitted when token limits are exceeded

These events appear across all container types and pipeline traces so developers can identify when and where token limits cut off execution.

### Tracing Events for Manifold Loop Limit

When a loop limit is configured on a Manifold and tracing is enabled, the following event is emitted:

- **`MANIFOLD_LOOP_LIMIT_EXCEEDED`** - Emitted when the iteration count reaches the configured limit

The `MANIFOLD_LOOP_ITERATION` event also includes `loopLimit` in its metadata when a limit is set.

### Enabling Tracing
```kotlin
// Containers with tracing support
connector.enableTracing(TraceConfig(
    detailLevel = TraceDetailLevel.DETAILED,
    includeContent = true
))

splitter.enableTracing(TraceConfig(enabled = true))

// Manifold has built-in tracing when tracingEnabled = true
```

## KillSwitch Safety Mechanism

KillSwitch provides token limit enforcement across all containers. See the **[KillSwitch Documentation](../core-concepts/killswitch.md)** for complete details.

### KillSwitch Status

| Container | KillSwitch Support | Loop Limit Support | Propagation |
|-----------|-------------------|---------------------|-------------|
| Connector | ✅ Yes | ❌ No | To branches |
| MultiConnector | ✅ Yes | ❌ No | To connectors |
| Splitter | ✅ Yes | ❌ No | To pipelines |
| Manifold | ✅ Yes | ✅ Yes (default 100) | To manager + workers |
| DistributionGrid | ✅ Yes | ❌ No | To router + workers |
| Junction | ✅ Yes | ❌ No | To moderator + participants |

### Quick Example

```kotlin
// Set on any container
manifold.killSwitch = KillSwitch(
    inputTokenLimit = 100_000,
    outputTokenLimit = 50_000
)

// Or via DSL
manifold {
    killSwitch(inputTokenLimit = 100_000, outputTokenLimit = 50_000)
    // ...
}
```

## P2P Integration Patterns

### Implemented P2P Containers

```kotlin
// Connector - Routes to last connection
class Connector : P2PInterface {
    override fun getPipelinesFromInterface(): List<Pipeline> {
        return branches.values.toList()
    }
    
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
        val pipeline = branches[lastConnection]
        return pipeline?.executeP2PRequest(request)
    }
}

// MultiConnector - Routes to first connector
class MultiConnector : P2PInterface {
    override fun getPipelinesFromInterface(): List<Pipeline> {
        return connectors.flatMap { it.getPipelinesFromInterface() }
    }
    
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
        return connectors.firstOrNull()?.executeP2PRequest(request)
    }
}

// Junction - Coordinates discussion rounds across P2P participants.
// The real implementation keeps moderator and participant bindings internally
// and exposes them through the P2PInterface contract.

// Splitter - Executes all pipelines, returns aggregated result
class Splitter : P2PInterface {
    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent {
        val jobs = executePipelines()
        jobs.awaitAll()
        
        val aggregated = MultimodalContent()
        for ((key, result) in results.contents) {
            aggregated.metadata[key] = result
        }
        return aggregated
    }
}
```

### Not Yet P2P-Functional Containers
- **DistributionGrid**: Implements `P2PInterface` and now supports local execution, explicit-peer remote handoff, trusted registry discovery, runtime hardening, and Kotlin DSL assembly

## Container State Management

### Available Reset Methods
```kotlin
// Splitter - Has result collection that needs clearing
splitter.results.flush() // Clear MultimodalCollection

// Connector - Set default path (public method)
connector.setDefaultPath("default-route") // Reset to default routing

// Note: Most containers don't expose explicit reset APIs
// Internal state like lastConnection, workingContentObject, loopIterationCount
// are private and cannot be directly accessed
```

### Resource Cleanup
```kotlin
// Manual cleanup for containers with accessible state
fun cleanup() {
    // Splitter - only public reset method
    splitter.results.flush()
    
    // Reset pipeline states if needed (these are accessible)
    childPipelines.forEach { pipeline ->
        pipeline.inputTokensSpent = 0
        pipeline.outputTokensSpent = 0
        pipeline.content = MultimodalContent()
    }
    
    // Note: Container internal state is private and cannot be reset directly
    // Containers may need to be recreated for full state reset
}
```

## Error Handling Patterns

### Connector Error Handling
Connector does **not** throw exceptions - it sets termination flags:

```kotlin
suspend fun execute(path: Any, content: MultimodalContent): MultimodalContent {
    try {
        val connection = branches[path]
        if (connection != null) {
            return connection.execute(content)
        }
        content.terminatePipeline = true // Set flag instead of throwing
        return content
    } catch (e: Exception) {
        content.terminatePipeline = true // Set flag on any error
        return content
    }
}

// Check termination flag instead of catching exceptions
val result = connector.execute("key", content)
if (result.terminatePipeline) {
    // Handle error case
}
```

### Splitter Async Error Handling
```kotlin
// Handle exceptions in parallel execution
val jobs = splitter.executePipelines()
jobs.forEach { job ->
    try {
        job.await()
    } catch (e: Exception) {
        // Handle individual pipeline failures
        logger.error("Pipeline failed", e)
    }
}
```

## Testing Strategies

### Unit Testing Actual APIs
```kotlin
class ConnectorTest {
    @Test
    fun `should set termination flag on invalid key`() = runTest {
        val connector = Connector()
        val content = MultimodalContent()
        
        val result = connector.execute("nonexistent", content)
        
        assertTrue(result.terminatePipeline)
    }
}

class SplitterTest {
    @Test
    fun `should collect results from parallel execution`() = runTest {
        val splitter = Splitter()
            .addContent("test", testContent)
            .addPipeline("test", testPipeline)
        
        val jobs = splitter.executePipelines()
        jobs.awaitAll()
        
        assertNotNull(splitter.results.contents["test"])
    }
}
```

### Integration Testing
```kotlin
class ManifoldIntegrationTest {
    @Test
    fun `should execute manager-worker loop`() = runTest {
        val manifold = Manifold()
            .setManagerPipeline(createTestManager())
            .autoTruncateContext()
        
        val task = MultimodalContent().addText("Test task")
        val result = manifold.execute(task)
        
        // Check that loop completed properly
        assertTrue(result.passPipeline || result.terminatePipeline)
    }
}
```

### Performance Testing
```kotlin
class SplitterPerformanceTest {
    @Test
    fun `should handle parallel execution efficiently`() = runTest {
        val splitter = Splitter()
        repeat(10) { i ->
            splitter.addContent("pipeline$i", testContent)
                .addPipeline("pipeline$i", createTestPipeline())
        }
        
        val startTime = System.currentTimeMillis()
        val jobs = splitter.executePipelines()
        jobs.awaitAll()
        val duration = System.currentTimeMillis() - startTime
        
        // Should complete in reasonable time despite 10 parallel pipelines
        assertThat(duration).isLessThan(5000)
    }
}
```

### Tracing Validation
```kotlin
class TracingTest {
    @Test
    fun `should emit expected trace events`() = runTest {
        val connector = Connector()
            .add("test", testPipeline)
            .enableTracing()
        
        connector.execute("test", testContent)
        
        val trace = connector.getTrace()
        assertThat(trace).contains("CONNECTOR_START")
        assertThat(trace).contains("CONNECTOR_ROUTE_SELECTION")
        assertThat(trace).contains("CONNECTOR_END")
    }
}
```

## Performance Considerations

### Memory Management
```kotlin
// Clear collections between runs
splitter.results.flush()

// Monitor token usage in long-running containers
val totalTokens = pipeline.inputTokensSpent + pipeline.outputTokensSpent
if (totalTokens > threshold) {
    // Implement compression or reset
}
```

### Concurrency Patterns
```kotlin
// Splitter uses proper coroutine scoping
return coroutineScope {
    val jobs = mutableListOf<Deferred<Unit>>()
    
    for ((key, activatorValue) in activatorKeys) {
        for (pipeline in activatorValue.pipelines) {
            val job = async {
                // Execute pipeline
            }
            jobs.add(job)
        }
    }
    
    jobs
}
```

## Implementation Status Summary

| Container | Status | Key Methods | P2P | Tracing | Loop Limit |
|-----------|--------|-------------|-----|---------|------------|
| **Connector** | ✅ Complete | `add()`, `execute()`, `setDefaultPath()` | ✅ | ✅ | ❌ |
| **MultiConnector** | ✅ Complete | `add()`, `setMode()`, `execute()` | ✅ | ❌ | ❌ |
| **Splitter** | ✅ Complete | `addContent()`, `addPipeline()`, `executePipelines()` | ✅ | ✅ | ❌ |
| **Manifold** | ✅ Complete | `execute()`, manager pipeline required, `setMaxLoopIterations()` | ✅ | ✅ | ✅ (default 100) |
| **DistributionGrid** | ✅ Phase 8 shipped | `setRouter()`, `setWorker()`, peer registration, discovery/membership APIs, execution entrypoints, hardening helpers, trace export, and `distributionGrid { ... }` | ✅ | ✅ | ❌ |
| **Junction** | ✅ Complete | `execute()`, `conductDiscussion()` | ✅ | ✅ | ❌ |

## Best Practices Based on Actual APIs

1. **Check termination flags** instead of catching exceptions (Connector pattern)
2. **Clear result collections** between runs (Splitter)
3. **Use proper P2P registration** with all required components
4. **Handle async exceptions** in parallel containers
5. **Enable tracing** where supported for debugging
6. **Validate container state** before execution
7. **Use meaningful keys** for routing and result identification
8. **Test both success and failure** scenarios
9. **Monitor resource usage** in long-running containers
10. **Follow actual API signatures** rather than assumed patterns

---

**Previous:** [← Junction](junction.md)
## Next Steps

- [Pipe Context Protocol Overview](../advanced-concepts/pipe-context-protocol.md) - Continue into the protocol layer used by containers.
