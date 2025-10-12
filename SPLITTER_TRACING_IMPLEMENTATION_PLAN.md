# Splitter Tracing Implementation Plan

## Overview
Enhance the Splitter class with comprehensive tracing capabilities following the established TPipe tracing patterns. Currently, Splitter only enables tracing on contained pipelines but lacks container-level orchestration tracing.

## Current State Analysis

### Existing Implementation
- `tracePipelines: Boolean` - Flag to enable pipeline tracing
- `enableTracing()` - Enables tracing for bound pipelines only
- `init(config: TraceConfig)` - Propagates config to pipelines
- No Splitter-specific trace events
- No orchestration-level tracing

### Missing Capabilities
- Container lifecycle events (SPLITTER_START/END)
- Parallel execution coordination tracing
- Content distribution tracking
- Pipeline completion aggregation
- Callback execution tracing
- Failure isolation and recovery tracking

## Implementation Steps

### Step 1: Add Core Tracing Infrastructure

**File:** `src/main/kotlin/Pipeline/Splitter.kt`

**Add Properties:**
```kotlin
// Replace existing tracePipelines with full tracing infrastructure
private var tracingEnabled = false
private var traceConfig = TraceConfig()
private val splitterId = UUID.randomUUID().toString()
```

**Remove/Replace:**
- Remove `var tracePipelines = false`
- Update `enableTracing()` method signature

### Step 2: Add New TraceEventTypes

**File:** `src/main/kotlin/Debug/TraceEventType.kt`

**Add Events:**
```kotlin
// Splitter Orchestration Events
SPLITTER_START,
SPLITTER_END,
SPLITTER_SUCCESS,
SPLITTER_FAILURE,

// Content Distribution Events  
SPLITTER_CONTENT_DISTRIBUTION,
SPLITTER_PIPELINE_DISPATCH,
SPLITTER_PIPELINE_COMPLETION,

// Callback Events
SPLITTER_PIPELINE_CALLBACK,
SPLITTER_COMPLETION_CALLBACK,

// Parallel Execution Events
SPLITTER_PARALLEL_START,
SPLITTER_PARALLEL_AWAIT,
SPLITTER_RESULT_COLLECTION,
```

### Step 3: Update EventPriorityMapper

**File:** `src/main/kotlin/Debug/TraceEventPriority.kt`

**Add Mappings:**
```kotlin
// In getPriority() method, add:

// CRITICAL events
TraceEventType.SPLITTER_FAILURE -> TraceEventPriority.CRITICAL

// STANDARD events  
TraceEventType.SPLITTER_START,
TraceEventType.SPLITTER_END,
TraceEventType.SPLITTER_SUCCESS,
TraceEventType.SPLITTER_PIPELINE_COMPLETION -> TraceEventPriority.STANDARD

// DETAILED events
TraceEventType.SPLITTER_CONTENT_DISTRIBUTION,
TraceEventType.SPLITTER_PIPELINE_DISPATCH,
TraceEventType.SPLITTER_PIPELINE_CALLBACK,
TraceEventType.SPLITTER_COMPLETION_CALLBACK -> TraceEventPriority.DETAILED

// INTERNAL events
TraceEventType.SPLITTER_PARALLEL_START,
TraceEventType.SPLITTER_PARALLEL_AWAIT,
TraceEventType.SPLITTER_RESULT_COLLECTION -> TraceEventPriority.INTERNAL
```

### Step 4: Implement Core Tracing Methods

**File:** `src/main/kotlin/Pipeline/Splitter.kt`

**Add Methods:**
```kotlin
/**
 * Enables tracing for this Splitter with the specified configuration.
 * @param config The tracing configuration to use
 * @return This Splitter object for method chaining
 */
fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Splitter {
    this.tracingEnabled = true
    this.traceConfig = config
    PipeTracer.enable()
    return this
}

/**
 * Disables tracing for this Splitter.
 */
fun disableTracing(): Splitter {
    this.tracingEnabled = false
    return this
}

/**
 * Internal tracing method for Splitter events.
 */
private fun trace(
    eventType: TraceEventType,
    phase: TracePhase,
    content: MultimodalContent? = null,
    metadata: Map<String, Any> = emptyMap(),
    error: Throwable? = null
) {
    if (!tracingEnabled) return
    
    if (!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return
    
    val enhancedMetadata = buildSplitterMetadata(metadata, traceConfig.detailLevel, eventType, error)
    
    val event = TraceEvent(
        timestamp = System.currentTimeMillis(),
        pipeId = splitterId,
        pipeName = "Splitter-${activatorKeys.size}keys",
        eventType = eventType,
        phase = phase,
        content = if (shouldIncludeContent(traceConfig.detailLevel)) content else null,
        contextSnapshot = null, // Splitter doesn't have context
        metadata = if (traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
        error = error
    )
    
    PipeTracer.addEvent(splitterId, event)
}

/**
 * Builds Splitter-specific metadata based on verbosity level.
 */
private fun buildSplitterMetadata(
    baseMetadata: Map<String, Any>,
    detailLevel: TraceDetailLevel,
    eventType: TraceEventType,
    error: Throwable?
): Map<String, Any> {
    val metadata = baseMetadata.toMutableMap()
    
    when (detailLevel) {
        TraceDetailLevel.MINIMAL -> {
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
            }
        }
        
        TraceDetailLevel.NORMAL -> {
            metadata["splitterId"] = splitterId
            metadata["activatorKeyCount"] = activatorKeys.size
            metadata["totalPipelines"] = activatorKeys.values.sumOf { it.pipelines.size }
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
                metadata["errorType"] = error::class.simpleName ?: "Unknown"
            }
        }
        
        TraceDetailLevel.VERBOSE -> {
            metadata["splitterClass"] = this::class.qualifiedName ?: "Splitter"
            metadata["splitterId"] = splitterId
            metadata["activatorKeys"] = activatorKeys.keys.toList()
            metadata["pipelinesByKey"] = activatorKeys.mapValues { it.value.pipelines.size }
            metadata["hasOnPipelineFinish"] = (onPipeLineFinish != null).toString()
            metadata["hasOnSplitterFinish"] = (onSplitterFinish != null).toString()
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
                metadata["errorType"] = error::class.simpleName ?: "Unknown"
            }
        }
        
        TraceDetailLevel.DEBUG -> {
            metadata["splitterClass"] = this::class.qualifiedName ?: "Splitter"
            metadata["splitterId"] = splitterId
            metadata["activatorKeys"] = activatorKeys.keys.toList()
            metadata["pipelineDetails"] = activatorKeys.mapValues { entry ->
                entry.value.pipelines.map { "${it.javaClass.simpleName}:${it.pipeName}" }
            }
            metadata["resultCount"] = results.contents.size
            metadata["hasOnPipelineFinish"] = (onPipeLineFinish != null).toString()
            metadata["hasOnSplitterFinish"] = (onSplitterFinish != null).toString()
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
                metadata["errorType"] = error::class.simpleName ?: "Unknown"
                metadata["stackTrace"] = error.stackTraceToString()
            }
        }
    }
    
    return metadata
}

private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean {
    return when (detailLevel) {
        TraceDetailLevel.MINIMAL -> false
        TraceDetailLevel.NORMAL -> false
        TraceDetailLevel.VERBOSE -> traceConfig.includeContext
        TraceDetailLevel.DEBUG -> traceConfig.includeContext
    }
}

/**
 * Gets trace report for this Splitter in the specified format.
 */
fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String {
    return PipeTracer.exportTrace(splitterId, format)
}

/**
 * Gets failure analysis for this Splitter if tracing is enabled.
 */
fun getFailureAnalysis(): FailureAnalysis? {
    return if (tracingEnabled) PipeTracer.getFailureAnalysis(splitterId) else null
}

/**
 * Gets the unique trace ID for this Splitter.
 */
fun getTraceId(): String = splitterId
```

### Step 5: Update init() Method

**File:** `src/main/kotlin/Pipeline/Splitter.kt`

**Modify init() method:**
```kotlin
suspend fun init(config: TraceConfig = TraceConfig()) {
    // Initialize tracing if enabled
    if (tracingEnabled) {
        PipeTracer.startTrace(splitterId)
        trace(TraceEventType.SPLITTER_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "activatorKeyCount" to activatorKeys.size,
                  "totalPipelines" to activatorKeys.values.sumOf { it.pipelines.size }
              ))
    }
    
    // Iterate through all activator keys to initialize their bound pipelines
    for (it in activatorKeys) {
        val pipelines = it.value.pipelines
        
        // Trace content distribution
        if (tracingEnabled) {
            trace(TraceEventType.SPLITTER_CONTENT_DISTRIBUTION, TracePhase.INITIALIZATION,
                  it.value.content,
                  metadata = mapOf(
                      "activatorKey" to it.key,
                      "pipelineCount" to pipelines.size,
                      "contentSize" to (it.value.content?.text?.length ?: 0)
                  ))
        }
        
        for (pipeline in pipelines) {
            pipeline.pipelineContainer = this
            
            // Apply tracing configuration if tracing is enabled
            if (tracingEnabled) {
                pipeline.enableTracing(config)
                // Set splitter ID as pipeline ID for correlation
                for (pipe in pipeline.getPipes()) {
                    pipe.currentPipelineId = splitterId
                }
            }
            
            pipeline.init()
        }
    }
}
```

### Step 6: Update executePipelines() Method

**File:** `src/main/kotlin/Pipeline/Splitter.kt`

**Modify executePipelines() method:**
```kotlin
suspend fun executePipelines(): List<Job> {
    val jobs = mutableListOf<Job>()
    
    if (tracingEnabled) {
        trace(TraceEventType.SPLITTER_PARALLEL_START, TracePhase.EXECUTION,
              metadata = mapOf("totalJobs" to activatorKeys.values.sumOf { it.pipelines.size }))
    }
    
    for (activatorKey in activatorKeys) {
        val content = activatorKey.value.content
        val pipelines = activatorKey.value.pipelines
        
        for (pipeline in pipelines) {
            if (tracingEnabled) {
                trace(TraceEventType.SPLITTER_PIPELINE_DISPATCH, TracePhase.EXECUTION,
                      content,
                      metadata = mapOf(
                          "activatorKey" to activatorKey.key,
                          "pipelineName" to pipeline.pipelineName
                      ))
            }
            
            val job = CoroutineScope(Dispatchers.Default).launch {
                try {
                    val result = pipeline.execute(content!!.deepCopy())
                    
                    // Store result
                    results.contents[pipeline.pipelineName] = result
                    
                    if (tracingEnabled) {
                        trace(TraceEventType.SPLITTER_PIPELINE_COMPLETION, TracePhase.EXECUTION,
                              result,
                              metadata = mapOf(
                                  "pipelineName" to pipeline.pipelineName,
                                  "success" to true,
                                  "resultSize" to result.text.length
                              ))
                    }
                    
                    // Execute pipeline completion callback
                    onPipeLineFinish?.let { callback ->
                        if (tracingEnabled) {
                            trace(TraceEventType.SPLITTER_PIPELINE_CALLBACK, TracePhase.POST_PROCESSING,
                                  result,
                                  metadata = mapOf("pipelineName" to pipeline.pipelineName))
                        }
                        callback(this@Splitter, pipeline, result)
                    }
                    
                } catch (e: Exception) {
                    if (tracingEnabled) {
                        trace(TraceEventType.SPLITTER_FAILURE, TracePhase.EXECUTION,
                              content,
                              error = e,
                              metadata = mapOf(
                                  "pipelineName" to pipeline.pipelineName,
                                  "activatorKey" to activatorKey.key
                              ))
                    }
                }
            }
            jobs.add(job)
        }
    }
    
    // Launch completion monitoring job
    if (tracingEnabled) {
        CoroutineScope(Dispatchers.Default).launch {
            if (tracingEnabled) {
                trace(TraceEventType.SPLITTER_PARALLEL_AWAIT, TracePhase.POST_PROCESSING,
                      metadata = mapOf("jobCount" to jobs.size))
            }
            
            jobs.joinAll()
            
            if (tracingEnabled) {
                trace(TraceEventType.SPLITTER_RESULT_COLLECTION, TracePhase.POST_PROCESSING,
                      metadata = mapOf(
                          "resultCount" to results.contents.size,
                          "totalJobs" to jobs.size
                      ))
            }
            
            // Execute splitter completion callback
            onSplitterFinish?.let { callback ->
                if (tracingEnabled) {
                    trace(TraceEventType.SPLITTER_COMPLETION_CALLBACK, TracePhase.CLEANUP,
                          metadata = mapOf("resultCount" to results.contents.size))
                }
                callback(this@Splitter)
            }
            
            if (tracingEnabled) {
                val finalEventType = if (results.contents.isEmpty()) {
                    TraceEventType.SPLITTER_FAILURE
                } else {
                    TraceEventType.SPLITTER_SUCCESS
                }
                
                trace(finalEventType, TracePhase.CLEANUP,
                      metadata = mapOf(
                          "totalResults" to results.contents.size,
                          "successfulPipelines" to results.contents.size,
                          "totalPipelines" to jobs.size
                      ))
                
                trace(TraceEventType.SPLITTER_END, TracePhase.CLEANUP)
            }
        }
    }
    
    return jobs
}
```

### Step 7: Update Documentation

**File:** `src/main/kotlin/Pipeline/Splitter.kt`

**Update class documentation:**
```kotlin
/**
 * Splitter enables parallel execution of multiple pipelines with independent or shared input content.
 * Results are collected in a thread-safe manner with optional callbacks for pipeline and splitter completion.
 * 
 * Tracing Support:
 * - Container-level orchestration events (SPLITTER_START/END/SUCCESS/FAILURE)
 * - Content distribution tracking (SPLITTER_CONTENT_DISTRIBUTION)
 * - Pipeline dispatch and completion events (SPLITTER_PIPELINE_DISPATCH/COMPLETION)
 * - Callback execution tracing (SPLITTER_PIPELINE_CALLBACK/COMPLETION_CALLBACK)
 * - Parallel execution coordination (SPLITTER_PARALLEL_START/AWAIT)
 * 
 * Enable tracing with: splitter.enableTracing(config).init(config)
 */
```

### Step 8: Update Usage Documentation

**File:** `TPipe/Docs/Examples/Splitter-Usage.md`

**Add Enhanced Tracing Section:**
```markdown
## Enhanced Tracing Support

### Container-Level Tracing

Enable comprehensive Splitter orchestration tracing:

```kotlin
val traceConfig = TraceConfig()
    .setOutputPath("./traces")
    .setDetailLevel(TraceDetailLevel.VERBOSE)
    .enableConsoleOutput()

val splitter = Splitter()
    .enableTracing(traceConfig)

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init(traceConfig)
val jobs = splitter.executePipelines()
jobs.awaitAll()

// Get trace report
val report = splitter.getTraceReport(TraceFormat.HTML)
println(report)
```

### Trace Events Generated

- **SPLITTER_START/END**: Container lifecycle
- **SPLITTER_CONTENT_DISTRIBUTION**: Content distribution to pipelines
- **SPLITTER_PIPELINE_DISPATCH**: Individual pipeline execution start
- **SPLITTER_PIPELINE_COMPLETION**: Individual pipeline completion
- **SPLITTER_PARALLEL_START/AWAIT**: Parallel execution coordination
- **SPLITTER_PIPELINE_CALLBACK**: Pipeline completion callback execution
- **SPLITTER_COMPLETION_CALLBACK**: Splitter completion callback execution
- **SPLITTER_SUCCESS/FAILURE**: Final execution status

### Failure Analysis

```kotlin
val analysis = splitter.getFailureAnalysis()
analysis?.let {
    println("Failure point: ${it.failurePoint}")
    println("Suggested fixes: ${it.suggestedFixes}")
}
```
```

### Step 9: Add Unit Tests

**File:** `src/test/kotlin/Debug/SplitterTracingTest.kt`

**Create comprehensive test suite:**
```kotlin
class SplitterTracingTest {
    
    @Test
    fun `test splitter tracing lifecycle events`() {
        // Test SPLITTER_START, SPLITTER_END events
    }
    
    @Test
    fun `test content distribution tracing`() {
        // Test SPLITTER_CONTENT_DISTRIBUTION events
    }
    
    @Test
    fun `test pipeline dispatch and completion tracing`() {
        // Test SPLITTER_PIPELINE_DISPATCH, SPLITTER_PIPELINE_COMPLETION
    }
    
    @Test
    fun `test callback execution tracing`() {
        // Test SPLITTER_PIPELINE_CALLBACK, SPLITTER_COMPLETION_CALLBACK
    }
    
    @Test
    fun `test parallel execution tracing`() {
        // Test SPLITTER_PARALLEL_START, SPLITTER_PARALLEL_AWAIT
    }
    
    @Test
    fun `test failure tracing and analysis`() {
        // Test SPLITTER_FAILURE events and failure analysis
    }
    
    @Test
    fun `test verbosity level filtering`() {
        // Test event filtering at different detail levels
    }
    
    @Test
    fun `test trace report generation`() {
        // Test HTML, JSON, Markdown report generation
    }
}
```

## Implementation Checklist

- [ ] Add core tracing infrastructure to Splitter class
- [ ] Add new TraceEventTypes for Splitter events
- [ ] Update EventPriorityMapper with Splitter event priorities
- [ ] Implement trace() method and metadata building
- [ ] Update init() method with tracing
- [ ] Update executePipelines() method with comprehensive tracing
- [ ] Add tracing utility methods (getTraceReport, getFailureAnalysis, etc.)
- [ ] Update class and method documentation
- [ ] Update Splitter usage documentation
- [ ] Create comprehensive unit test suite
- [ ] Test integration with existing tracing infrastructure
- [ ] Validate trace report generation in all formats

## Testing Strategy

1. **Unit Tests**: Test individual tracing methods and event generation
2. **Integration Tests**: Test tracing with real pipelines and callbacks
3. **Performance Tests**: Ensure tracing doesn't impact parallel execution performance
4. **Report Tests**: Validate trace report generation and formatting
5. **Failure Tests**: Test failure scenarios and analysis generation

## Migration Notes

- Existing `tracePipelines` property will be replaced with `tracingEnabled`
- Existing `enableTracing()` method signature will change to accept `TraceConfig`
- All existing Splitter usage will continue to work (backward compatible)
- New tracing features are opt-in via `enableTracing(config)`

## Benefits

1. **Comprehensive Visibility**: Full orchestration-level tracing
2. **Debugging Support**: Detailed failure analysis and execution paths
3. **Performance Monitoring**: Parallel execution timing and coordination
4. **Consistency**: Follows established TPipe tracing patterns
5. **Flexibility**: Configurable verbosity levels and output formats
