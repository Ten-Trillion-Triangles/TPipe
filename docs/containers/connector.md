# Connector

> 💡 **Tip:** The **Connector** is a conditional Valve. It tests the water flow against a condition and routes it to one pipe or the other—like a smart Y-joint.


## Table of Contents
- [Basic Usage](#basic-usage)
- [Key Methods](#key-methods)
- [Error Handling](#error-handling)
- [Connector Path Metadata](#connector-path-metadata)
- [P2P Integration](#p2p-integration)
- [Tracing Support](#tracing-support)
- [Complete Example](#complete-example)
- [Best Practices](#best-practices)

Connector provides key-based routing to different pipelines. Content is routed to a specific pipeline based on a routing key, enabling branching logic in your pipeline architecture.

## Basic Usage

```kotlin
val connector = Connector()
    .add("process", processingPipeline)
    .add("analyze", analysisPipeline)
    .add("summarize", summaryPipeline)

// Route content to specific pipeline
val result = connector.execute("process", content)
```

## Key Methods

### Pipeline Management
```kotlin
// Add pipeline with routing key
fun add(key: Any, pipeline: Pipeline): Connector

// Get pipeline by key
fun get(key: Any): Pipeline?

// Set default path for P2P requests
fun setDefaultPath(path: Any): Connector
```

### Execution
```kotlin
// Execute with explicit key
suspend fun execute(path: Any, content: MultimodalContent): MultimodalContent
```

## Error Handling

Connector does **not** throw exceptions on invalid paths. Instead, it sets `terminatePipeline = true`:

```kotlin
suspend fun execute(path: Any, content: MultimodalContent): MultimodalContent {
    try {
        val connection = branches[path]
        if (connection != null) {
            return connection.execute(content)
        }
        content.terminatePipeline = true
        return content
    } catch (e: Exception) {
        content.terminatePipeline = true
        return content
    }
}
```

Check the termination flag instead of catching exceptions:
```kotlin
val result = connector.execute("unknown-key", content)
if (result.terminatePipeline) {
    // Handle invalid routing key
}
```

## Connector Path Metadata

Content objects can carry routing information:

```kotlin
// Extension functions in Connector.kt
fun MultimodalContent.setConnectorPath(path: Any) {
    metadata["connectorPath"] = path
}

fun MultimodalContent.getConnectorPath(): Any? {
    return metadata["connectorPath"]
}
```

## P2P Integration

### P2P Request Handling
P2P requests route to the last connected pipeline:

```kotlin
override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
    val pipeline = branches[lastConnection]
    return pipeline?.executeP2PRequest(request)
}
```

### P2P Registration
```kotlin
fun registerAsP2PAgent() {
    // Set descriptor
    connector.setP2pDescription(P2PDescriptor(
        agentName = "document-processor",
        agentDescription = "Routes documents to type-specific processors",
        transport = P2PTransport(Transport.Tpipe, "document-processor"),
        requiresAuth = false,
        usesConverse = false,
        allowsAgentDuplication = false,
        allowsCustomContext = false,
        allowsCustomAgentJson = false,
        recordsInteractionContext = false,
        recordsPromptContent = false,
        allowsExternalContext = false,
        contextProtocol = ContextProtocol.none
    ))
    
    // Set requirements
    connector.setP2pRequirements(P2PRequirements(
        allowExternalConnections = true
    ))
    
    // Set transport
    connector.setP2pTransport(P2PTransport(Transport.Tpipe, "document-processor"))
    
    // Register with P2P system
    P2PRegistry.register(connector)
}
```

## Tracing Support

Enable tracing across all child pipelines:

```kotlin
connector.enableTracing(TraceConfig(
    detailLevel = TraceDetailLevel.DETAILED,
    includeContent = true
))

// Get trace from last executed pipeline
val trace = connector.getTrace(TraceFormat.HTML)
```

## Complete Example

```kotlin
class DocumentProcessor {
    private val connector = Connector()
        .add("pdf", pdfPipeline)
        .add("docx", docxPipeline) 
        .add("txt", textPipeline)
        .setDefaultPath("txt")
        .enableTracing()
    
    suspend fun processDocument(content: MultimodalContent, type: String): MultimodalContent {
        val result = connector.execute(type, content)
        
        if (result.terminatePipeline) {
            // Handle unsupported document type
            return handleUnsupportedType(content, type)
        }
        
        return result
    }
    
    private fun handleUnsupportedType(content: MultimodalContent, type: String): MultimodalContent {
        return MultimodalContent().apply {
            addText("Unsupported document type: $type")
            terminatePipeline = false // Reset flag
        }
    }
}
```

## Best Practices

- **Check termination flag** instead of catching exceptions
- **Set default paths** for P2P integration
- **Use meaningful keys** that describe the routing logic
- **Enable tracing** for debugging complex routing scenarios
- **Validate pipeline existence** before execution if needed
- **Handle unsupported routes** gracefully with fallback logic

---

**Previous:** [← Container Overview](container-overview.md) | **Next:** [MultiConnector →](multiconnector.md)
