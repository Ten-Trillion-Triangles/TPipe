# P2PInterface API

## Table of Contents
- [Overview](#overview)
| [Public Functions](#public-functions)
  - [P2P Configuration](#p2p-configuration)
  - [Container Management](#container-management)
  - [Pipeline Access](#pipeline-access)
  - [Execution Methods](#execution-methods)
  - [Recursive Propagation](#recursive-propagation)

## Overview

The `P2PInterface` enables Pipe-to-Pipe communication, allowing TPipe components to be registered as addressable agents in a distributed system. It provides standardized methods for configuration, discovery, and execution across containers and pipelines.

```kotlin
interface P2PInterface
```

## Public Functions

### P2P Configuration

#### `setP2pDescription(description: P2PDescriptor)`
Sets the P2P agent descriptor containing identification and capability information.

**Behavior:** Default implementation is empty. Implementing classes should store the descriptor for agent registration and discovery. The descriptor contains agent name, description, transport details, and capability flags.

#### `getP2pDescription(): P2PDescriptor?`
Retrieves the P2P agent descriptor.

**Behavior:** Default implementation returns null. Implementing classes should return the stored descriptor used for P2P system registration and agent discovery.

#### `setP2pTransport(transport: P2PTransport)`
Sets the transport configuration for P2P communication.

**Behavior:** Default implementation is empty. Implementing classes should store transport details including transport method (TPipe, HTTP, etc.) and addressing information for agent connectivity.

#### `getP2pTransport(): P2PTransport?`
Retrieves the P2P transport configuration.

**Behavior:** Default implementation returns null. Implementing classes should return transport configuration used for establishing P2P connections.

#### `setP2pRequirements(requirements: P2PRequirements)`
Sets security and compatibility requirements for P2P interactions.

**Behavior:** Default implementation is empty. Implementing classes should store requirements that define allowed operations, security constraints, and compatibility settings for P2P requests.

#### `getP2pRequirements(): P2PRequirements?`
Retrieves the P2P requirements configuration.

**Behavior:** Default implementation returns null. Implementing classes should return requirements used for validating and filtering incoming P2P requests.

---

### Container Management

#### `setContainerObject(container: Any)`
Sets reference to parent container holding this P2P-enabled object.

**Behavior:** Default implementation is empty. Used when pipelines or pipes are embedded within containers (Connector, Splitter, Manifold) to maintain parent-child relationships for advanced tracing and coordination.

#### `getContainerObject(): Any?`
Retrieves reference to parent container.

**Behavior:** Default implementation returns null. Enables access to parent container for context sharing, tracing integration, and hierarchical management in complex orchestration scenarios.

#### `setParentInterface(parent: P2PInterface)`
Sets the parent interface to any child P2PInterface object. This enables generic pass-through of interface data during complex container operations.

**Behavior:** Default implementation is empty. Implementing classes should store the parent reference for hierarchical traversal and interface delegation. Used by container classes (Pipeline, Manifold, Junction, Connector, Splitter, MultiConnector) and Pipe to maintain parent-child relationships.

#### `getParentP2PInterface(): P2PInterface?`
Retrieves the parent P2PInterface owned by the object above the current object.

**Behavior:** Default implementation returns null. Enables upward traversal of the P2PInterface ownership tree. Used for recursive operations and hierarchical context propagation.

#### `getTopLevelParentInterface(): P2PInterface?`
Recursively searches for the top-level parent interface by traversing up the ownership tree.

**Behavior:** Default implementation follows the parent chain until reaching a node with no parent. Returns the root of the hierarchy.

#### `getNearestPumpStationParent(): P2PInterface?`
Traverses upward through the P2PInterface ownership tree to find the nearest `PumpStation`.

**Behavior:** Returns the first `PumpStation` ancestor found, or null if none exists in the parent chain. Required because PumpStation contains features like paths that need to be pulled in by child agents responsible for routing.

---

### Pipeline Access

#### `getPipelinesFromInterface(): List<Pipeline>`
Retrieves all pipelines managed by this P2P interface.

**Behavior:** 
- **Default**: Returns empty list
- **Containers**: Should return all managed pipelines (e.g., Connector returns all branch pipelines)
- **Pipelines**: Should return list containing self
- **Pipes**: Should return empty list as pipes don't manage pipelines

Used by P2P system for pipeline discovery and routing decisions.

---

### Execution Methods

#### `executeP2PRequest(request: P2PRequest): P2PResponse?`
Executes P2P requests with advanced features and protocol compliance.

**Behavior:** 
- **Default**: Returns null (no P2P support)
- **Advanced Features**: Implementing classes should handle:
  - **Schema Modification**: Dynamic JSON input/output schema updates
  - **Context Binding**: Request-specific context injection
  - **Custom Instructions**: Per-pipe instruction overrides
  - **Security Validation**: Requirements-based request filtering
  - **Pipeline Copying**: Temporary pipeline duplication for isolation

**P2P Protocol Support:**
- Request validation against P2P requirements
- Context isolation and security enforcement
- Response formatting and metadata inclusion
- Error handling and failure reporting

#### `executeLocal(content: MultimodalContent): MultimodalContent`
Executes content locally without P2P protocol overhead.

**Behavior:** 
- **Default**: Returns content unchanged (pass-through)
- **Containers**: Should execute internal logic (routing, orchestration, etc.)
- **Pipelines**: Should execute pipeline with content
- **Direct Execution**: Bypasses P2P system for embedded scenarios

**Use Cases:**
- **Embedded Containers**: Avoid circular references when containers are embedded in pipes
- **Performance**: Skip P2P overhead for local execution
- **Testing**: Direct execution without P2P setup requirements

## Key Behaviors

### Interface Contract
P2PInterface provides default implementations for all methods, making it optional for implementing classes to override only needed functionality. This enables gradual P2P adoption and selective feature implementation.

### Agent Registration
Classes implementing P2PInterface can be registered in the P2P system using their descriptor, transport, and requirements configuration. The P2P registry uses these components for agent discovery and routing.

### Security Model
P2P requirements define security boundaries including:
- **Authentication requirements**
- **Allowed operations** (context modification, schema changes)
- **External connection permissions**
- **Agent duplication policies**

### Container Integration
The container object reference enables sophisticated orchestration scenarios where P2P-enabled components maintain awareness of their hierarchical context for tracing, coordination, and resource management.

### Execution Flexibility
Dual execution methods (P2P vs local) provide flexibility for different integration patterns:
- **P2P execution**: Full protocol compliance with security and isolation
- **Local execution**: Direct integration with performance optimization

### Pipeline Discovery
The pipeline access method enables P2P system to understand component structure and make intelligent routing decisions based on available pipelines and their capabilities.

### Default Implementations
All methods have sensible defaults enabling implementing classes to selectively override only required functionality, reducing implementation burden while maintaining interface compliance.

### Recursive Propagation

A uniform pattern across P2PInterface: each `*Recursive` method drills the entire agent tree (containers, then leaf pipes) and applies the same operation at every node. This propagates settings to descendants even across multi-level compositions (Pipeline-in-Pipeline, Manifold-with-Containers-in-Pipelines, PumpStation-with-mixed-agents).

The recursive methods share one shape:

| Method | Purpose | Pipe-side leaf handler |
|--------|---------|----------------------|
| `setTokenBudgetRecursive(budget: TokenBudgetSettings)` | Apply token budget to every descendant pipe | `setTokenBudget` |
| `setPipeSettingsRecursively(settings: PipeSettings)` | Apply pipe settings to every descendant pipe | `applyPipeSettings` |
| `setStreamingCallbackRecursive(callback: suspend (String) -> Unit)` | Register a per-chunk streaming callback on every leaf pipe | `propagateStreamingCallback` |
| `removeStreamingCallbackRecursive(callback: suspend (String) -> Unit)` | Remove only that callback by identity without disabling other callbacks | callback-manager removal and provider legacy-field cleanup |
| `supportsStreamingCallbackRemoval()` | Report whether callback-specific removal is supported | `true` for TPipe implementations; `false` by default for legacy P2P implementations |
| `enableStallDetectorRecursive(config, callback)` | Enable stall detection on every leaf pipe | `propagateStallDetection` |
| `setConverseRoleRecursive(role: ConverseRole)` | Set converse role on every leaf pipe | `setConverseRole` |
| `suspend abortRecursive()` | Abort every leaf pipe's current execution | `abort` (delegates to `propagateAbortRecursively`) |
| `enablePipeTimeoutRecursive(applyRecursively, duration, autoRetry, retryLimit)` | Configure pipe timeout on every leaf pipe | `enablePipeTimeout` |

Each method:

1. Has a no-op default body on the interface — non-Pipeline implementations opt out by not overriding.
2. Is overridden on every P2PInterface implementer that owns children: `Pipeline`, `Manifold`, `Junction`, `Splitter`, `Connector`, `MultiConnector`, `DistributionGrid`, `PumpStation` — each iterates its own pipes and calls `pipe.<method>(...)` on each, so the recursion walks down through every container layer.
3. Has a corresponding override on `Pipe` that delegates: if `containerPtr == null` (the pipe is a leaf in its current container), apply locally; otherwise call `containerPtr.<method>(...)` to drill upward.

#### `abortRecursive()` (suspend)
Cancels the current execution on every leaf pipe in this interface's agent tree. Walks the entire container hierarchy from any node where it's invoked — calling `manifold.abortRecursive()` triggers the cascade across the manager pipeline and every worker.

**Parameters:** None.

**Behavior:** Each container override iterates its children and calls `abortRecursive()` on each. The base `Pipe` override resolves the leaf behavior: if the pipe is a leaf (no `containerPtr`), it calls `abort()` which delegates to `propagateAbortRecursively`, walking the pipe's own validator / transformation / branch / reasoning child pipes and their own children. Cycle-safe via `pipeId`-keyed `visited` set — a pipe reachable from multiple containers is cancelled exactly once.

**Use cases:**
- Cascade cancel a running multi-agent task from the top of the tree.
- Emergency stop when a watch dog timer fires or a kill-switch trips.

**Example:**
```kotlin
val tree: P2PInterface = manifold { /* ... */ }
// ... tree is running ...
tree.abortRecursive()
// every leaf pipe across the entire agent tree is now cancelled
```

**Error path:** Returns normally on success. If a child pipe's `abort()` throws, the exception propagates after completing the current iteration (no upstream cascade stops on first failure, but the exception does not roll back previously-completed aborts).

#### `enablePipeTimeoutRecursive(applyRecursively: Boolean = true, duration: Long = 300000, autoRetry: Boolean = false, retryLimit: Int = 5)`
Configures pipe-timeout on every leaf pipe in this interface's agent tree using safe-to-propagate parameters. The signature exposes only the parameters that can be applied uniformly across a tree — `customLogic` is intentionally NOT exposed because it's bound to a specific pipe instance and would leak incorrectly across the cascade. Each leaf retains its own custom retry logic if any was set.

**Parameters:**
- **`applyRecursively`**: Whether each leaf should propagate to its own descendant pipes. Defaults to `true`.
- **`duration`**: Timeout duration in milliseconds. Defaults to 300000 (5 minutes).
- **`autoRetry`**: Whether to enable automatic retry on timeout. Defaults to `false`. Mutually exclusive with the per-pipe `customLogic` retry function (which is NOT propagated).
- **`retryLimit`**: Maximum retry attempts. Defaults to `5`.

**Behavior:** Each container override iterates its children and calls `enablePipeTimeoutRecursive(...)` on each. The base `Pipe` override resolves the leaf behavior: if the pipe is a leaf (no `containerPtr`), it calls `enablePipeTimeout(...)` locally; otherwise it delegates upward to `containerPtr.enablePipeTimeoutRecursive(...)` to drill toward the leaves. This matches the direct-child propagation in `Pipeline.init` and gives the root caller a single source of truth for timeout configuration across the entire agent tree.

**Use cases:**
- Standardize a single timeout configuration across a multi-agent tree.
- Apply an emergency short-timeout across all leaves when token-budget pressure is high.

**Example:**
```kotlin
val tree: P2PInterface = distributionGrid { /* ... */ }
tree.enablePipeTimeoutRecursive(
    applyRecursively = true,
    duration = 120000,         // 2 minutes per leaf
    autoRetry = true,
    retryLimit = 3
)
// every leaf pipe across the entire grid now has a 2-minute timeout with up to 3 retries
```

**Custom logic preserved:** Custom retry functions set on individual pipes via `setRetryFunction(...)` are not replaced by the recursive call. To replace custom logic across the tree, call `setRetryFunction(...)` on each leaf directly via the existing `*Recursive` overrides or by iterating `getPipes()`.
## Next Steps

- [P2P Package API](p2p-package.md) - Continue into the distributed agent package.
