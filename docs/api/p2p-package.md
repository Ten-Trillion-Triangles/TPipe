# P2P Package API

The P2P package is the Inter-Municipal Infrastructure of the TPipe ecosystem. It provides the protocols, registries, and security gates required for distributed agent communication. This package allows agents to discover each other, verify capabilities, and collaborate on complex tasks across different pipelines or even separate services.

## Table of Contents
- [P2PDescriptor: The Identity Card](#p2pdescriptor-the-identity-card)
- [P2PRequest: The Cargo Unit](#p2prequest-the-cargo-unit)
- [P2PResponse: The Result Packet](#p2presponse-the-result-packet)
- [P2PRequirements: The Security Gate](#p2prequirements-the-security-gate)
- [P2PRegistry: The Central Switching Station](#p2pregistry-the-central-switching-station)
- [Key Operational Behaviors](#key-operational-behaviors)

---

## P2PDescriptor: The Identity Card
The `P2PDescriptor` is the exhaustive technical specification for an agent. It tells the network exactly what the agent is called, what it can do, and how it must be reached.

*   **Agent Identity**: `agentName`, `agentDescription`, and `agentSkills` (used by LLMs to determine if they should call this agent).
*   **Capabilities**:
    *   `allowsAgentDuplication`: If true, the agent supports on-the-fly "Cloning" to handle custom requests without affecting the main template.
    *   `allowsCustomAgentJson`: If true, the caller can dynamically override the agent's JSON output schemas.
    *   `usesConverse`: Indicates the agent prefers structured conversation history over raw text prompts.
*   **Protocol Support**: `contextProtocol` (e.g., PCP, MCP) and `pcpDescriptor` for listing available tools.

---

## P2PRequest: The Cargo Unit
The `P2PRequest` is the standardized "Cargo" that travels between agents. It contains the instructions, data, and metadata needed for a successful collaboration.

*   **Prompt**: The `MultimodalContent` (text/images) being sent to the target agent.
*   **Context**: Optional `ContextWindow` data the caller wants to "Inject" into the receiver's memory reservoir.
*   **Customization**: `inputSchema` and `outputSchema` overrides that allow a caller to "Reshape" the agent's flow for a specific task.
*   **Security**: `authBody` containing the necessary credentials for the target agent's gatekeeper.

---

## P2PResponse: The Result Packet
The object returned after an inter-agent call. It contains either the successful output or a detailed rejection report.

*   **`output`**: The resulting `MultimodalContent` if the flow completed successfully.
*   **`rejection`**: If the gatekeeper blocked the request, this contains the `P2PError` type (e.g., `auth`, `json`, `transport`) and a detailed reason for the failure.

---

## P2PRequirements: The Security Gate
The `P2PRequirements` define the operational boundaries of an agent. They are the Gauges that the `P2PRegistry` checks before allowing a request to flow.

*   **Input Limits**: `maxTokens` and `maxBinarySize` prevent callers from overwhelming the agent's reservoir.
*   **Access Control**: `allowExternalConnections` determines if the agent is visible globally or only within its local container.
*   **Auth Logic**: `authMechanism` allows you to provide a custom Kotlin function to verify incoming credentials.

---

## P2PRegistry: The Central Switching Station
A singleton object that acts as the Switching Station for the entire network.

*   **`register(agent)`**: Adds an agent to the central directory, automatically inferring requirements from its descriptor.
*   **`sendP2pRequest()`**: The primary client-side method. It resolves the target agent by name, applies any request templates, and manages the network or in-process handoff.
*   **`executeP2pRequest()`**: The primary server-side method. It performs the "Pressure Test"—validating the incoming request against the agent's `P2PRequirements` before execution.

---

## Key Operational Behaviors

### 1. High-Security Validation
Every inter-agent request is subjected to a comprehensive multi-point check. If a request tries to bypass a schema requirement or exceeds a token limit, the registry triggers a `P2PRejection` before the target model is even called.

### 2. Isolation via Duplication
When an agent allows duplication, TPipe creates a Snapshot Copy of the target pipeline. This ensures that custom instructions or temporary context from one caller never "Pollute" the mainline for other concurrent users.

### 3. Unified Discovery
The registry supports both local "In-process" agents and remote agents. This allows developers to build agents that work identically whether they are in the same JVM or halfway across the world.
