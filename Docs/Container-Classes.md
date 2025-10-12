# TPipe Container Classes

Container classes in TPipe provide advanced orchestration and routing capabilities for managing multiple pipelines. They enable complex workflows including parallel execution, conditional branching, task orchestration, and multi-path routing.

## Overview

TPipe provides four primary container classes:

- **Connector** - Conditional branching to different pipelines based on keys
- **MultiConnector** - Advanced multi-path routing with sequential, parallel, and fallback modes
- **Splitter** - Parallel execution of multiple pipelines with result collection
- **Manifold** - Agentic task orchestration with manager and worker pipelines

---

## Connector

### Purpose

The Connector enables conditional branching by routing content to different pipelines based on a key-value mapping system. Think of it as a switch statement for pipelines.

### Key Features

- Map any key type to specific pipelines
- Dynamic pipeline selection at runtime
- Tracing support for debugging
- P2P interface support for agent communication
- Graceful failure handling

### Use Cases

- Route content based on classification results
- Branch workflows based on validation outcomes
- Implement decision trees in pipeline logic
- Create conditional processing paths

### Basic Usage

```kotlin
val connector = Connector()
    .add("route_a", pipelineA)
    .add("route_b", pipelineB)
    .add("default", defaultPipeline)

// Execute using a specific route
val result = connector.execute("route_a", content)
```

### Advanced Usage

```kotlin
// Enable tracing
connector.enableTracing(TraceConfig(
    detailLevel = TraceDetailLevel.VERBOSE
))

// Get trace after execution
val trace = connector.getTrace(TraceFormat.JSON)

// Set default path for P2P requests
connector.setDefaultPath("route_a")

// Retrieve specific pipeline
val pipeline = connector.get("route_b")
```

### How It Works

1. Content is passed to `execute()` with a routing key
2. Connector looks up the pipeline mapped to that key
3. If found, content is routed to that pipeline
4. Pipeline executes and returns result
5. If key not found or error occurs, content is terminated

### Error Handling

- Invalid keys terminate the pipeline gracefully
- Exceptions during execution set `terminatePipeline = true`
- Last executed connection is tracked for tracing

---

## MultiConnector

### Purpose

The MultiConnector manages multiple Connector instances and provides sophisticated routing strategies including sequential chaining, parallel distribution, and fallback mechanisms.

### Key Features

- Three execution modes: Sequential, Parallel, Fallback
- Load balancing for parallel execution
- Round-robin content distribution
- P2P interface support
- Automatic failure recovery in fallback mode

### Execution Modes

#### Sequential Mode
Passes content through connectors one after another, with each connector's output becoming the next connector's input.

```kotlin
val multiConnector = MultiConnector()
    .add(connectorA)
    .add(connectorB)
    .add(connectorC)
    .setMode(MultiConnector.ExecutionMode.SEQUENTIAL)

val result = multiConnector.execute(
    paths = listOf("step1", "step2", "step3"),
    content = inputContent
)
```

**Use Case**: Multi-stage processing where each stage depends on the previous result.

#### Parallel Mode
Distributes multiple content objects across connectors using round-robin load balancing.

```kotlin
val multiConnector = MultiConnector()
    .add(connectorA)
    .add(connectorB)
    .setMode(MultiConnector.ExecutionMode.PARALLEL)

val results = multiConnector.executeParallel(
    contentList = listOf(content1, content2, content3, content4),
    paths = listOf("route1", "route2", "route1", "route2")
)
```

**Use Case**: Processing multiple independent items concurrently across available resources.

#### Fallback Mode
Tries connectors in sequence until one succeeds, providing automatic failure recovery.

```kotlin
val multiConnector = MultiConnector()
    .add(primaryConnector)
    .add(backupConnector)
    .add(emergencyConnector)
    .setMode(MultiConnector.ExecutionMode.FALLBACK)

val result = multiConnector.execute(
    paths = listOf("primary", "backup", "emergency"),
    content = inputContent
)
```

**Use Case**: High-availability systems with primary and backup processing paths.

### Load Balancing

In parallel mode, content is distributed using round-robin:
- Content 0 → Connector 0
- Content 1 → Connector 1
- Content 2 → Connector 0
- Content 3 → Connector 1

### P2P Integration

MultiConnector can be registered as a P2P agent, allowing remote invocation:

```kotlin
val descriptor = P2PDescriptor(
    agentName = "multi-router",
    agentDescription = "Multi-path content router"
)

multiConnector.setP2pDescription(descriptor)
P2PRegistry.register(multiConnector, transport, descriptor, requirements)
```

---

## Splitter

### Purpose

The Splitter executes multiple pipelines in parallel and collects their results in a thread-safe manner. It's designed for fan-out processing where a single input spawns multiple independent processing tasks.

### Key Features

- Parallel pipeline execution with coroutines
- Thread-safe result collection
- Activation key system for flexible pipeline grouping
- Callback system for pipeline and splitter completion
- Automatic pipeline initialization
- Tracing support

### Core Concepts

#### Activation Keys
Keys group pipelines and associate them with specific content. Multiple pipelines can share the same key for parallel execution with the same input.

#### Result Collection
Results are stored in a `MultimodalCollection` with pipeline names as keys, accessible during or after execution.

#### Callbacks
Two callback types enable reactive programming:
- `onPipelineFinish` - Called when any pipeline completes
- `onSplitterFinish` - Called when all pipelines complete

### Basic Usage

```kotlin
val splitter = Splitter()
    .addContent("task1", content1)
    .addPipeline("task1", pipelineA)
    .addPipeline("task1", pipelineB)
    .addContent("task2", content2)
    .addPipeline("task2", pipelineC)

splitter.init()
val jobs = splitter.executePipelines()

// Wait for all to complete
jobs.forEach { it.await() }

// Access results
val resultA = splitter.results.contents["pipelineA"]
val resultB = splitter.results.contents["pipelineB"]
```

### Advanced Usage with Callbacks

```kotlin
val splitter = Splitter()
    .setOnPipelineFinish { splitter, pipeline, content ->
        println("${pipeline.pipelineName} completed")
        // Process individual result
        processResult(content)
    }
    .setOnSplitterFinish { splitter ->
        println("All pipelines completed")
        // Aggregate all results
        val allResults = splitter.results.contents.values
        aggregateResults(allResults)
    }
    .enableTracing()

// Add pipelines and content
splitter
    .addContent("batch1", batchContent)
    .addPipeline("batch1", pipeline1)
    .addPipeline("batch1", pipeline2)
    .addPipeline("batch1", pipeline3)

splitter.init(TraceConfig(detailLevel = TraceDetailLevel.VERBOSE))
splitter.executePipelines()
```

### Pipeline Management

```kotlin
// Remove specific pipeline from all keys
splitter.removePipeline(pipelineA)

// Remove entire activation key
splitter.removeKey("task1")

// Flush results for reuse
splitter.results.flush()
```

### Use Cases

- Batch processing with parallel workers
- A/B testing multiple pipeline configurations
- Ensemble model predictions
- Multi-model consensus systems
- Parallel data transformation pipelines

### Thread Safety

The Splitter uses:
- `ConcurrentHashMap` for result storage
- `Mutex` for callback synchronization
- `AtomicInteger` for completion tracking

This ensures safe concurrent access from multiple coroutines.

### Error Handling

Pipeline failures are caught and stored as error content:
```kotlin
val errorContent = MultimodalContent("Pipeline execution failed: ${e.message}")
```

Callbacks are still invoked for failed pipelines, allowing error handling logic.

---

## Manifold

### Purpose

The Manifold implements agentic task orchestration where a manager pipeline coordinates multiple specialized worker pipelines to complete complex tasks. It's the most sophisticated container class, designed for autonomous agent systems.

### Architecture

```
┌─────────────────────────────────────────┐
│         Manifold Container              │
│                                         │
│  ┌───────────────────────────────────┐ │
│  │     Manager Pipeline              │ │
│  │  - Analyzes task                  │ │
│  │  - Selects worker agents          │ │
│  │  - Tracks progress                │ │
│  │  - Validates completion           │ │
│  └───────────────────────────────────┘ │
│              │                          │
│              ▼                          │
│  ┌───────────────────────────────────┐ │
│  │     Worker Pipelines              │ │
│  │  ┌─────────┐  ┌─────────┐        │ │
│  │  │Worker A │  │Worker B │  ...   │ │
│  │  │Specialist│  │Specialist│       │ │
│  │  └─────────┘  └─────────┘        │ │
│  └───────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

### Key Features

- Manager-worker architecture
- Automatic P2P agent registration
- Converse history tracking
- Context window management
- Human-in-the-loop validation
- Automatic context truncation
- Comprehensive tracing
- Task progress tracking

### Basic Setup

```kotlin
val manifold = Manifold()
    .setManagerPipeline(managerPipeline)
    .addWorkerPipeline(
        pipeline = codeWriterPipeline,
        agentName = "CodeWriter",
        agentDescription = "Writes code based on specifications",
        agentSkills = listOf(
            P2PSkills("Write", "Generate code files"),
            P2PSkills("Refactor", "Improve existing code")
        )
    )
    .addWorkerPipeline(
        pipeline = testWriterPipeline,
        agentName = "TestWriter",
        agentDescription = "Creates unit tests",
        agentSkills = listOf(
            P2PSkills("Test", "Generate test cases")
        )
    )

manifold.init()
val result = manifold.execute(taskContent)
```

### Context Management

The Manifold automatically manages context to prevent overflow:

```kotlin
manifold
    .autoTruncateContext()
    .setTruncationMethod(ContextWindowSettings.TruncateTop)
    .setContextWindowSize(32000)
```

Or provide custom truncation logic:

```kotlin
manifold.setContextTruncationFunction { content ->
    // Custom truncation logic
    val history = extractJson<ConverseHistory>(content.text)
    // Modify history as needed
    content.text = serialize(history)
}
```

### Human-in-the-Loop

#### Validation Function
Inspect and validate worker outputs:

```kotlin
manifold.setValidatorFunction { content, agent ->
    val output = content.text
    
    // Validate output meets requirements
    if (output.contains("ERROR") || output.isEmpty()) {
        println("Agent ${agent.pipelineName} produced invalid output")
        return@setValidatorFunction false
    }
    
    true // Validation passed
}
```

#### Failure Function
Attempt recovery when validation fails:

```kotlin
manifold.setFailureFunction { content, agent ->
    println("Attempting to recover from failure...")
    
    // Try to fix the issue
    val fixed = attemptRepair(content)
    
    if (fixed) {
        content.text = "Repaired output"
        return@setFailureFunction true // Continue
    }
    
    false // Cannot recover, terminate manifold
}
```

#### Transformation Function
Transform outputs after successful execution:

```kotlin
manifold.setTransformationFunction { content ->
    // Execute PCP tools
    val tools = extractTools(content)
    tools.forEach { executeTool(it) }
    
    // Transform content
    content.text = formatOutput(content.text)
    content
}
```

### Execution Flow

1. **Initialization**
   - Manager and workers are initialized
   - P2P agents are registered
   - Agent lists are distributed
   - Context window sizes are calculated

2. **Task Loop**
   - Manager analyzes task and current state
   - Manager selects appropriate worker agent
   - Worker executes specialized task
   - Result is validated (if validator provided)
   - Result is transformed (if transformer provided)
   - Converse history is updated
   - Loop continues until task complete

3. **Termination**
   - Manager signals task completion
   - Final result is returned
   - Trace data is available

### Converse History

The Manifold uses `ConverseHistory` to track all interactions:

```kotlin
{
  "history": [
    {"role": "user", "content": "Initial task description"},
    {"role": "system", "content": "Manager decision to call CodeWriter"},
    {"role": "agent", "content": "CodeWriter output"},
    {"role": "system", "content": "Manager decision to call TestWriter"},
    {"role": "agent", "content": "TestWriter output"}
  ]
}
```

This provides complete task context for the manager's decision-making.

### P2P Integration

Workers are automatically registered as P2P agents:

```kotlin
// Automatic registration with secure defaults
manifold.addWorkerPipeline(workerPipeline)

// Custom P2P configuration
val descriptor = P2PDescriptor(
    agentName = "CustomWorker",
    agentDescription = "Specialized worker",
    requiresAuth = true,
    usesConverse = true
)

val requirements = P2PRequirements(
    allowExternalConnections = true,
    requireConverseInput = true
)

manifold.addWorkerPipeline(workerPipeline, descriptor, requirements)
```

### Tracing

Enable comprehensive tracing for debugging:

```kotlin
manifold.enableTracing(TraceConfig(
    detailLevel = TraceDetailLevel.DEBUG,
    includeContext = true,
    includeMetadata = true,
    outputFormat = TraceFormat.HTML
))

// After execution
val trace = manifold.getTraceReport(TraceFormat.HTML)
val analysis = manifold.getFailureAnalysis()
```

Trace events include:
- `MANIFOLD_START` / `MANIFOLD_END`
- `MANIFOLD_LOOP_ITERATION`
- `MANAGER_TASK_ANALYSIS`
- `MANAGER_DECISION`
- `AGENT_DISPATCH`
- `AGENT_RESPONSE`
- `CONVERSE_HISTORY_UPDATE`
- `VALIDATION_START` / `VALIDATION_SUCCESS` / `VALIDATION_FAILURE`
- `TRANSFORMATION_START` / `TRANSFORMATION_SUCCESS`

### Use Cases

- Multi-agent software development systems
- Complex task decomposition and execution
- Autonomous research and analysis
- Multi-step content generation
- Collaborative AI systems
- Task orchestration with specialized models

### Best Practices

1. **Always provide context management** - Either enable auto-truncation or provide a custom function
2. **Use validation functions** - Prevent invalid outputs from propagating
3. **Enable tracing during development** - Essential for debugging complex agent interactions
4. **Name your pipelines** - Makes trace analysis much easier
5. **Define clear agent skills** - Helps manager make better delegation decisions
6. **Use converse history** - Provides optimal context for manager decisions
7. **Handle failures gracefully** - Implement failure functions for critical systems

---

## Comparison Matrix

| Feature | Connector | MultiConnector | Splitter | Manifold |
|---------|-----------|----------------|----------|----------|
| **Execution** | Single path | Multi-path | Parallel | Sequential loop |
| **Routing** | Key-based | Multi-strategy | Activation keys | Agent-based |
| **Parallelism** | No | Optional | Yes | No |
| **P2P Support** | Yes | Yes | No | Yes |
| **Callbacks** | No | No | Yes | Yes |
| **Tracing** | Yes | Yes | Yes | Yes |
| **Context Management** | No | No | No | Yes |
| **Complexity** | Low | Medium | Medium | High |

---

## Choosing the Right Container

- **Use Connector** when you need simple conditional branching
- **Use MultiConnector** when you need complex routing with multiple strategies
- **Use Splitter** when you need parallel execution with result collection
- **Use Manifold** when you need autonomous agent orchestration

---

## Common Patterns

### Pattern: Validation Pipeline with Fallback

```kotlin
val validator = Connector()
    .add("valid", successPipeline)
    .add("invalid", retryPipeline)

val multiConnector = MultiConnector()
    .add(primaryConnector)
    .add(validator)
    .setMode(MultiConnector.ExecutionMode.SEQUENTIAL)
```

### Pattern: Parallel Processing with Aggregation

```kotlin
val splitter = Splitter()
    .addContent("batch", batchData)
    .addPipeline("batch", worker1)
    .addPipeline("batch", worker2)
    .addPipeline("batch", worker3)
    .setOnSplitterFinish { splitter ->
        val aggregated = aggregateResults(splitter.results.contents.values)
        saveResults(aggregated)
    }
```

### Pattern: Multi-Stage Agent System

```kotlin
val manifold = Manifold()
    .setManagerPipeline(orchestrator)
    .addWorkerPipeline(analyzer, agentName = "Analyzer")
    .addWorkerPipeline(generator, agentName = "Generator")
    .addWorkerPipeline(validator, agentName = "Validator")
    .autoTruncateContext()
    .setValidatorFunction { content, agent -> validateOutput(content) }
    .enableTracing()
```

---

## See Also

- [Pipeline Documentation](Pipeline.md)
- [P2P System Documentation](P2P-System.md)
- [Tracing Documentation](Tracing.md)
- [Context Management](Context-Management.md)
