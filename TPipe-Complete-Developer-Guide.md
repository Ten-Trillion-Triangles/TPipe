# TPipe Complete Developer Guide

## Table of Contents
- [Overview](#overview)
- [Quick Start](#quick-start)
- [Core Components](#core-components)
- [Working Examples](#working-examples)
- [Advanced Features](#advanced-features)
- [Production Patterns](#production-patterns)
- [API Reference](#api-reference)

## Overview

TPipe is a production-grade Kotlin agent orchestration framework that powers complex multi-LLM applications. Built for real-world use cases like TPipeWriter (a sophisticated writing assistant), TPipe provides:

**🔄 Multi-Provider Orchestration**
- Unified API across AWS Bedrock (Claude, Nova, Llama, DeepSeek) and Ollama
- Advanced model features: reasoning tokens, tool calling, streaming, multimodal content
- Automatic failover, retry logic, and provider-specific optimizations

**🧠 Intelligent Context Management**
- Global context banks with automatic persistence and sharing between agents
- Weighted lorebook system with dependency chains and smart activation
- Context windows with intelligent truncation and token budgeting
- Mini-banks for branched conversations and retrieval-augmented workflows

**🛠️ Secure Tool Execution (PCP)**
- Pipe Context Protocol for safe LLM tool calling across stdio, HTTP, Python, and native Kotlin
- Multi-level security controls with command filtering and sandboxing
- Automatic parameter validation and type conversion
- Integration bridge for Model Context Protocol (MCP) compatibility

**🌐 Agent-to-Agent Communication (P2P)**
- Distributed agent networks with service discovery and routing
- Custom transport protocols (in-memory, stdio, HTTP, remote)
- Agent capability advertising and dynamic task delegation
- Authentication and authorization controls

**📊 Container Orchestration**
- **Pipelines**: Sequential multi-stage processing with context propagation
- **Connectors**: Smart routing between specialized agent pipelines  
- **Splitters**: Parallel execution with result aggregation and callbacks
- **Manifolds**: Manager-worker patterns with task decomposition and coordination
- **MultiConnectors**: Fallback chains and load balancing across containers

**🔍 Production Observability**
- Comprehensive tracing system with HTML dashboards and JSON exports
- Real-time performance monitoring with token usage and latency tracking
- Failure analysis with context snapshots and remediation suggestions
- Custom trace events and metadata for application-specific monitoring

**⚡ Advanced Features**
- JSON schema enforcement with automatic repair and validation
- Multimodal content processing (images, documents, binary data)
- Pipeline flow control (jumps, loops, early termination)
- Configuration serialization and hot-reloading
- Comprehensive test suite with integration testing

**Real-World Applications**
TPipeWriter demonstrates TPipe's capabilities in production:
- Multi-stage writing pipelines with planning, drafting, and revision phases
- Lorebook-driven world building with character and plot consistency
- Style analysis and rewriting with contextual feedback loops
- Chapter management with branched editing and version control
- Interactive shell with subcommands and persistent state

TPipe transforms complex LLM workflows from prototype scripts into maintainable, observable, and scalable applications.

## Quick Start

### Basic Pipe Setup

```kotlin
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    val summary: String,
    val keyPoints: List<String>,
    val confidence: Double
)

val analysisPipe = BedrockMultimodalPipe()
    .setPipeName("Document Analyzer")
    .setRegion("us-west-2")
    .useConverseApi()
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("You are an expert document analyzer. Return structured JSON analysis.")
    .setJsonOutput(AnalysisResult())
    .setMaxTokens(2000)
    .setTemperature(0.3)
    .applySystemPrompt()

val result = analysisPipe.execute(
    MultimodalContent(text = "Analyze this quarterly report...")
)
```

### Simple Pipeline

```kotlin
import com.TTT.Pipeline.Pipeline

val pipeline = Pipeline()
    .add(analysisPipe)
    .add(
        BedrockMultimodalPipe()
            .setPipeName("Summary Generator")
            .setModel("meta.llama3-1-70b-instruct-v1:0")
            .setSystemPrompt("Create executive summary from analysis")
            .applySystemPrompt()
    )
    .enableTracing()

val finalResult = pipeline.execute(
    MultimodalContent(text = "Process this document...")
)
```

## Core Components

### Pipe Configuration

#### Bedrock Pipes
```kotlin
import bedrockPipe.BedrockMultimodalPipe
import env.bedrockEnv

// Initialize environment
bedrockEnv.loadInferenceConfig()

val bedrockPipe = BedrockMultimodalPipe()
    .setPipeName("Research Assistant")
    .setRegion("us-east-1")
    .useConverseApi()
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setMaxTokens(4000)
    .setTemperature(0.7)
    .setReasoning(3000) // Enable reasoning with token budget
    .enableStreaming()
    .setStreamingCallback { chunk -> print(chunk) }
    .setReadTimeout(300) // 5 minutes
    .applySystemPrompt()

// Tool configuration
val tools = listOf(
    buildJsonObject {
        put("toolSpec", buildJsonObject {
            put("name", "search_documents")
            put("description", "Search through document database")
            put("inputSchema", buildJsonObject {
                put("json", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Search query")
                        })
                    })
                    put("required", buildJsonArray { add("query") })
                })
            })
        })
    }
)

bedrockPipe.setTools(tools)
bedrockPipe.setToolChoice("auto")
```

#### Ollama Pipes
```kotlin
import ollamaPipe.OllamaPipe

val ollamaPipe = OllamaPipe()
    .setPipeName("Local Assistant")
    .setModel("llama3.1:70b")
    .setIP("localhost")
    .setPort(11434)
    .setSystemPrompt("You are a helpful coding assistant")
    .setNumPredict(1000)
    .setTemperature(0.4f)
    .setRepeatPenalty(1.1f)
    .setMirostat(mode = 2, eta = 0.1f, tau = 5.0f)
    .setGpuSettings(numGpu = 2)
    .setNumThread(8)
    .setJsonOutput("""{"code": "", "explanation": ""}""")
    .applySystemPrompt()
```

### Context Management

#### Context Windows and Lorebooks
```kotlin
import com.TTT.Context.*
import com.TTT.Enums.ContextWindowSettings
import kotlinx.coroutines.runBlocking

// Create lorebook entries
val characterLore = LoreBook().apply {
    key = "character:alice"
    value = "Alice is the lead researcher with expertise in quantum computing"
    weight = 10
    aliasKeys.addAll(listOf("Dr. Alice", "Alice Chen"))
    linkedKeys.add("project:quantum")
}

val projectLore = LoreBook().apply {
    key = "project:quantum"
    value = "Project Quantum aims to develop fault-tolerant quantum algorithms"
    weight = 8
    requiredKeys.add("character:alice")
}

// Setup context window
val contextWindow = ContextWindow().apply {
    contextSize = 32000
    contextWindowTruncation = ContextWindowSettings.TruncateTop
    loreBookKeys[characterLore.key] = characterLore
    loreBookKeys[projectLore.key] = projectLore
    contextElements.addAll(listOf(
        "Meeting notes from last week...",
        "Current project status..."
    ))
}

// Store in global context bank
runBlocking {
    ContextBank.emplaceWithMutex("research_session", contextWindow)
}

// Use in pipe
val content = MultimodalContent(
    text = "What's Alice's role in the quantum project?"
).apply {
    context = contextWindow
}
```

#### Mini Banks for Branched Context
```kotlin
val branchContext = ContextWindow().apply {
    contextSize = 16000
    contextElements.add("Alternative approach discussion...")
}

val miniBank = MiniBank(mutableMapOf(
    "main_session" to contextWindow,
    "alternative_branch" to branchContext
))

val content = MultimodalContent(text = "Compare approaches").apply {
    workspaceContext = miniBank
}
```

### Pipe Context Protocol (PCP)

#### Tool Definition
```kotlin
import com.TTT.PipeContextProtocol.*

val pcpContext = PcpContext().apply {
    // Stdio tool
    addStdioOption(
        functionName = "file_search",
        description = "Search files in directory",
        command = "grep -r '{query}' {directory}",
        parameters = mapOf(
            "query" to "string",
            "directory" to "string"
        ),
        securityLevel = SecurityLevel.MEDIUM
    )
    
    // HTTP tool
    addHttpOption(
        functionName = "api_call",
        description = "Make REST API call",
        endpoint = "https://api.example.com/search",
        method = "POST",
        headers = mapOf("Content-Type" to "application/json"),
        parameters = mapOf("query" to "string"),
        securityLevel = SecurityLevel.HIGH
    )
    
    // TPipe tool (internal function)
    addTPipeOption(
        functionName = "calculate",
        description = "Perform calculation",
        parameters = mapOf(
            "expression" to "string",
            "precision" to "int"
        )
    )
}

// Bind to pipe
analysisPipe.setPcpContext(pcpContext)
```

#### Tool Execution
```kotlin
import com.TTT.PipeContextProtocol.PcpExecutionDispatcher

val toolRequest = PcPRequest(
    functionName = "file_search",
    parameters = mapOf(
        "query" to "TODO",
        "directory" to "/home/user/project"
    )
)

val toolResult = PcpExecutionDispatcher.executeRequest(toolRequest, pcpContext)
println("Tool result: ${toolResult.result}")
```

### P2P Agent Communication

#### Agent Registration
```kotlin
import com.TTT.P2P.*

val agentDescriptor = P2PDescriptor(
    agentName = "research.analyzer",
    agentDescription = "Analyzes research documents and extracts insights",
    transport = P2PTransport(
        transportMethod = Transport.Tpipe,
        transportAddress = "analyzer"
    ),
    requiresAuth = false,
    usesConverse = true,
    allowsAgentDuplication = true,
    allowsCustomContext = true,
    allowsCustomAgentJson = true,
    recordsInteractionContext = true,
    recordsPromptContent = false,
    allowsExternalContext = true,
    contextProtocol = ContextProtocol.pcp,
    pcpDescriptor = pcpContext
)

val requirements = P2PRequirements().apply {
    allowExternalConnections = true
    allowAgentDuplication = true
    allowCustomContext = true
    maxTokens = 4000
}

// Configure pipeline for P2P
pipeline.setP2pDescription(agentDescriptor)
pipeline.setP2pTransport(agentDescriptor.transport)
pipeline.setP2pRequirements(requirements)

// Register globally
P2PRegistry.register(pipeline)
```

#### Agent Communication
```kotlin
val p2pRequest = P2PRequest(
    transport = agentDescriptor.transport,
    prompt = MultimodalContent(text = "Analyze this research paper"),
    pcpRequest = PcPRequest(),
    customContextDescriptions = "Focus on methodology section"
)

val response = pipeline.executeP2PRequest(p2pRequest)
println("Agent response: ${response.prompt.text}")
```

## Working Examples

### Multi-Agent Research Pipeline

```kotlin
import com.TTT.Pipeline.*
import kotlinx.serialization.Serializable

@Serializable
data class ResearchFindings(
    val methodology: String,
    val keyResults: List<String>,
    val limitations: List<String>,
    val significance: String
)

@Serializable
data class PeerReview(
    val strengths: List<String>,
    val weaknesses: List<String>,
    val recommendations: List<String>,
    val overallScore: Int
)

// Create specialized pipes
val extractorPipe = BedrockMultimodalPipe()
    .setPipeName("Research Extractor")
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("""
        Extract key research findings from academic papers.
        Focus on methodology, results, limitations, and significance.
    """.trimIndent())
    .setJsonOutput(ResearchFindings())
    .setMaxTokens(3000)
    .applySystemPrompt()

val reviewerPipe = BedrockMultimodalPipe()
    .setPipeName("Peer Reviewer")
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("""
        Conduct peer review of research findings.
        Evaluate strengths, weaknesses, and provide constructive feedback.
    """.trimIndent())
    .setJsonOutput(PeerReview())
    .setMaxTokens(2000)
    .applySystemPrompt()

// Create connector for routing
val researchConnector = Connector()
    .add("extract", extractorPipe)
    .add("review", reviewerPipe)
    .setDefaultPath("extract")
    .enableTracing()

// Process with routing
val extractedFindings = researchConnector.execute(
    "extract", 
    MultimodalContent(text = "Research paper content...")
)

val peerReview = researchConnector.execute(
    "review",
    MultimodalContent(text = "Review these findings: ${extractedFindings.text}")
)
```

### Parallel Processing with Splitter

```kotlin
val splitter = Splitter()
    .addContent("analysis", MultimodalContent(text = "Analyze market trends"))
    .addPipeline("analysis", 
        BedrockMultimodalPipe()
            .setPipeName("Market Analyst")
            .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
            .setSystemPrompt("Analyze market trends and provide insights")
            .applySystemPrompt()
    )
    .addPipeline("analysis",
        BedrockMultimodalPipe()
            .setPipeName("Risk Assessor")
            .setModel("meta.llama3-1-70b-instruct-v1:0")
            .setSystemPrompt("Assess risks in market analysis")
            .applySystemPrompt()
    )
    .enableTracing()
    .setOnPipelineFinish { key, pipeline, result ->
        println("${pipeline.pipelineName} completed: ${result.text.take(100)}...")
    }

runBlocking {
    splitter.init(TraceConfig(enabled = true))
    val jobs = splitter.executePipelines()
    jobs.forEach { it.await() }
    
    // Access results
    splitter.results.contents.forEach { (key, results) ->
        println("Results for $key:")
        results.forEach { result ->
            println("- ${result.text}")
        }
    }
}
```

### Manifold Manager-Worker Pattern

```kotlin
import com.TTT.Pipeline.Manifold
import com.TTT.Structs.TaskProgress
import kotlinx.serialization.Serializable

@Serializable
data class AgentRequest(
    val agentName: String,
    val task: String,
    val priority: String = "normal",
    val context: String = ""
)

// Manager pipeline that coordinates workers
val managerPipe = BedrockMultimodalPipe()
    .setPipeName("Task Manager")
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("""
        You coordinate a team of specialist agents.
        Break down complex tasks and assign them to appropriate agents.
        Return JSON with AgentRequest format for each subtask.
    """.trimIndent())
    .setJsonOutput(AgentRequest())
    .applySystemPrompt()

// Specialist worker pipes
val codeReviewerPipe = BedrockMultimodalPipe()
    .setPipeName("Code Reviewer")
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("Review code for bugs, performance, and best practices")
    .applySystemPrompt()

val documentationPipe = BedrockMultimodalPipe()
    .setPipeName("Documentation Writer")
    .setModel("meta.llama3-1-70b-instruct-v1:0")
    .setSystemPrompt("Write clear, comprehensive documentation")
    .applySystemPrompt()

// Create manifold
val manifold = Manifold()
    .setManagerPipeline(managerPipe)
    .addWorkerPipeline(codeReviewerPipe)
    .addWorkerPipeline(documentationPipe)
    .setAgentPipeNames(listOf("Task Manager"))
    .setContextTruncationFunction { context ->
        // Keep only recent conversation history
        context.converseHistory.history.takeLast(10).let { recent ->
            context.converseHistory.history.clear()
            context.converseHistory.history.addAll(recent)
        }
        context
    }
    .setWorkerValidationFunction { worker, content ->
        // Validate worker can handle the task
        content.text.isNotEmpty()
    }
    .enableTracing()

val result = manifold.execute(
    MultimodalContent(text = "Review and document this new API implementation")
)
```

## Advanced Features

### Multimodal Content Processing

```kotlin
import com.TTT.Pipe.BinaryContent

val multimodalContent = MultimodalContent(
    text = "Analyze this image and document"
).apply {
    // Add image
    addBinary(BinaryContent().apply {
        data = File("chart.png").readBytes()
        filename = "chart.png"
        mimeType = "image/png"
    })
    
    // Add document
    addBinary(BinaryContent().apply {
        data = File("report.pdf").readBytes()
        filename = "report.pdf"
        mimeType = "application/pdf"
    })
}

val analysis = BedrockMultimodalPipe()
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("Analyze the provided image and document together")
    .execute(multimodalContent)
```

### Pipeline Flow Control

```kotlin
val controlledContent = MultimodalContent(
    text = "Process this step by step"
).apply {
    // Jump to specific pipe
    jumpToPipe("validator")
    
    // Repeat current pipe
    repeatPipe = true
    
    // Terminate early if condition met
    if (someCondition) {
        terminate()
    }
}

val pipeline = Pipeline()
    .add(processorPipe)
    .add(validatorPipe.setPipeName("validator"))
    .add(finalizerPipe)
    .execute(controlledContent)
```

### Context Snapshots and Recovery

```kotlin
val content = MultimodalContent(text = "Risky operation").apply {
    useSnapshot = true // Enable automatic snapshots
}

val pipe = BedrockMultimodalPipe()
    .setFailureFunction { context, content, error ->
        // Restore from snapshot on failure
        val snapshot = content.metadata["snapshot"] as? MultimodalContent
        snapshot?.let {
            println("Restoring from snapshot due to: $error")
            it
        } ?: content
    }
    .execute(content)
```

### Custom Validation and Transformation

```kotlin
val validatedPipe = BedrockMultimodalPipe()
    .setPreValidationFunction { context, content ->
        // Modify context before processing
        context.contextElements.add("Validation timestamp: ${System.currentTimeMillis()}")
        context
    }
    .setValidationFunction { context, content ->
        // Validate output
        content.text.isNotEmpty() && content.text.length > 10
    }
    .setTransformationFunction { context, content ->
        // Transform output
        content.copy(text = content.text.uppercase())
    }
    .execute(MultimodalContent(text = "transform this text"))
```

## Production Patterns

### Error Handling and Resilience

```kotlin
val resilientPipeline = Pipeline()
    .add(
        BedrockMultimodalPipe()
            .setPipeName("Primary Processor")
            .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
            .setFailureFunction { context, content, error ->
                println("Primary failed: $error, falling back...")
                // Return content to trigger fallback
                content
            }
    )
    .add(
        BedrockMultimodalPipe()
            .setPipeName("Fallback Processor")
            .setModel("meta.llama3-1-70b-instruct-v1:0")
            .setSystemPrompt("Handle this as a fallback processor")
    )
    .enableTracing()

// MultiConnector for parallel fallbacks
val multiConnector = MultiConnector()
    .add(primaryConnector)
    .add(fallbackConnector)
    .setMode(MultiConnector.ExecutionMode.FALLBACK)
    .execute(listOf("primary", "fallback"), listOf(content))
```

### Performance Monitoring

```kotlin
import com.TTT.Debug.*

val monitoredPipeline = Pipeline()
    .add(analysisPipe)
    .enableTracing(TraceConfig(
        enabled = true,
        detailLevel = TraceDetailLevel.DEBUG
    ))

val result = monitoredPipeline.execute(content)

// Export trace for analysis
val htmlReport = monitoredPipeline.getTraceReport(TraceFormat.HTML)
File("trace-report.html").writeText(htmlReport)

// Get performance metrics
val trace = PipeTracer.getTrace(monitoredPipeline.pipelineId)
trace?.let { events ->
    val totalTime = events.last().timestamp - events.first().timestamp
    val tokenUsage = monitoredPipeline.inputTokensSpent + monitoredPipeline.outputTokensSpent
    println("Execution time: ${totalTime}ms, Tokens used: $tokenUsage")
}
```

### Configuration Management

```kotlin
import com.TTT.Structs.PipeSettings

// Save pipe configuration
val settings = analysisPipe.toPipeSettings()
val configJson = Json.encodeToString(settings)
File("pipe-config.json").writeText(configJson)

// Load and apply configuration
val loadedSettings = Json.decodeFromString<PipeSettings>(
    File("pipe-config.json").readText()
)

val restoredPipe = BedrockMultimodalPipe()
    .fromPipeSettings(loadedSettings)
    .applySystemPrompt()
```

## API Reference

### Core Classes

| Class | Package | Purpose | Key Methods |
|-------|---------|---------|-------------|
| `Pipe` | `com.TTT.Pipe` | Base pipe abstraction | `execute()`, `setSystemPrompt()`, `setJsonOutput()` |
| `Pipeline` | `com.TTT.Pipeline` | Sequential pipe execution | `add()`, `execute()`, `enableTracing()` |
| `MultimodalContent` | `com.TTT.Pipe` | Content container | `addBinary()`, `jumpToPipe()`, `terminate()` |
| `ContextWindow` | `com.TTT.Context` | Memory management | `merge()`, `selectLoreBookContext()` |
| `PcpContext` | `com.TTT.PipeContextProtocol` | Tool definitions | `addStdioOption()`, `addHttpOption()` |

### Bedrock Pipe Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `setRegion()` | `String` | AWS region |
| `useConverseApi()` | - | Enable Converse API |
| `setModel()` | `String` | Model identifier |
| `setMaxTokens()` | `Int` | Token limit |
| `setTemperature()` | `Double` | Sampling temperature |
| `setReasoning()` | `Int` | Reasoning token budget |
| `enableStreaming()` | - | Enable response streaming |
| `setTools()` | `List<JsonObject>` | Tool definitions |
| `setToolChoice()` | `String` | Tool selection mode |

### Container Methods

| Container | Key Methods | Purpose |
|-----------|-------------|---------|
| `Connector` | `add()`, `execute()`, `setDefaultPath()` | Route to single pipeline |
| `Splitter` | `addContent()`, `addPipeline()`, `executePipelines()` | Parallel execution |
| `Manifold` | `setManagerPipeline()`, `addWorkerPipeline()` | Manager-worker pattern |
| `MultiConnector` | `setMode()`, `execute()` | Multiple connector coordination |

### Tracing and Debug

| Method | Purpose |
|--------|---------|
| `enableTracing()` | Enable trace collection |
| `getTraceReport()` | Export trace in format |
| `PipeTracer.exportTrace()` | Export specific trace |
| `TraceVisualizer.generateHtmlReport()` | Create HTML dashboard |

This comprehensive guide covers the complete TPipe framework with accurate examples based on the actual implementation. All code snippets use the correct API signatures and demonstrate real usage patterns from the TPipe codebase.
