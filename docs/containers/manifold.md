# Manifold - The Manager-Worker Orchestrator

Manifold provides manager-worker orchestration where a manager pipeline coordinates task execution. It uses a specialized execution loop that continues until task completion, with automatic conversation history management and intelligent token truncation.

## Table of Contents
- [Core Concepts](#core-concepts)
- [Basic Usage](#basic-usage)
- [Execution Methods and API](#execution-methods-and-api)
- [The Execution Loop](#the-execution-loop)
- [Context Management](#context-management)
- [Tracing Support](#tracing-support)
- [P2P Integration](#p2p-integration)
- [Complete Example](#complete-example)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Core Concepts

### TaskProgress
The Manager agent uses a structured `TaskProgress` object to track state and progression across multiple execution turns.

```kotlin
@Serializable
data class TaskProgress(
    var taskDescription: String = "",      // The foundational objective (immutable)
    var nextTaskInstructions: String = "", // Specific instructions for the next specialist
    var taskProgressStatus: String = "",   // High-level status summary
    var isTaskComplete: Boolean = false    // The project termination signal
)
```

### The Manager Pipeline
A Manifold acts as the supervisor for a specific **Manager Pipeline**. This pipeline is responsible for:
1.  Analyzing the current `TaskProgress`.
2.  Selecting and calling **Worker Agents** via P2P.
3.  Determining when the task is finished by setting the `passPipeline` or `terminatePipeline` flags.

---

## Basic Usage

Setting up a supervisory manifold involves attaching a manager pipeline and configuring the operational boundaries.

```kotlin
val supervisor = Manifold()
    .setManagerPipeline(orchestratorPipeline) // The Brain of the station
    .autoTruncateContext()                   // Prevent reservoir overflow
    .setContextWindowSize(16000)             // Set the total intake capacity
```

### Running the Project
```kotlin
val result = supervisor.execute(initialRequirements)
```

---

## Execution Methods and API

```kotlin
class Manifold {
    // Manager pipeline setup
    fun setManagerPipeline(pipeline: Pipeline, descriptor: P2PDescriptor? = null, requirements: P2PRequirements? = null): Manifold

    // Context management valves
    fun autoTruncateContext(): Manifold
    fun setTruncationMethod(method: ContextWindowSettings): Manifold
    fun setContextWindowSize(size: Int): Manifold
    fun setContextTruncationFunction(func: suspend (content: MultimodalContent) -> Unit): Manifold

    // Validation
    fun setValidatorFunction(func: suspend (content: MultimodalContent, agent: Pipeline) -> Boolean): Manifold

    // Core execution
    suspend fun execute(content: MultimodalContent): MultimodalContent
}
```

---

## The Execution Loop

The `execute()` method handles the complete multi-turn orchestration:

1.  **Format Check**: If the input isn't already a `ConverseHistory`, TPipe automatically wraps it into one.
2.  **The Loop**: The Manager Pipeline executes repeatedly while both `terminatePipeline` and `passPipeline` are false.
3.  **Coordination**: During each turn, the Manager makes P2P calls to worker agents, analyzes their response, and updates the task ledger.
4.  **Completion**: Once a termination flag is set by the Manager, the loop shuts and the final result is returned.

---

## Context Management

### Auto Truncation
Long-running projects can easily burst a model's context window. Enable automatic truncation to trim old conversation history turns when limits are approached.

```kotlin
manifold.autoTruncateContext()
    .setContextWindowSize(8192)
    .setTruncationMethod(ContextWindowSettings.TruncateTop)
```

### Worker Validation
You can install a secondary inspector that validates every response from a worker agent before the Manager sees it.

```kotlin
manifold.setValidatorFunction { content, worker ->
    // Return true if the worker's output is valid
    content.text.isNotBlank() && !content.terminatePipeline
}
```

---

## Tracing Support

Manifold emits a detailed audit trail of every supervisor decision and worker interaction.
- **`MANAGER_DECISION`**: Logs the step-by-step logic of the supervisor.
- **`AGENT_DISPATCH`**: Records which specialized sub-agent was called.
- **`TASK_PROGRESS_UPDATE`**: Tracks the evolution of the `TaskProgress` ledger.

---

## P2P Integration

A Manifold can be registered as a P2P agent, allowing other systems to delegate complex, multi-step tasks to it.

```kotlin
manifold.setP2pDescription(P2PDescriptor(
    agentName = "project-manager",
    agentDescription = "Coordinates multi-step software audits and development."
))
P2PRegistry.register(manifold)
```

---

## Complete Example

```kotlin
class InfrastructureSupervisor {
    private val manifold = Manifold()

    init {
        // 1. Create a 2-valve manager pipeline (Analyst -> Selector)
        val manager = Pipeline()
            .add(taskAnalystPipe)
            .add(workerSelectorPipe)

        // 2. Configure the station
        manifold.setManagerPipeline(manager)
            .autoTruncateContext()
            .setContextWindowSize(32000)
            .setValidatorFunction { content, _ -> content.text.isNotEmpty() }
    }

    suspend fun processProject(requirements: String): MultimodalContent {
        return manifold.execute(MultimodalContent(text = requirements))
    }
}
```

---

## Best Practices

*   **Explicit Termination**: Ensure your Manager prompt emphasizes that it MUST set `isTaskComplete = true` (mapping to `passPipeline`) to exit the loop.
*   **Worker Redundancy**: If a worker agent fails, prompt your supervisor to try a different expert or approach rather than terminating the whole project.
*   **Converse History**: Manifold is designed for conversation. Ensure all workers in your yard have `wrapContentWithConverse()` enabled for seamless state flow.

---

## Next Steps

Now that you can coordinate teams of agents, learn about the different ways to route data between them.

**→ [Connector - Pipeline Branching](connector.md)** - Key-based pipeline routing.
