# DistributionGrid

## Table of Contents
- [Current Implementation Status](#current-implementation-status)
- [Defined Data Structures](#defined-data-structures)
- [Intended Architecture](#intended-architecture)
- [Schema Validation](#schema-validation)
- [Missing Implementation](#missing-implementation)
- [Planned Usage (Not Currently Working)](#planned-usage-not-currently-working)
- [Development Requirements](#development-requirements)
- [Current Limitations](#current-limitations)
- [Contributing](#contributing)

DistributionGrid is designed for decentralized agent swarms where AI agents autonomously decide which agent to call next. **Currently this is a stub implementation** with only basic structure and data classes defined.

## Current Implementation Status

⚠️ **DistributionGrid is currently incomplete** - only the class structure and data types exist.

```kotlin
class DistributionGrid : P2PInterface {
    private var entryPipeline: Pipeline? = null
    private var judgePipeline: Pipeline? = null  
    private var workerPipelines: MutableList<Pipeline>? = null
    private var availableAgents: List<AgentDescriptor>? = null
    private var enableTracing = false
    
    // Only one implemented method:
    fun setEntryPipeline(pipeline: Pipeline) {
        // Validates pipeline has DistributionGridTask JSON schema
        val requiredJsonOutputSchema = examplePromptFor(DistributionGridTask::class)
        // ... validation logic
        entryPipeline = pipeline
    }
}
```

## Defined Data Structures

### DistributionGridTask
Task payload designed to pass between agents:

```kotlin
@Serializable
data class DistributionGridTask(
    var isTaskComplete: Boolean,
    var taskDescription: String,        // Original task, never changes
    var actionTaken: String,           // What current agent did
    var requestToNextAgent: String,    // Instructions for next agent
    var nextAgentToCall: AgentRequest, // Which agent to call next
    var pcpRequest: PcPRequest? = null,// Optional tool execution
    var userContent: MultimodalContent? = null // Hidden from agents
)
```

### DistributionGridJudgement
Judge evaluation result:

```kotlin
data class DistributionGridJudgement(
    var isTaskComplete: Boolean = false,
    var previousAgent: String = "",
    var previousAgentResponse: MultimodalContent = MultimodalContent()
)
```

## Intended Architecture

The planned design includes:

### Dispatcher Pipeline
- Initial task assessment and first agent selection
- Must have JSON output schema matching `DistributionGridTask`

### Worker Pipelines
- Autonomous agents that process tasks and select next agents
- Each must support `DistributionGridTask` input/output

### Judge Pipeline
- Validates task completion and routes incomplete tasks
- Uses `DistributionGridJudgement` for decisions

## Schema Validation

The only implemented feature validates pipeline schemas:

```kotlin
fun setEntryPipeline(pipeline: Pipeline) {
    val requiredJsonOutputSchema = examplePromptFor(DistributionGridTask::class)
    
    var hasSchema = false
    for (pipe in pipeline.getPipes()) {
        if (pipe.jsonOutput == requiredJsonOutputSchema) {
            hasSchema = true
            break
        }
    }
    
    if (!hasSchema) {
        throw Exception("Entry pipeline must have a pipe with the following json output schema: $requiredJsonOutputSchema")
    }
    
    entryPipeline = pipeline
}
```

## Missing Implementation

The following features are **not yet implemented**:

- ❌ Task execution loop
- ❌ Agent discovery and routing
- ❌ Judge validation system
- ❌ Token management and compression
- ❌ Conversation history tracking
- ❌ Worker pipeline management
- ❌ P2P request handling
- ❌ Tracing events
- ❌ Error handling and recovery

## Planned Usage (Not Currently Working)

```kotlin
// This is the intended API design, but not implemented:
class SoftwareDevelopmentGrid {
    private val grid = DistributionGrid()
    
    init {
        // These methods don't exist yet:
        // grid.setDispatcher(dispatcherPipeline)
        // grid.addWorker("architect", architectPipeline)  
        // grid.setJudge(judgePipeline)
        // grid.setMaxIterations(50)
        
        // Only this works:
        grid.setEntryPipeline(dispatcherPipeline)
    }
    
    suspend fun developSoftware(requirements: String): SoftwareProject {
        // This execute method doesn't exist:
        // val result = grid.executeTask(requirements)
        
        throw NotImplementedError("DistributionGrid execution not implemented")
    }
}
```

## Development Requirements

To complete DistributionGrid implementation:

### Core Methods Needed
```kotlin
// Agent management
fun addWorker(name: String, pipeline: Pipeline)
fun setJudge(pipeline: Pipeline)
fun setMaxIterations(max: Int)

// Execution
suspend fun executeTask(task: String): MultimodalContent
suspend fun executeAgent(agentName: String, task: DistributionGridTask): MultimodalContent

// Agent discovery
fun updateAvailableAgents()
fun getAgentContext(): String

// Token management  
fun manageTokens(history: ConverseHistory): ConverseHistory
fun compressHistory(history: ConverseHistory, targetSize: Int): ConverseHistory
```

### P2P Integration
```kotlin
override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
    // Route to appropriate agent based on request
}

override fun getPipelinesFromInterface(): List<Pipeline> {
    // Return all registered pipelines
}
```

### Tracing Support
```kotlin
fun enableTracing(config: TraceConfig)
// Emit events like:
// - DISTRIBUTION_GRID_START/END
// - AGENT_DISPATCH/COMPLETION  
// - TASK_ROUTING_DECISION
// - JUDGE_VALIDATION
// - TOKEN_COMPRESSION
```

## Current Limitations

- **No execution capability** - only schema validation works
- **No agent management** - cannot add workers or judges
- **No P2P handling** - P2P methods are inherited but not implemented
- **No tracing** - no trace events emitted
- **No error handling** - no recovery mechanisms

## Contributing

If implementing DistributionGrid:

1. **Study existing containers** (Manifold, Splitter) for patterns
2. **Implement agent management** methods
3. **Create execution loop** with task routing
4. **Add judge validation** system
5. **Implement token management** and compression
6. **Add comprehensive tracing** support
7. **Handle P2P requests** properly
8. **Create test scenarios** for distributed execution

DistributionGrid represents an advanced distributed AI pattern that requires significant implementation work to become functional.

---

**Previous:** [← Manifold](manifold.md) | **Next:** [Junction →](junction.md)
