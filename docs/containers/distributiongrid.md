# DistributionGrid - Decentralized Agent Swarms

In a highly advanced infrastructure, you don't always want a single manager coordinating every task. Sometimes you need a **Decentralized Swarm**—a system where agents autonomously decide which specialist to call next based on the evolving requirements of a project. **DistributionGrid** is the orchestration container designed for these autonomous workflows.

Think of DistributionGrid as a **Smart Power Grid** where power (tasks) is automatically routed to the most efficient substation (agent) without central intervention.

> [!WARNING]
> **Experimental Status**: The `DistributionGrid` is currently a **Stub Implementation**. The structural blueprints and data classes are defined, but the autonomous execution loop is not yet functional.

## Core Data Structures: The Task Cargo

The grid relies on the `DistributionGridTask` to move data between autonomous agents.

```kotlin
@Serializable
data class DistributionGridTask(
    var isTaskComplete: Boolean,        // Has the project reached its goal?
    var taskDescription: String,        // The foundational objective (immutable)
    var actionTaken: String,           // A log of what the current agent just did
    var requestToNextAgent: String,    // Specific instructions for the next specialist
    var nextAgentToCall: AgentRequest, // The target valve for the next turn
    var pcpRequest: PcPRequest? = null // Optional tool calls (e.g., shell, HTTP)
)
```

## Intended Architecture: The Autonomous Yard

When complete, the DistributionGrid will coordinate three types of specialized mainlines:

### 1. The Dispatcher (Entry Pipeline)
The initial valve that assesses the task and selects the first agent to enter the swarm. It must output a valid `DistributionGridTask` object.

### 2. The Workers (Autonomous Agents)
A pool of specialized pipelines (Registered in the **P2PRegistry**) that can process a task and then "Hand off" the flow to another worker by populating the `nextAgentToCall` field.

### 3. The Judge (Quality Control)
A high-priority auditor that validates every hand-off, ensuring the swarm is actually moving toward the goal and hasn't fallen into a logic loop.

---

## Technical Validation: Schema Checking

Currently, the grid's only active feature is **Blueprint Validation**. It ensures that any entry pipeline you attach has the correct JSON output schema to produce a `DistributionGridTask`.

```kotlin
fun setEntryPipeline(pipeline: Pipeline) {
    // TPipe verifies that the pipeline's final valve
    // matches the required DistributionGridTask specification.
    // ...
}
```

---

## Planned Capabilities (Development Roadmap)

To reach industrial readiness, the following Infrastructure Upgrades are planned:

*   **Autonomous Loop**: A kernel that handles the actual hand-off between worker agents.
*   **Token Compression**: Logic to automatically summarize long conversation histories so the Water Level (token count) stays within model limits.
*   **Infinite Loop Detection**: A safety valve that shuts the grid if agents start passing a task back and forth without making progress.
*   **Deep Grid Tracing**: High-resolution telemetry to visualize the Path a task took through the autonomous swarm.

---

## Contributing to the Grid

The `DistributionGrid` represents the cutting edge of the TPipe ecosystem. If you are interested in building decentralized agent swarms:

1.  **Analyze Manifold**: Study the `Manifold` container to understand how multi-turn loops are currently handled.
2.  **P2P Mastery**: Ensure you understand how `P2PRegistry` handles agent discovery, as the grid relies entirely on P2P for worker hand-offs.
3.  **Autonomous Logic**: Build test scenarios where agents can successfully populate `AgentRequest` objects based on complex inputs.

## Next Steps

Since the DistributionGrid is experimental, start your orchestration journey with the production-ready manager-worker system.

**→ [Manifold - Multi-Agent Orchestration](manifold.md)** - Coordinating specialized workers with a central manager.
