# Manifold

> 💡 **Tip:** The **Manifold** orchestrates multiple specialized Agents (pipes). It is a complex switching yard that reads PCP function calls and routes the water to the correct downstream agent dynamically.


## Table of Contents
- [Core Concepts](#core-concepts)
- [Basic Usage](#basic-usage)
- [Key Methods](#key-methods)
- [Task Execution Flow](#task-execution-flow)
- [Context Management](#context-management)
- [Tracing Support](#tracing-support)
- [P2P Integration](#p2p-integration)
- [Complete Example](#complete-example)
- [Important Notes](#important-notes)
- [Best Practices](#best-practices)

Manifold provides manager-worker orchestration where a manager pipeline coordinates task execution. It uses a single `execute()` method that loops until task completion, with automatic converse history management and token truncation.

## Core Concepts

### TaskProgress
Tracks task state and progression:

```kotlin
@Serializable
data class TaskProgress(
    var taskDescription: String = "",
    var nextTaskInstructions: String = "",
    var taskProgressStatus: String = "",
    var isTaskComplete: Boolean = false
)
```

### Manager Pipeline
The manager pipeline must:
- Accept converse history format
- Make P2P calls to worker agents
- Set `terminatePipeline` or `passPipeline` when complete

## Basic Usage

```kotlin
val manifold = Manifold()
    .setManagerPipeline(orchestratorPipeline)
    .autoTruncateContext()
    .setContextWindowSize(8192)

val result = manifold.execute(initialTask)
```

## Key Methods

```kotlin
class Manifold {
    // Manager pipeline setup
    fun setManagerPipeline(pipeline: Pipeline, descriptor: P2PDescriptor? = null, requirements: P2PRequirements? = null): Manifold
    
    // Context management
    fun autoTruncateContext(): Manifold
    fun setTruncationMethod(method: ContextWindowSettings): Manifold
    fun setContextWindowSize(size: Int): Manifold
    fun setContextTruncationFunction(func: suspend (content: MultimodalContent) -> Unit): Manifold
    
    // Validation
    fun setValidatorFunction(func: suspend (content: MultimodalContent, agent: Pipeline) -> Boolean): Manifold
    
    // Execution
    suspend fun execute(content: MultimodalContent): MultimodalContent
}
```

## Task Execution Flow

The `execute()` method handles the complete orchestration:

1. **Converse History Setup**: Converts input to converse format if needed
2. **Manager Loop**: Executes manager pipeline repeatedly
3. **P2P Coordination**: Manager makes calls to worker agents
4. **Termination**: Continues until `terminatePipeline` or `passPipeline` is set

```kotlin
suspend fun execute(content: MultimodalContent): MultimodalContent {
    // Convert to converse history if needed
    val isConverseHistory = extractJson<ConverseHistory>(content.text)
    if (isConverseHistory == null) {
        val newConverseHistory = ConverseHistory()
        newConverseHistory.add(ConverseRole.user, content)
        content.text = serialize(newConverseHistory)
    }
    
    // Execute manager loop
    while (!workingContentObject.terminatePipeline && !workingContentObject.passPipeline) {
        // Manager decides next action and calls workers via P2P
        workingContentObject = managerPipeline.execute(workingContentObject)
    }
    
    return workingContentObject
}
```

## Context Management

### Auto Truncation
Enable automatic context truncation to prevent overflow:

```kotlin
manifold.autoTruncateContext()
    .setContextWindowSize(8192)
    .setTruncationMethod(ContextWindowSettings.TRUNCATE_TOP)
```

### Custom Truncation Function
Provide custom truncation logic:

```kotlin
manifold.setContextTruncationFunction { content ->
    // Custom truncation implementation
    println("Truncating content: ${content.text.length} characters")
}
```

### Validation Function
Add custom validation for worker outputs:

```kotlin
manifold.setValidatorFunction { content, agent ->
    // Return true if content is valid, false otherwise
    content.text.isNotBlank() && !content.terminatePipeline
}
```

## Tracing Support

Manifold emits comprehensive tracing events:

```kotlin
// Trace events include:
// - MANIFOLD_START/END/SUCCESS/FAILURE
// - MANAGER_DECISION/TASK_ANALYSIS/AGENT_SELECTION
// - TASK_PROGRESS_UPDATE/COMPLETION_CHECK
// - AGENT_DISPATCH/RESPONSE
// - MANIFOLD_LOOP_ITERATION
// - CONVERSE_HISTORY_UPDATE
```

## P2P Integration

Manifold requires P2P-enabled manager pipeline:

```kotlin
// Manager must have P2P agents configured
private fun hasP2P(pipeline: Pipeline?) {
    // Validates that pipeline can make P2P calls
}

override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
    return execute(request.prompt)?.let { result ->
        P2PResponse(output = result)
    }
}
```

## Complete Example

```kotlin
class ProjectManagementSystem {
    private val manifold = Manifold()
    
    init {
        setupManifold()
    }
    
    private fun setupManifold() {
        // Create manager pipeline with P2P capabilities
        val manager = Pipeline()
            .addPipe(taskPlannerPipe)
            .addPipe(workerSelectorPipe)
            .addPipe(progressEvaluatorPipe)
        
        // Register worker agents in P2P system
        val codeWorker = Pipeline().addPipe(codeGenerationPipe)
        codeWorker.setP2pDescription(P2PDescriptor(
            agentName = "code-developer",
            agentDescription = "Generates and reviews code",
            transport = P2PTransport(Transport.Tpipe, "code-developer"),
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none
        ))
        P2PRegistry.register(codeWorker)
        
        val docWorker = Pipeline().addPipe(documentationPipe)
        docWorker.setP2pDescription(P2PDescriptor(
            agentName = "documentation-writer",
            agentDescription = "Creates technical documentation",
            transport = P2PTransport(Transport.Tpipe, "documentation-writer"),
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none
        ))
        P2PRegistry.register(docWorker)
        
        // Configure manifold using builder methods
        manifold.setManagerPipeline(manager)
            .autoTruncateContext()
            .setContextWindowSize(16000)
            .setTruncationMethod(ContextWindowSettings.TRUNCATE_TOP)
            .setValidatorFunction { content, agent ->
                // Validate worker outputs
                content.text.isNotBlank() && !content.terminatePipeline
            }
    }
    
    suspend fun executeProject(requirements: String): ProjectResult {
        val initialTask = MultimodalContent().apply {
            addText(requirements)
        }
        
        val result = manifold.execute(initialTask)
        
        return ProjectResult(
            finalOutput = result.text,
            conversationHistory = extractJson<ConverseHistory>(result.text),
            completed = result.passPipeline || result.terminatePipeline
        )
    }
    
    fun setupP2PAgent() {
        manifold.setP2pDescription(P2PDescriptor(
            agentName = "project-manager",
            agentDescription = "Manages complex multi-step software projects",
            transport = P2PTransport(Transport.Tpipe, "project-manager"),
            requiresAuth = true,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = true,
            allowsCustomAgentJson = false,
            recordsInteractionContext = true,
            recordsPromptContent = false,
            allowsExternalContext = true,
            contextProtocol = ContextProtocol.pcp,
            agentSkills = mutableListOf(
                P2PSkills("task-orchestration", "Coordinate multi-step project tasks"),
                P2PSkills("worker-management", "Select and manage specialized workers"),
                P2PSkills("progress-tracking", "Monitor and evaluate task progress")
            )
        ))
        
        manifold.setP2pRequirements(P2PRequirements(
            allowExternalConnections = true,
            requireConverseInput = true
        ))
        
        manifold.setP2pTransport(P2PTransport(Transport.Tpipe, "project-manager"))
        
        P2PRegistry.register(manifold)
    }
}

data class ProjectResult(
    val finalOutput: String,
    val conversationHistory: ConverseHistory?,
    val completed: Boolean
)
```

## Important Notes

- **Manager pipeline required**: Must be set before execution
- **P2P dependency**: Manager must be able to make P2P calls to workers
- **Converse format**: Input is automatically converted to converse history
- **Loop termination**: Manager must set termination flags to exit
- **Context management**: Auto-truncation prevents context overflow
- **Tracing support**: Comprehensive events for debugging

## Best Practices

- **Design clear termination criteria** in manager pipeline
- **Enable auto-truncation** for long-running tasks
- **Register worker agents** in P2P system before execution
- **Use converse history** for proper conversation tracking
- **Monitor loop iterations** to prevent infinite loops
- **Handle P2P failures** gracefully in manager pipeline
- **Enable tracing** for debugging complex workflows
- **Validate manager pipeline** has P2P capabilities before execution

---

**Previous:** [← Splitter](splitter.md) | **Next:** [DistributionGrid →](distributiongrid.md)
