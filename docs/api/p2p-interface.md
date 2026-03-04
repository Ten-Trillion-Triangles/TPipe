# P2PInterface API

The `P2PInterface` is the standard connector that allows any TPipe component—whether it is a single Pipe, a multi-stage Pipeline, or a complex Orchestration Container—to become an addressable, discoverable **Agent** in a distributed system. It defines the contract for how components describe their capabilities, manage their transport, and execute inter-agent requests.

```kotlin
interface P2PInterface
```

## Table of Contents
- [P2P Configuration](#p2p-configuration)
- [Container and Pipeline Management](#container-and-pipeline-management)
- [Execution Methods](#execution-methods)
- [Key Operational Behaviors](#key-operational-behaviors)

---

## P2P Configuration: The Agent Specification

These methods allow a component to define its Identity Card for the P2P network.

#### `setP2pDescription(description: P2PDescriptor)`
Sets the technical specification for the agent. This includes the agent's name, its role description, and its available Skills (skills are metadata that help other agents decide if they should call this one).

#### `setP2pTransport(transport: P2PTransport)`
Defines the Pipe Type used for communication. It specifies the transport method (e.g., TPipe for in-process, HTTP for remote) and the exact address where the agent can be reached.

#### `setP2pRequirements(requirements: P2PRequirements)`
Sets the Security Gate for the agent. This defines exactly what a requesting agent is allowed to do, such as whether it can modify JSON schemas, inject custom context, or if it requires authentication.

---

## Container and Pipeline Management

These functions maintain the structural integrity and hierarchy of the agentic infrastructure.

#### `setContainerObject(container: Any)` / `getContainerObject(): Any?`
Used to link a component to its parent container (e.g., linking a Pipeline to a Manifold). This is critical for high-resolution tracing, ensuring that the Flow Meter data is correctly propagated up the hierarchy.

#### `getPipelinesFromInterface(): List<Pipeline>`
The "Inventory Check." It returns all the mainlines managed by this interface.
- **Pipelines**: Return a list containing themselves.
- **Containers**: Return all branches or worker pipelines they coordinate.
- **Pipes**: Typically return an empty list.

---

## Execution Methods: The Two Flow Modes

#### `executeP2PRequest(request: P2PRequest): P2PResponse?`
The **Standard Protocol** flow. This method executes a full, high-security P2P interaction.
- **Logic**: It handles schema modification, context binding, and custom instructions provided by the caller.
- **Isolation**: In most implementations, it performs **Pipeline Copying**, creating a temporary duplicate of the mainline to ensure that one caller's custom instructions don't permanently alter the agent's blueprint for everyone else.

#### `executeLocal(content: MultimodalContent): MultimodalContent`
The **Direct Flow** mode. It executes the component's internal logic without the overhead of the P2P protocol or security validation.
- **Use Case**: Best for internal orchestration where one component is calling another within the same trust boundary or for performance-critical local operations.

---

## Key Operational Behaviors

### 1. Unified Identity
By implementing this interface, a complex cluster (like a Splitter containing 10 pipelines) can present itself to the network as a single, simple agent, hiding its internal complexity.

### 2. Gradual Adoption
The interface provides default "No-op" implementations for all methods. This allows developers to gradually add P2P capabilities to their infrastructure without needing to implement every feature at once.

### 3. Security-First Architecture
The P2PInterface ensures that every inter-agent call is governed by the `P2PRequirements` gate. This prevents unauthorized callers from "Hijacking" an agent's logic or dumping massive amounts of data into its context reservoirs.
