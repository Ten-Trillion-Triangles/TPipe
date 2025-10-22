# P2PInterface API

## Table of Contents
- [Overview](#overview)
- [Public Functions](#public-functions)
  - [P2P Configuration](#p2p-configuration)
  - [Container Management](#container-management)
  - [Pipeline Access](#pipeline-access)
  - [Execution Methods](#execution-methods)

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
