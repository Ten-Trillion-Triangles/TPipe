# Manifold - The Manager-Worker Orchestrator

In a massive agentic project, you don't just need a single mainline; you need a **Project Manager**—an agent that can coordinate multiple specialized workers to achieve a complex goal. **Manifold** is the orchestration container that provides this manager-worker hierarchy. It executes a "Manager Pipeline" in a loop, allowing it to delegate tasks to other P2P agents, analyze their progress, and decide when the project is finished.

Think of Manifold as the **Supervisory Station** that watches over a team of specialized technicians (Agents) and ensures the entire "Plumbing Project" is completed to spec.

## Core Concepts: The Supervisory Loop

### TaskProgress: The Project Ledger
The Manager agent uses a structured `TaskProgress` object to keep track of what has been done and what needs to happen next.

```kotlin
@Serializable
data class TaskProgress(
    var taskDescription: String = "",      // What are we trying to do?
    var nextTaskInstructions: String = "", // Instructions for the next worker
    var taskProgressStatus: String = "",   // High-level status
    var isTaskComplete: Boolean = false    // Is the valve project finished?
)
```

### The Execution Loop
The Manifold works by looping. It pumps data into the Manager Pipeline, which then decides:
1.  "I need help from the **Code-Developer** agent." (P2P Call)
2.  "The worker finished. Now I'll update my progress ledger."
3.  "The project is 100% complete. I'll shut the loop."

---

## Basic Usage: Setting Up the Station

```kotlin
val supervisor = Manifold()
    .setManagerPipeline(orchestratorPipeline) // The "Brain" of the station
    .autoTruncateContext()                   // Prevent reservoir overflow
    .setContextWindowSize(16000)             // Set the total capacity
```

### Running the Project
```kotlin
val result = supervisor.execute(initialRequirements)
```

---

## P2P Integration: The Intercom System

For a Manifold to work, its Manager Pipeline must be configured to talk to other agents via **P2P (Pipe-to-Pipe)**. The Manifold itself can also be registered as a P2P agent, allowing it to act as a "Project Endpoint" for other systems.

```kotlin
// Registering the Manifold as a supervisor
manifold.setP2pDescription(P2PDescriptor(
    agentName = "lead-supervisor",
    agentDescription = "Coordinates complex multi-step infrastructure projects."
))
P2PRegistry.register(manifold)
```

---

## Context Management: Balancing the Load

Because Manifold projects can involve many turns of conversation, managing the Reservoir Pressure (token count) is critical.

*   **Auto Truncation**: Manifold can automatically trim the conversation history as it grows.
*   **Custom Truncation**: You can provide your own logic for how the supervisor should "forget" old tasks to make room for new ones.
*   **Worker Validation**: You can set a `setValidatorFunction` to inspect the output of every worker agent before the supervisor sees it, ensuring no "junk" enters the manager's context.

---

## Tracing: The Audit Trail

Managing a team of agents is complex. TPipe's deep tracing provides a complete audit trail of every manager decision and worker response.

```kotlin
// Trace logs will show:
// - AGENT_DISPATCH: Who the supervisor called.
// - AGENT_RESPONSE: What the worker returned.
// - TASK_PROGRESS_UPDATE: How the supervisor updated the ledger.
```

---

## Best Practices for Supervisors

*   **Clear Termination**: Ensure your Manager Pipeline is prompted to set `passPipeline = true` when the task is genuinely finished, or the loop will continue indefinitely.
*   **Use Converse History**: Manifold works best when the entire flow is treated as a conversation.
*   **Worker Redundancy**: If a worker agent fails, prompt your supervisor to try a different agent or a different approach rather than just terminating.

---

## Next Steps

Now that you can manage teams of agents, learn about the different ways to route data between them.

**→ [Connector - Pipeline Branching](connector.md)** - Key-based pipeline routing.
