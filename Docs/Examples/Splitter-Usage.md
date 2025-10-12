# Splitter Usage Guide

The Splitter class enables parallel execution of multiple pipelines with independent or shared input content. Results are collected in a thread-safe manner with optional callbacks for pipeline and splitter completion.

## Basic Usage

### Simple Parallel Execution

```kotlin
val splitter = Splitter()

// Create pipelines
val pipeline1 = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Summarize the text")

val pipeline2 = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("Extract key points")

// Add content and bind pipelines to activation key
val content = MultimodalContent("Your input text here")
splitter.addContent("task1", content)
    .addPipeline("task1", pipeline1)
    .addPipeline("task1", pipeline2)

// Initialize and execute
splitter.init()
val jobs = splitter.executePipelines()

// Wait for all pipelines to complete
jobs.awaitAll()

// Access results
val result1 = splitter.results.contents["pipeline1"]
val result2 = splitter.results.contents["pipeline2"]
```

## Multiple Activation Keys

Execute different pipelines with different input content:

```kotlin
val splitter = Splitter()

// First task: Analyze document
val analysisPipeline = BedrockPipe()
    .setModel("anthropic.claude-3-opus-20240229-v1:0")
    .setSystemPrompt("Analyze this document")

val documentContent = MultimodalContent("Document text...")
splitter.addContent("analysis", documentContent)
    .addPipeline("analysis", analysisPipeline)

// Second task: Generate summary
val summaryPipeline = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Create a summary")

val summaryContent = MultimodalContent("Different text...")
splitter.addContent("summary", summaryContent)
    .addPipeline("summary", summaryPipeline)

// Execute all
splitter.init()
splitter.executePipelines().awaitAll()
```

## Using Callbacks

### Lambda vs Function Reference

Kotlin provides two ways to bind callbacks:

- **Lambda syntax** `{ params -> body }`: Creates an anonymous function inline. Best for simple, one-off logic that won't be reused.
- **Function reference** `::functionName`: References an existing named function. Best for complex logic, reusable callbacks, or when you need to test the callback independently.

### Pipeline Completion Callback

```kotlin
// Lambda syntax
val splitter = Splitter()
    .setOnPipelineFinish { splitter, pipeline, content ->
        println("Pipeline ${pipeline.pipelineName} completed")
        println("Result: ${content.text}")
    }

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init()
splitter.executePipelines()
```

```kotlin
// Function reference syntax
suspend fun handlePipelineCompletion(splitter: Splitter, pipeline: Pipeline, content: MultimodalContent)
{
    println("Pipeline ${pipeline.pipelineName} completed")
    println("Result: ${content.text}")
}

val splitter = Splitter()
    .setOnPipelineFinish(::handlePipelineCompletion)

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init()
splitter.executePipelines()
```

### Splitter Completion Callback

```kotlin
// Lambda syntax
val splitter = Splitter()
    .setOnSplitterFinish { splitter ->
        println("All pipelines completed")
        println("Total results: ${splitter.results.contents.size}")
        
        // Process all results
        splitter.results.contents.forEach { (name, content) ->
            println("$name: ${content.text}")
        }
    }

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init()
splitter.executePipelines()
```

```kotlin
// Function reference syntax
suspend fun handleSplitterCompletion(splitter: Splitter)
{
    println("All pipelines completed")
    println("Total results: ${splitter.results.contents.size}")
    
    splitter.results.contents.forEach { (name, content) ->
        println("$name: ${content.text}")
    }
}

val splitter = Splitter()
    .setOnSplitterFinish(::handleSplitterCompletion)

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init()
splitter.executePipelines()
```

## Tracing Support

Enable tracing for all pipelines:

```kotlin
val traceConfig = TraceConfig()
    .setOutputPath("./traces")
    .enableConsoleOutput()

val splitter = Splitter()
    .enableTracing()

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init(traceConfig)
splitter.executePipelines().awaitAll()
```

## Managing Pipelines

### Adding and Removing Pipelines

```kotlin
val splitter = Splitter()

// Add pipelines
splitter.addPipeline("key1", pipeline1)
    .addPipeline("key1", pipeline2)
    .addPipeline("key2", pipeline3)

// Remove specific pipeline from all keys
splitter.removePipeline(pipeline1)

// Remove entire activation key
splitter.removeKey("key2")
```

## Multimodal Content

Process images and documents in parallel:

```kotlin
val splitter = Splitter()

val imageAnalysisPipe = BedrockMultimodalPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Describe this image")

val textAnalysisPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("Extract metadata")

val multimodalContent = MultimodalContent(
    text = "What's in this image?",
    binaryContent = listOf(
        BinaryContent.Bytes(imageBytes, "image/jpeg")
    )
)

splitter.addContent("analysis", multimodalContent)
    .addPipeline("analysis", imageAnalysisPipe)
    .addPipeline("analysis", textAnalysisPipe)

splitter.init()
splitter.executePipelines().awaitAll()
```

## Result Collection Pattern

```kotlin
val splitter = Splitter()

// Setup pipelines...
splitter.init()
val jobs = splitter.executePipelines()

// Wait for specific pipeline
jobs[0].await()
val firstResult = splitter.results.contents.values.first()

// Wait for all
jobs.awaitAll()

// Access by pipeline name
val namedResult = splitter.results.contents["myPipeline"]

// Clear results for reuse
splitter.results.flush()
```

## Error Handling

Pipeline failures are handled gracefully:

```kotlin
val splitter = Splitter()
    .setOnPipelineFinish { splitter, pipeline, content ->
        if (content.text.contains("failed"))
        {
            println("Pipeline ${pipeline.pipelineName} failed")
            // Handle failure
        }
        else
        {
            println("Pipeline ${pipeline.pipelineName} succeeded")
        }
    }

splitter.addContent("task", content)
    .addPipeline("task", pipeline1)
    .addPipeline("task", pipeline2)

splitter.init()
splitter.executePipelines().awaitAll()
```

## Best Practices

1. **Pipeline Names**: Set pipeline names for easier result identification
```kotlin
pipeline1.pipelineName = "summarizer"
pipeline2.pipelineName = "analyzer"
```

2. **Content Isolation**: Each pipeline receives a deep copy of the content to prevent race conditions

3. **Callback Usage**: Use callbacks for fire-and-forget patterns, await for synchronous control

4. **Result Flushing**: Call `results.flush()` before reusing a splitter instance

5. **Tracing**: Enable tracing during development for debugging parallel execution

## Advanced Pattern: Dynamic Pipeline Addition

```kotlin
val splitter = Splitter()

// Add initial pipelines
splitter.addContent("base", content)

// Dynamically add pipelines based on conditions
if (needsAnalysis)
{
    splitter.addPipeline("base", analysisPipeline)
}

if (needsSummary)
{
    splitter.addPipeline("base", summaryPipeline)
}

splitter.init()
splitter.executePipelines().awaitAll()
```

## Notes

- Pipelines can only be bound to one activation key at a time
- Each pipeline receives an independent copy of the content
- Results are stored in a thread-safe ConcurrentHashMap
- Splitter completion callback is guaranteed to execute only once
- Pipeline failures don't affect other pipeline executions
