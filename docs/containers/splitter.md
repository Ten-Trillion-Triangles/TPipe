# Splitter

> 💡 **Tip:** The **Splitter** is a literal split in your plumbing. One Pipe comes in, and identical water (context) flows out into multiple parallel pipes concurrently.


## Table of Contents
- [Basic Usage](#basic-usage)
- [Key Methods](#key-methods)
- [MultimodalCollection](#multimodalcollection)
- [Async Coordination](#async-coordination)
- [Callback Functions](#callback-functions)
- [Tracing Events](#tracing-events)
- [P2P Integration](#p2p-integration)
- [Complete Example](#complete-example)
- [Best Practices](#best-practices)

Splitter enables fan-out execution where content is distributed to multiple pipelines that run in parallel. Results are collected in a `MultimodalCollection` for aggregation and processing.

## Basic Usage

```kotlin
val splitter = Splitter()
    .addContent("analyze", analysisContent)
    .addPipeline("analyze", analysisPipeline)
    .addContent("summarize", summaryContent)  
    .addPipeline("summarize", summaryPipeline)

val jobs = splitter.executePipelines()
jobs.forEach { it.await() }

// Access results
val analysisResult = splitter.results.contents["analyze"]
```

## Key Methods

### Content and Pipeline Management
```kotlin
// Add content for a specific activation key
fun addContent(key: Any, content: MultimodalContent): Splitter

// Add pipeline to an activation key
fun addPipeline(key: Any, pipeline: Pipeline): Splitter

// Remove pipeline from all keys
fun removePipeline(pipeline: Pipeline): Splitter

// Remove entire activation key
fun removeKey(key: Any): Splitter
```

### Execution
```kotlin
// Execute all bound pipelines in parallel
suspend fun executePipelines(): List<Deferred<Unit>>
```

### Tracing
```kotlin
// Enable tracing for splitter and child pipelines
fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Splitter

// Disable tracing
fun disableTracing(): Splitter

### Independent Tracing
By default, enabling tracing on a Splitter merges all child pipeline traces into the Splitter's trace stream. You can configure independent tracing to keep child traces separate.

```kotlin
// Child pipelines will NOT broadcast events to the Splitter trace
splitter.enableTracing(TraceConfig(
    enabled = true,
    mergeSplitterTraces = false 
))
```
```

## MultimodalCollection

Results are stored in a thread-safe collection:

```kotlin
@Serializable
data class MultimodalCollection(
    var contents: MutableMap<String, MultimodalContent> = ConcurrentHashMap()
) {
    fun flush() {
        contents.clear()
    }
}

// Access results after execution
val collection = splitter.results
for ((key, content) in collection.contents) {
    println("Pipeline $key produced: ${content.text}")
}
```

## Async Coordination

### Parallel Execution
```kotlin
// Start all pipelines asynchronously
val jobs = splitter.executePipelines()

// Wait for all to complete
jobs.forEach { it.await() }

// Or use awaitAll extension
jobs.awaitAll()
```

### Callback Functions

Splitter supports callback functions for pipeline completion:

```kotlin
// Called when each pipeline finishes
splitter.onPipeLineFinish = { splitter, pipeline, content ->
    println("Pipeline ${pipeline.pipelineName} completed")
    processResult(content)
}

// Called when all pipelines finish
splitter.onSplitterFinish = { splitter ->
    println("All pipelines completed")
    aggregateResults(splitter.results)
}
```

## Tracing Events

Splitter emits comprehensive tracing events:

```kotlin
splitter.enableTracing(TraceConfig(
    detailLevel = TraceDetailLevel.COMPREHENSIVE,
    includeContent = true
))

// Trace events include:
// - SPLITTER_START/END/SUCCESS/FAILURE
// - SPLITTER_CONTENT_DISTRIBUTION  
// - SPLITTER_PIPELINE_DISPATCH/COMPLETION
// - SPLITTER_PIPELINE_CALLBACK/COMPLETION_CALLBACK
// - SPLITTER_PARALLEL_START/AWAIT
```

## P2P Integration

Splitter exposes bound pipelines through P2P:

```kotlin
override fun getPipelinesFromInterface(): List<Pipeline> {
    // Access through private activatorKeys map
    return activatorKeys.values.flatMap { it.pipelines }
}

// Helper: Get all child pipelines directly
val pipelines = splitter.getAllChildPipelines()

// Helper: Get trace IDs of all child pipelines (useful for independent tracing)
val traceIds = splitter.getChildTraceIds()

override suspend fun executeLocal(content: MultimodalContent): MultimodalContent {
    val jobs = executePipelines()
    jobs.awaitAll()
    
    // Return aggregated results in metadata
    val aggregated = MultimodalContent()
    for ((key, result) in results.contents) {
        aggregated.metadata[key] = result
    }
    return aggregated
}
```

## Complete Example

```kotlin
class DocumentAnalysisSystem {
    private val splitter = Splitter()
    
    init {
        // Set up callbacks
        splitter.onPipeLineFinish = { _, pipeline, content ->
            logPipelineCompletion(pipeline.pipelineName, content)
        }
        
        splitter.onSplitterFinish = { splitter ->
            generateFinalReport(splitter.results)
        }
        
        splitter.enableTracing()
    }
    
    suspend fun analyzeDocument(document: MultimodalContent): AnalysisReport {
        // Clear previous results
        splitter.results.flush()
        
        // Bind different analysis pipelines with content
        splitter
            .addContent("sentiment", document)
            .addPipeline("sentiment", sentimentPipeline)
            .addContent("entities", document)
            .addPipeline("entities", entityPipeline)
            .addContent("topics", document)
            .addPipeline("topics", topicPipeline)
            .addContent("summary", document)
            .addPipeline("summary", summaryPipeline)
        
        // Execute all analyses in parallel
        val jobs = splitter.executePipelines()
        jobs.awaitAll()
        
        // Aggregate results
        return AnalysisReport(
            sentiment = splitter.results.contents["sentiment"]?.text ?: "",
            entities = extractEntities(splitter.results.contents["entities"]),
            topics = extractTopics(splitter.results.contents["topics"]),
            summary = splitter.results.contents["summary"]?.text ?: ""
        )
    }
    
    private suspend fun logPipelineCompletion(name: String, content: MultimodalContent) {
        println("Analysis '$name' completed with ${content.text.length} characters")
    }
    
    private suspend fun generateFinalReport(results: MultimodalCollection) {
        val report = buildString {
            appendLine("Document Analysis Complete")
            appendLine("Analyses performed: ${results.contents.keys.joinToString()}")
            appendLine("Total results: ${results.contents.size}")
        }
        println(report)
    }
    
    fun setupP2PAgent() {
        splitter.setP2pDescription(P2PDescriptor(
            agentName = "document-analyzer",
            agentDescription = "Parallel document analysis with multiple specialized pipelines",
            transport = P2PTransport(Transport.Tpipe, "document-analyzer"),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            agentSkills = mutableListOf(
                P2PSkills("parallel-analysis", "Run multiple analyses simultaneously"),
                P2PSkills("result-aggregation", "Collect and combine analysis results")
            )
        ))
        
        splitter.setP2pRequirements(P2PRequirements(
            allowExternalConnections = true
        ))
        
        splitter.setP2pTransport(P2PTransport(Transport.Tpipe, "document-analyzer"))
        
        P2PRegistry.register(splitter)
    }
}

data class AnalysisReport(
    val sentiment: String,
    val entities: List<String>,
    val topics: List<String>, 
    val summary: String
)
```

## Best Practices

- **Use meaningful activation keys** for easy result identification
- **Clear results between runs** using `results.flush()`
- **Implement callbacks** for real-time result processing
- **Handle async exceptions** in pipeline execution
- **Monitor resource usage** with many parallel pipelines
- **Enable tracing** for debugging complex parallel flows
- **Bind both content and pipelines** to activation keys before execution
- **Wait for all jobs** to complete before accessing results

---

**Previous:** [← MultiConnector](multiconnector.md) | **Next:** [Manifold →](manifold.md)
