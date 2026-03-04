# Container Overview

Containers are specialized classes that orchestrate multiple pipelines, providing higher-level coordination patterns beyond simple pipe composition. Most containers implement `P2PInterface`, providing a unified way to build distributed agent systems.

## Table of Contents
- [Container vs Pipeline](#container-vs-pipeline)
- [The Container Catalog](#the-container-catalog)
- [P2P Integration](#p2p-integration)
- [Tracing Support](#tracing-support)
- [When to Use Containers](#when-to-use-containers)
- [Implementation Status](#implementation-status)
- [Basic Container Pattern](#basic-container-pattern)
- [Next Steps](#next-steps)

---

## Container vs Pipeline

Think of a Pipeline as a series of connected pipes. A Container is the structure that decides which pipeline to use or how to combine their outputs.

| Aspect | Pipeline | Container |
| :--- | :--- | :--- |
| **Purpose** | Sequential execution of logic. | Coordination of multiple logic streams. |
| **Child Units** | Individual `Pipe` instances. | Complete `Pipeline` instances. |
| **Flow Model** | Linear (A → B → C). | Routing, Parallel, or Distributed. |
| **P2P Role** | Represents a single agent. | Can expose and coordinate multiple agents. |

---

## The Container Catalog

TPipe provides several specialized containers to handle different infrastructure needs:

### 1. Routing Containers
These containers act like Switching Stations, directing the flow based on specific keys or conditions.
*   **[Connector](connector.md)**: Simple key-based routing between different pipelines.
*   **[MultiConnector](multiconnector.md)**: Complex management of multiple connections with advanced execution modes.

### 2. Parallel Containers
These act like Manifolds, splitting a single flow into multiple branches that run at the same time.
*   **[Splitter](splitter.md)**: Fan-out execution where one input is processed by multiple pipelines simultaneously, with results collected at the end.

### 3. Orchestration Containers
The Brain of the infrastructure, managing task distribution and agent cooperation.
*   **[Manifold](manifold.md)**: Manager-worker coordination where a Manager agent delegates tasks to a pool of Worker agents.
*   **[DistributionGrid](distributiongrid.md)**: A decentralized swarm of agents (Experimental).
*   **[Junction](junction.md)**: A multi-agent discussion container where agents collaborate on a single output (Experimental).

---

## P2P Integration

Most containers implement `P2PInterface`. This allows a Container to act as a single P2P endpoint, while internally delegating requests to its child pipelines. This hides complex internal orchestration behind a simple agent interface.

```kotlin
class Connector : P2PInterface {
    override fun getPipelinesFromInterface(): List<Pipeline> {
        return branches.values.toList() // Expose managed pipelines
    }
}
```

---

## Tracing Support

Containers support Deep Tracing. When you enable tracing on a Container, it automatically propagates that configuration to every pipe in every child pipeline. This ensures you have a unified view of the entire orchestration event.

```kotlin
fun enableTracing(config: TraceConfig = TraceConfig()) {
    tracingEnabled = true
    // Automatically propagates to all child pipelines
    childPipelines.forEach { it.enableTracing(config) }
}
```

---

## When to Use Containers

**Use containers when:**
- Multiple pipelines need coordination.
- Key-based routing logic is required.
- Parallel execution is needed for performance or multi-perspective analysis.
- Task orchestration is complex (e.g., manager-worker patterns).

**Use direct pipe composition (Pipeline) when:**
- You have a simple sequential process.
- Single-stage logic is sufficient.
- No dynamic routing is needed.

---

## Implementation Status

| Container | Maturity | P2P Ready | Deep Tracing |
| :--- | :--- | :--- | :--- |
| **Connector** | ✅ Production | ✅ Yes | ✅ Yes |
| **MultiConnector** | ✅ Production | ✅ Yes | ❌ No |
| **Splitter** | ✅ Production | ✅ Yes | ✅ Yes |
| **Manifold** | ✅ Production | ✅ Yes | ✅ Yes |
| **DistributionGrid** | ⚠️ Experimental | ✅ Yes | ❌ No |
| **Junction** | ⚠️ Experimental | ❌ No | ❌ No |

---

## Basic Container Pattern

```kotlin
class Connector : P2PInterface {
    private val branches = mutableMapOf<Any, Pipeline>()

    fun add(key: Any, pipeline: Pipeline): Connector {
        branches[key] = pipeline
        return this
    }

    suspend fun execute(path: Any, content: MultimodalContent): MultimodalContent {
        val connection = branches[path]
        return connection?.execute(content) ?: content.apply { terminatePipeline = true }
    }

    override fun getPipelinesFromInterface(): List<Pipeline> = branches.values.toList()
}
```

---

## Next Steps

Start with the most common orchestration pattern: simple routing.

**→ [Connector - Pipeline Branching](connector.md)** - Key-based pipeline routing.
