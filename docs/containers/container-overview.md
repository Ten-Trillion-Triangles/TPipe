# Container Overview - Infrastructure Orchestration

While a **Pipeline** represents a single mainline, **Containers** are the complex infrastructure—the pumping stations, treatment plants, and distribution grids that coordinate multiple mainlines. Containers provide high-level orchestration patterns that go beyond simple sequential execution.

In the TPipe ecosystem, Containers allow you to route data, run processes in parallel, and manage swarms of agents with industrial precision.

## Containers vs. Pipelines

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

## P2P & Tracing: Unified Visibility

Even though Containers manage complex, multi-pipeline flows, they remain fully integrated into the TPipe ecosystem:

### P2P Integration
Most containers implement `P2PInterface`. This means a Container can act as a single P2P endpoint, while internally delegating requests to its child pipelines. This allows you to hide complex internal orchestration behind a simple agent interface.

### Tracing Support
Containers support Deep Tracing. When you enable tracing on a Container, it automatically propagates that configuration to every pipe in every child pipeline. This ensures you have a unified view of the entire orchestration event.

```kotlin
// Enable tracing on the entire station
manifold.enableTracing(TraceConfig(logLevel = LogLevel.DEBUG))
```

---

## When to Reach for a Container?

> [!TIP]
> **Use a Pipeline** when you have a clear, step-by-step process that always follows the same path.
>
> **Use a Container** when:
> *   You need to choose between different logic paths at runtime.
> *   You want to run multiple models in parallel to save time.
> *   You're building a Team of agents with different specialties.
> *   You need to scale your processing across a distributed grid.

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

## Next Steps

Start with the most common orchestration pattern: simple routing.

**→ [Connector - Pipeline Branching](connector.md)** - Key-based pipeline routing.
