# DistributionGrid

> 💡 **Tip:** `DistributionGrid` is TPipe's distributed grid harness. Think of it as a cluster of worker nodes where each node has a router (decides where work goes) and a worker (actually does the work). Tasks hop between nodes over P2P until complete.

## Table of Contents
- [What DistributionGrid Is](#what-distributiongrid-is)
- [When to Use It](#when-to-use-it)
- [How It Works: The Big Picture](#how-it-works-the-big-picture)
- [Internal Data Structures](#internal-data-structures)
- [The Router Contract](#the-router-contract)
- [The Worker Contract](#the-worker-contract)
- [Hook Points Reference](#hook-points-reference)
- [DSL Builder](#dsl-builder)
- [Manual Assembly](#manual-assembly)
- [Peer and Registry Discovery](#peer-and-registry-discovery)
- [Durable Checkpoints](#durable-checkpoints)
- [PCP Forwarding Policy](#pcp-forwarding-policy)
- [P2P Concurrency](#p2p-concurrency)
- [Common Startup Failures](#common-startup-failures)
- [Best Practices](#best-practices)

## What DistributionGrid Is

`DistributionGrid` is a distributed task routing system. Each grid **node** is a single TPipe agent instance (running as a coroutine within the TPipe process) with:

- A **router** pipeline — receives the task content, decides what to do next, writes a `DistributionGridDirective` back into content metadata
- A **worker** pipeline — receives the task content, does the actual work, returns the result
- Optional **peers** — other nodes this node can send work to

The grid handles:
- **Task routing** — router decides: run locally, send to a peer, or forward to a remote node
- **Remote handoff** — framing tasks as grid RPC over P2P
- **Session reuse** — cached P2P sessions for repeated peer communication
- **Retry logic** — routing policy controls whether to retry same peer or try another
- **Registry discovery** — optional registry for finding downstream nodes
- **Durable checkpoints** — save/resume for long-running or interruptible tasks

## When to Use It

Use `DistributionGrid` when:

- You have TPipe pipelines on different machines that need to coordinate
- You want to distribute LLM work across a cluster of agents
- You need reliability via retry and checkpoint mechanisms
- You're building a multi-agent system where one agent's output feeds into another across machines

**Don't use it** for simple single-machine fan-out — use `Splitter` instead.

## How It Works: The Big Picture

### End-to-End Flow

```
Caller                          Grid Node A                         Grid Node B
  │                                   │                                   │
  │  grid.execute(content)            │                                   │
  ├────────────────────────────────► │                                   │
  │                                   │  1. Wrap content in                 │
  │                                   │     DistributionGridEnvelope      │
  │                                   │                                   │
  │                                   │  2. Run router pipeline            │
  │                                   │     content = router.execute(content)
  │                                   │                                   │
  │                                   │  3. Read directive from            │
  │                                   │     content.metadata["distributionGridDirective"]
  │                                   │     → RUN_LOCAL_WORKER            │
  │                                   │     → HAND_OFF_TO_PEER             │
  │                                   │     → RETURN_TO_SENDER             │
  │                                   │     → TERMINATE                   │
  │                                   │                                   │
  │                                   │  4a. RUN_LOCAL_WORKER:            │
  │                                   │      Run worker pipeline           │
  │                                   │      → finalize as result          │
  │                                   │                                   │
  │                                   │  4b. HAND_OFF_TO_PEER:            │
  │                                   │      Serialize envelope as         │
  │                                   │      P2P request, send to peer    │
  │                                   ├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─► │
  │                                   │                     5. Peer node   │
  │                                   │                     receives P2P  │
  │                                   │                     request       │
  │                                   │                     runs router  │
  │                                   │                     runs worker  │
  │                                   │                     returns result│
  │                                   │◄ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤
  │                                   │  6. Receive response, finalize     │
  │                                   │     as terminal content           │
  │◄─────────────────────────────────┤                                   │
  │  result                           │                                   │
```

### Node Identity

Each node has:
- A **node ID** — stable identifier for this node (used in `originNodeId`, `currentNodeId`)
- A **transport** — how to reach this node (address + method)
- A **P2P descriptor** — outward-facing identity published for discovery

## Agent Contract

Understanding the input/output contract between the grid harness and your router/worker pipelines is critical for writing conforming components.

### The Router Contract

The router is a **pipeline** that receives `MultimodalContent` and must write a `DistributionGridDirective` into the content's metadata before returning.

#### What the Router Receives

The router receives `envelope.content` — which is `MultimodalContent`. At origin, this is the caller's input. At downstream nodes, it contains the accumulated task state.

The router also has access to the full `DistributionGridEnvelope` through the hook context, which includes:

- `envelope.taskId` — Stable task identifier
- `envelope.originNodeId` — Node that created the task
- `envelope.hopHistory` — Audit trail of all prior hops
- `envelope.currentObjective` — Current task text (may differ from original at downstream nodes)
- `envelope.attributes` — Extensible metadata map

#### What the Router Must Output

The router **must** write to `content.metadata["distributionGridDirective"]` a parsed `DistributionGridDirective` object. The grid reads this after your pipeline returns.

**Minimal working router that always runs locally:**

```kotlin
val routerPipeline = Pipeline().apply {
    pipelineName = "grid-router"
    add(BedrockPipe().apply {
        setPipeName("router")
        setJsonOutput(DistributionGridDirective())
        setSystemPrompt("""
            You are a DistributionGrid router. Your job is to return a routing directive.

            Return this JSON: {"kind": "RUN_LOCAL_WORKER", "notes": "default routing"}

            Do not change the kind — always route to local worker for now.
        """.trimIndent())
    })
}
```

**Router that can dispatch to peers:**

```kotlin
val routerPipeline = Pipeline().apply {
    pipelineName = "grid-router"
    add(BedrockPipe().apply {
        setPipeName("router")
        setJsonOutput(DistributionGridDirective())
        setSystemPrompt("""
            You are a DistributionGrid router.

            Analyze the task and choose a directive:
            - {"kind": "RUN_LOCAL_WORKER", "notes": "..."} → run on local worker
            - {"kind": "HAND_OFF_TO_PEER", "targetPeerId": "peer-key", "notes": "..."} → send to peer

            If the task is simple and self-contained, run locally.
            If the task requires specialized capabilities from another peer, dispatch there.
        """.trimIndent())
    })
}
```

#### Directive Resolution

The grid reads the directive from metadata using a key constant:

```kotlin
private const val DIRECTIVE_METADATA_KEY = "distributionGridDirective"
```

```kotlin
val contentDirective = content.metadata[DIRECTIVE_METADATA_KEY] as? DistributionGridDirective
```

**If the router doesn't write a directive**, the grid falls back to `DistributionGridDirective(kind = RUN_LOCAL_WORKER)`.

### The Worker Contract

The worker is a **pipeline** that receives `MultimodalContent` and returns the work result as `MultimodalContent`.

#### What the Worker Receives

The worker receives `envelope.content` — the same `MultimodalContent` that the router received and potentially modified. The worker should not need to understand the envelope structure.

#### What the Worker Must Output

The worker should return its result as `MultimodalContent` (with `text` containing the output). The grid wraps this in a `DistributionGridOutcome` and returns it to the caller.

**Minimal working worker:**

```kotlin
val workerPipeline = Pipeline().apply {
    pipelineName = "grid-worker"
    add(BedrockPipe().apply {
        setPipeName("worker")
        setSystemPrompt("""
            You are a DistributionGrid worker. Execute the task and return your result.
            Return the best answer you can produce.
        """.trimIndent())
    })
}
```

### DSL Settings That Affect the Contract

| Setting | Effect on Contract |
|---------|-------------------|
| `routing { maxHopCount(n) }` | Limits how many times a task can hop. Workers don't need to track this; the grid enforces it. |
| `routing { allowRetrySamePeer(true/false) }` | Whether a failed peer dispatch retries the same peer. |
| `routing { allowRemotePcpForwarding(true/false) }` | Whether PCP payloads are forwarded to remote nodes. Affects what workers see. |
| `memory { outboundTokenBudget(n) }` | Shapes outbound memory. The router receives truncated context if the budget is tight. |
| `memory { summaryBudget(n) }` | Budget for memory summarization if enabled. |
| `hooks { beforeRoute { } }` | Intercepts before router runs. Can modify content or add attributes. |
| `hooks { beforeLocalWorker { } }` | Intercepts after router returns `RUN_LOCAL_WORKER`. |
| `hooks { afterLocalWorker { } }` | Intercepts after worker completes. Can add execution notes. |
| `hooks { beforePeerDispatch { } }` | Intercepts before peer handoff. Can set PCP forwarding flag. |
| `hooks { afterPeerResponse { } }` | Intercepts after peer response. |
| `killSwitch(input, output, onTripped)` | Halts execution if token limits are exceeded. |
| `concurrencyMode(ISOLATED)` | Required for P2P exposure. Each request gets a fresh grid state. |

### Envelope Lifecycle Contract

The `DistributionGridEnvelope` flows through the grid:

1. **Created at origin** — `taskId` (UUID), `originNodeId`, `originTransport` set at creation
2. **Wrapped at each hop** — `senderNodeId`, `senderTransport` updated before dispatch
3. **Worker receives only content** — Worker sees `MultimodalContent`, not the envelope
4. **Results wrapped in outcome** — Grid produces `DistributionGridOutcome` with `finalContent`, `hopCount`, `completionNotes`

### Failure Handling Contract

Workers should return well-formed output. Failures are tracked in `DistributionGridFailure`:

```kotlin
data class DistributionGridFailure(
    var kind: DistributionGridFailureKind = DistributionGridFailureKind.UNKNOWN,
    var sourceNodeId: String = "",
    var targetNodeId: String = "",
    var reason: String = "",
    var retryable: Boolean = false
)
```

Failure kinds: `HANDSHAKE_REJECTED`, `SESSION_REJECTED`, `TRUST_REJECTED`, `POLICY_REJECTED`, `ROUTING_FAILURE`, `WORKER_FAILURE`, `TRANSPORT_FAILURE`, `VALIDATION_FAILURE`, `DURABILITY_FAILURE`, `UNKNOWN`.

The grid can retry `retryable = true` failures based on `routingPolicy`. Non-retryable failures terminate the task.

### Checkpoint Contract

If `setDurableStore(...)` is configured, the grid checkpoints at:

- `before-peer-dispatch` — Before sending to a peer
- `after-local-worker` — After local worker completes
- `after-peer-response` — After receiving peer response

Your durable store implementation must handle `checkpointState(envelope, reason)` and `resumeState(taskId)`. On resume, the grid reconstructs the envelope and continues from the checkpointed point.

## Internal Data Structures

### DistributionGridEnvelope

The envelope is the core data structure that flows through the grid. It's a `@Serializable` data class:

```kotlin
data class DistributionGridEnvelope(
    var taskId: String = "",                    // Stable task ID (UUID generated at origin)
    var originNodeId: String = "",              // Node that originally created this task
    var originTransport: P2PTransport = P2PTransport(),
    var senderNodeId: String = "",             // Node that sent this hop
    var senderTransport: P2PTransport = P2PTransport(),
    var currentNodeId: String = "",             // Node currently processing this envelope
    var currentTransport: P2PTransport = P2PTransport(),
    var content: MultimodalContent = MultimodalContent(),  // The actual task content
    var taskIntent: String = "",                // Original task text (set at origin)
    var currentObjective: String = "",           // Current task text (may change at each hop)
    var routingPolicy: DistributionGridRoutingPolicy = DistributionGridRoutingPolicy(),
    var tracePolicy: DistributionGridTracePolicy = DistributionGridTracePolicy(),
    var credentialPolicy: DistributionGridCredentialPolicy = DistributionGridCredentialPolicy(),
    var executionNotes: MutableList<String> = mutableListOf(),  // Human-readable log
    var hopHistory: MutableList<DistributionGridHopRecord> = mutableListOf(),  // Audit trail
    var completed: Boolean = false,             // Terminal flag
    var latestOutcome: DistributionGridOutcome? = null,  // Final result
    var latestFailure: DistributionGridFailure? = null,      // Failure record
    var durableStateKey: String = "",          // Checkpoint key for durability
    var sessionRef: DistributionGridSessionRef? = null,      // Negotiated session
    var attributes: MutableMap<String, String> = mutableMapOf()  // Extensible metadata
)
```

The envelope is **not** what you send to the router or worker. Instead, the grid extracts `envelope.content` and passes that as `MultimodalContent` to your pipelines. After your pipeline runs, the grid reads back the result from `content.text` and `content.metadata`.

### DistributionGridDirective

The router writes its decision into `content.metadata["distributionGridDirective"]` as a `DistributionGridDirective`:

```kotlin
data class DistributionGridDirective(
    var kind: DistributionGridDirectiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
    var targetNodeId: String = "",              // For HAND_OFF_TO_PEER: target node ID
    var targetPeerId: String = "",              // For HAND_OFF_TO_PEER: peer key to dispatch to
    var targetTransport: P2PTransport? = null,  // Optional explicit transport target
    var notes: String = "",                     // Human-readable router notes
    var alternatePeerIds: MutableList<String> = mutableListOf(),  // Fallback peers
    var rejectReason: String = ""               // For REJECT/TERMINATE: why
)
```

**Directive kinds:**

| Kind | Meaning |
|------|---------|
| `RUN_LOCAL_WORKER` | Run the task on the local worker |
| `HAND_OFF_TO_PEER` | Send to a peer (set `targetPeerId` or `targetNodeId`) |
| `RETURN_TO_SENDER` | Return to the immediate sender |
| `RETURN_TO_ORIGIN` | Return all the way to the origin node |
| `RETURN_TO_TRANSPORT` | Return to a specific transport address |
| `RETRY_SAME_PEER` | Retry the same peer (after failure) |
| `TRY_ALTERNATE_PEER` | Try the next peer in `alternatePeerIds` |
| `REJECT` | Reject the task (policy violation) |
| `TERMINATE` | Terminate the task (unrecoverable) |

### DistributionGridOutcome

When the grid reaches a terminal state, it produces a `DistributionGridOutcome`:

```kotlin
data class DistributionGridOutcome(
    var status: DistributionGridOutcomeStatus = DistributionGridOutcomeStatus.SUCCESS,
    var returnMode: DistributionGridReturnMode = DistributionGridReturnMode.RETURN_TO_SENDER,
    var taskId: String = "",
    var finalContent: MultimodalContent = MultimodalContent(),  // The terminal result
    var completionNotes: String = "",
    var hopCount: Int = 0,
    var finalNodeId: String = "",
    var terminalFailure: DistributionGridFailure? = null
)
```

### DistributionGridFailure

Failures are recorded as:

```kotlin
data class DistributionGridFailure(
    var kind: DistributionGridFailureKind = DistributionGridFailureKind.UNKNOWN,
    var sourceNodeId: String = "",
    var targetNodeId: String = "",
    var transportMethod: Transport = Transport.Tpipe,
    var transportAddress: String = "",
    var reason: String = "",
    var policyCause: String = "",
    var retryable: Boolean = false
)
```

Failure kinds: `HANDSHAKE_REJECTED`, `SESSION_REJECTED`, `TRUST_REJECTED`, `POLICY_REJECTED`, `ROUTING_FAILURE`, `WORKER_FAILURE`, `TRANSPORT_FAILURE`, `VALIDATION_FAILURE`, `DURABILITY_FAILURE`, `UNKNOWN`.

## Hook Points Reference

Hooks let you intercept the execution at specific points. All hooks receive a `DistributionGridEnvelope` and return a (possibly modified) envelope.

### beforeRoute

Fires before the router runs.

```kotlin
hooks {
    beforeRoute { envelope ->
        // Modify content, add attributes, or log before routing decision
        envelope.attributes["custom-flag"] = "value"
        envelope
    }
}
```

### beforeLocalWorker

Fires before the local worker runs (after router returned `RUN_LOCAL_WORKER`).

```kotlin
hooks {
    beforeLocalWorker { envelope ->
        // Inspect or modify content before worker execution
        envelope
    }
}
```

### afterLocalWorker

Fires after the local worker completes successfully.

```kotlin
hooks {
    afterLocalWorker { envelope ->
        // Modify result content, add execution notes
        envelope.executionNotes.add("Worker completed at ${System.currentTimeMillis()}")
        envelope
    }
}
```

### beforePeerDispatch

Fires before sending to a peer (after router returned `HAND_OFF_TO_PEER`).

```kotlin
hooks {
    beforePeerDispatch { envelope ->
        // Can set PCP forwarding flag here
        // envelope.attributes["distributionGridAllowRemotePcpForwarding"] = "true"
        envelope
    }
}
```

### afterPeerResponse

Fires after receiving a response from a peer.

```kotlin
hooks {
    afterPeerResponse { envelope ->
        // Inspect or modify the peer response before finalization
        envelope
    }
}
```

### outboundMemory

Fires before outbound memory shaping (if memory policy is configured).

```kotlin
hooks {
    outboundMemory { envelope ->
        // Custom memory shaping logic
        envelope
    }
}
```

### failure

Fires when a failure is recorded.

```kotlin
hooks {
    failure { envelope ->
        // Log or annotate the failure
        envelope.executionNotes.add("Failure at ${envelope.latestFailure?.reason}")
        envelope
    }
}
```

### outcomeTransformation

Fires when producing the final terminal content.

```kotlin
hooks {
    outcomeTransformation { content, envelope ->
        // Transform final output
        content
    }
}
```

## DSL Builder

The Kotlin DSL is the preferred way to assemble a grid:

```kotlin
import com.TTT.Pipeline.distributionGrid
import com.TTT.P2P.P2PConcurrencyMode

val grid = distributionGrid {
    // P2P identity for this node
    p2p {
        agentName("my-grid-node")
        transportAddress("my-grid-node")
        transportMethod(Transport.Tpipe)
    }

    // Router and worker (required)
    router(routerPipeline)
    worker(workerPipeline)

    // Routing policy
    routing {
        allowRetrySamePeer(true)
        maxRetryCount(1)
        maxHopCount(8)
        allowRemotePcpForwarding(false)
    }

    // Memory policy
    memory {
        outboundTokenBudget(4096)
        summaryBudget(512)
    }

    // Tracing
    tracing {
        enabled()
    }

    // Orchestration hooks
    hooks {
        beforeRoute { envelope -> envelope }
        afterLocalWorker { envelope -> envelope }
    }

    // P2P concurrency
    concurrencyMode(P2PConcurrencyMode.ISOLATED)

    // Kill switch
    killSwitch(inputTokenLimit = 100000, outputTokenLimit = 10000)
}
```

The DSL returns an initialized grid ready for `execute()`.

## Manual Assembly

For manual assembly without DSL:

```kotlin
import com.TTT.Pipeline.distributionGridBuilder

val grid = distributionGridBuilder()
    .router(routerPipeline)
    .worker(workerPipeline)
    .setRoutingPolicy(DistributionGridRoutingPolicy().apply {
        maxHopCount = 8
    })
    .setMemoryPolicy(DistributionGridMemoryPolicy().apply {
        outboundTokenBudget = 4096
    })
    .enableTracing()
    .concurrencyMode(P2PConcurrencyMode.ISOLATED)
    .killSwitch(inputTokenLimit = 50000, outputTokenLimit = 5000)
    .build()
```

### Lifecycle Methods

| Method | Description |
|--------|-------------|
| `init()` | Validate bindings and initialize the grid |
| `pause()` / `resume()` | Pause or resume execution |
| `isPaused()` | Check pause state |
| `clearRuntimeState()` | Clear session, pause flags, discovered state |
| `clearTrace()` | Clear trace data |
| `resumeTask(taskId)` | Resume a checkpointed task |
| `getTraceReport()` | Get formatted trace output |
| `getFailureAnalysis()` | Get structured failure report |

## Peer and Registry Discovery

### Adding a Local Peer

```kotlin
grid.addPeer(localPeerPipeline)

// With custom descriptor
grid.addPeer(localPeerPipeline, myDescriptor, myRequirements)
```

### Adding an External Peer Descriptor

```kotlin
grid.addPeerDescriptor(externalPeerDescriptor)
```

### Registry-based Discovery

```kotlin
// Add trusted bootstrap registry
grid.addBootstrapRegistry(registryAdvertisement)

// Register with registry and maintain lease
grid.registerWithRegistry(registryId, leaseRequest)
grid.renewRegistryLease(registryId, leaseId)
grid.tickRegistryMemberships()

// Query registry for nodes
grid.queryRegistries(registryQuery)
```

## Durable Checkpoints

For long-running tasks that may be interrupted:

```kotlin
// Configure durable store
grid.setDurableStore(myDurableStore)

// Resume interrupted task
val result = grid.resumeTask(taskId)
```

The durable store interface:

```kotlin
interface DistributionGridDurableStore {
    suspend fun checkpointState(envelope: DistributionGridEnvelope, reason: String)
    suspend fun resumeState(taskId: String): DistributionGridDurableState?
}
```

Checkpoint reasons: `before-peer-dispatch`, `after-local-worker`, `after-peer-response`.

## PCP Forwarding Policy

By default, PCP payloads are stripped before remote handoff. Enable via routing policy:

```kotlin
routing {
    allowRemotePcpForwarding(true)
}
```

Or per-dispatch via hook:

```kotlin
hooks {
    beforePeerDispatch { envelope ->
        envelope.attributes["distributionGridAllowRemotePcpForwarding"] = "true"
        envelope
    }
}
```

## P2P Concurrency

`DistributionGrid` is stateful — register with `P2PConcurrencyMode.ISOLATED`:

```kotlin
grid.concurrencyMode(P2PConcurrencyMode.ISOLATED)
P2PRegistry.register(grid)
```

See [P2P Registry and Routing](../advanced-concepts/p2p/p2p-registry-and-routing.md#concurrency-modes) for details.

## Common Startup Failures

### `DistributionGrid requires a router before init().`

No router bound. Call `setRouter(routerPipeline)` or use `router { }` in DSL.

### `DistributionGrid requires a worker before init().`

No worker bound. Call `setWorker(workerPipeline)` or use `worker { }` in DSL.

### `Peer 'X' is already registered locally.`

Duplicate peer key. Call `removePeer("X")` first or use `replacePeer("X", newPeer)`.

### `Router did not write a directive.`

Router didn't set `content.metadata["distributionGridDirective"]`. Ensure your router pipeline outputs a `DistributionGridDirective` JSON via `setJsonOutput(DistributionGridDirective())`.

## Best Practices

- **Router must output a directive** — if your router pipeline doesn't write to `content.metadata["distributionGridDirective"]`, the grid defaults to `RUN_LOCAL_WORKER`
- **Use the DSL** — it handles validation and initialization in one place
- **Worker overflow protection** — configure token budgeting or truncation on worker pipes
- **Use `P2PConcurrencyMode.ISOLATED`** when registering via P2P
- **Configure durable store** for tasks that take more than a few seconds
- **Set `maxHopCount`** appropriately — too low and tasks won't reach destination, too high wastes resources
- **Enable tracing during development** to see routing decisions and hop paths

---

**Previous:** [← Manifold](manifold.md) | **Next:** [Junction →](junction.md)

## Next Steps

- [Cross-Cutting Topics](cross-cutting-topics.md) - Continue to shared container concepts.