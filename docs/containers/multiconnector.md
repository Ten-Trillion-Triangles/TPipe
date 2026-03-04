# MultiConnector

> 💡 **Tip:** The **MultiConnector** is a complex manifold that checks multiple conditions, routing the water to the first pipe whose Valve opens.


## Table of Contents
- [Basic Usage](#basic-usage)
- [Execution Modes](#execution-modes)
- [Key Methods](#key-methods)
- [Execution Behavior](#execution-behavior)
- [P2P Integration](#p2p-integration)
- [Complete Example](#complete-example)
- [Important Notes](#important-notes)
- [Best Practices](#best-practices)

MultiConnector manages multiple Connector instances with different execution modes: sequential, parallel, or fallback. All execution methods require explicit routing paths.

## Basic Usage

```kotlin
val multiConnector = MultiConnector()
    .add(primaryConnector)
    .add(fallbackConnector)
    .setMode(ExecutionMode.SEQUENTIAL)

// Execute with routing paths
val results = multiConnector.execute(listOf("process", "analyze"), content)
```

## Execution Modes

```kotlin
enum class ExecutionMode { 
    SEQUENTIAL,  // Pass result from one connector to next
    PARALLEL,    // Execute all connectors simultaneously  
    FALLBACK     // Try connectors until one succeeds
}
```

## Key Methods

### Connector Management
```kotlin
// Add connector to the collection
fun add(connector: Connector): MultiConnector

// Set execution strategy
fun setMode(mode: ExecutionMode): MultiConnector
```

### Execution Methods
```kotlin
// Execute single content with routing paths
suspend fun execute(paths: List<Any>, content: MultimodalContent): List<MultimodalContent>

// Execute multiple content objects
suspend fun execute(paths: List<Any>, contentList: List<MultimodalContent>): List<MultimodalContent>

// Execute multiple content in parallel (load-balanced)
suspend fun executeParallel(contentList: List<MultimodalContent>, paths: List<Any>): List<MultimodalContent>
```

## Execution Behavior

### Sequential Mode
Content flows through connectors in order:
```kotlin
multiConnector.setMode(ExecutionMode.SEQUENTIAL)
val results = multiConnector.execute(listOf("step1", "step2"), content)
// content → connector1("step1") → connector2("step2") → result
```

### Parallel Mode  
All connectors execute simultaneously with load balancing:
```kotlin
multiConnector.setMode(ExecutionMode.PARALLEL)
val results = multiConnector.executeParallel(
    listOf(content1, content2, content3),
    listOf("path1", "path2", "path3")
)
// Round-robin distribution across connectors
```

### Fallback Mode
Try connectors until one succeeds:
```kotlin
multiConnector.setMode(ExecutionMode.FALLBACK)
val results = multiConnector.execute(listOf("primary", "backup"), content)
// Tries connector1("primary"), falls back to connector2("backup") if needed
```

## P2P Integration

MultiConnector exposes all child pipelines through P2P:

```kotlin
override fun getPipelinesFromInterface(): List<Pipeline> {
    return connectors.flatMap { it.getPipelinesFromInterface() }
}

override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
    // Routes to first connector in sequential mode
    return connectors.firstOrNull()?.executeP2PRequest(request)
}
```

## Complete Example

```kotlin
class DocumentProcessingSystem {
    private val primaryProcessor = Connector()
        .add("pdf", advancedPdfPipeline)
        .add("docx", advancedDocxPipeline)
    
    private val fallbackProcessor = Connector()
        .add("pdf", basicPdfPipeline)
        .add("docx", basicDocxPipeline)
    
    private val multiConnector = MultiConnector()
        .add(primaryProcessor)
        .add(fallbackProcessor)
        .setMode(ExecutionMode.FALLBACK)
    
    suspend fun processDocument(content: MultimodalContent, docType: String): MultimodalContent {
        val results = multiConnector.execute(listOf(docType), content)
        return results.firstOrNull() ?: MultimodalContent().apply {
            addText("Processing failed for document type: $docType")
        }
    }
    
    suspend fun processMultipleDocuments(
        documents: List<MultimodalContent>,
        types: List<String>
    ): List<MultimodalContent> {
        multiConnector.setMode(ExecutionMode.PARALLEL)
        return multiConnector.executeParallel(documents, types)
    }
    
    suspend fun processWithSequentialSteps(content: MultimodalContent): MultimodalContent {
        multiConnector.setMode(ExecutionMode.SEQUENTIAL)
        val results = multiConnector.execute(listOf("extract", "process", "format"), content)
        return results.lastOrNull() ?: content
    }
    
    fun setupP2PAgent() {
        multiConnector.setP2pDescription(P2PDescriptor(
            agentName = "multi-document-processor",
            agentDescription = "Advanced document processing with multiple strategies",
            transport = P2PTransport(Transport.Tpipe, "multi-document-processor"),
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
                P2PSkills("document-processing", "Process documents with multiple strategies"),
                P2PSkills("fallback-handling", "Automatic fallback on processing failures")
            )
        ))
        
        multiConnector.setP2pRequirements(P2PRequirements(
            allowExternalConnections = true
        ))
        
        multiConnector.setP2pTransport(
            P2PTransport(Transport.Tpipe, "multi-document-processor")
        )
        
        P2PRegistry.register(multiConnector)
    }
}
```

## Important Notes

- **No tracing support**: MultiConnector does not have `enableTracing()` method
- **Routing paths required**: All execution methods require explicit path lists
- **Load balancing**: Parallel mode distributes content round-robin across connectors
- **Error handling**: Fallback mode continues to next connector on failures
- **P2P routing**: P2P requests go to first connector only

## Best Practices

- **Choose appropriate execution mode** based on your use case
- **Provide correct path counts** matching your connector configurations
- **Handle empty results** from failed executions
- **Use fallback mode** for reliability with multiple processing strategies
- **Monitor performance** as parallel execution increases resource usage
- **Test failure scenarios** to ensure fallback logic works correctly

---

**Previous:** [← Connector](connector.md) | **Next:** [Splitter →](splitter.md)
