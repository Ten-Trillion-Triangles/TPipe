# P2P Descriptors and Transport - Identity and Routing

The `P2PDescriptor` is the Identity Card for an agent within the TPipe network. It provides an exhaustive technical specification of what an agent can do, how it should be reached, and the security boundaries it enforces. This metadata allows other agents to discover it and the **P2PRegistry** to route requests with high precision.

Think of the descriptor as the Technical Specification Sheet for a specialized valve in your infrastructure.

## The Agent Descriptor: Blueprint of Capabilities

A complete descriptor covers identification, feature flags, and protocol support.

```kotlin
val descriptor = P2PDescriptor(
    agentName = "data-sanitizer",
    agentDescription = "Cleans and reformats raw sensor data for audit.",
    transport = P2PTransport(Transport.Tpipe, "sanitizer-endpoint"),
    
    // Industrial Feature Flags
    allowsAgentDuplication = true, // Enables on-the-fly cloning for custom requests
    allowsCustomAgentJson = true,  // Allows callers to override the output schema
    usesConverse = true,           // Indicates preference for conversation history
    allowsExternalContext = true,  // Can accept memory reservoirs from other agents
    
    // Operational Limits
    contextWindowSize = 32000,
    supportedContentTypes = mutableListOf(SupportedContentTypes.text, SupportedContentTypes.csv),

    // Protocol Support
    contextProtocol = ContextProtocol.pcp,
    pcpDescriptor = myPcpContext // List of tools this agent can use
)
```

---

## Transport Configuration: The Interconnects

The `P2PTransport` defines the type of mainline used for communication and the specific address where the agent is "Plumbed."

### 1. TPipe: In-Process Flow
The fastest transport method. It routes requests within the same JVM, bypassing the network entirely. This is the industrial standard for local agent swarms.

```kotlin
P2PTransport(
    transportMethod = Transport.Tpipe,
    transportAddress = "sanitizer-endpoint"
)
```

### 2. HTTP and Stdio: Remote Mainlines
(Planned) Future support for cross-service and cross-platform communication. These will allow agents to collaborate over standard web protocols or command-line pipes.

---

## Skills and Expertise: The Registry Data

Agents are often selected by other agents (like a **Manifold Manager**) based on their specialized skills. You define these using `P2PSkills`.

```kotlin
val descriptor = P2PDescriptor(
    // ...
    agentSkills = mutableListOf(
        P2PSkills("pii-extraction", "Identifies and redacts sensitive personal data."),
        P2PSkills("schema-validation", "Ensures input JSON matches the production blueprint.")
    )
)
```

---

## Request Templates: Pre-Configured Flow

You can provide a `requestTemplate` in your descriptor. This allows the system to automatically apply certain instructions or context elements to every incoming request, ensuring that the agent always operates under the correct safety protocols.

```kotlin
descriptor.requestTemplate = P2PRequest().apply {
    prompt.addText("SYSTEM: Always report findings in Markdown table format.")
}
```

---

## Key Operational Behaviors

### 1. Feature Enforcement
Feature flags like `allowsCustomAgentJson` are not just hints; they are strictly enforced by the `P2PRegistry`. If a caller tries to override the schema of an agent that has this flag set to `false`, the request is automatically blocked by the **Security Gate**.

### 2. Protocol Compatibility
The `contextProtocol` field tells the network which Tool Belt language the agent speaks (PCP, MCP, or native provider tools). This ensures that if you send an MCP-formatted tool call to a PCP-native agent, the system can attempt an adaptation or signal a protocol mismatch.

### 3. High-Capacity Context
Setting the `contextWindowSize` in the descriptor allows the registry to perform a Pressure Test on incoming requests. If a caller tries to pump 50,000 tokens into an agent rated for 32,000, the request is rejected before it can cause a system crash.

## Next Steps

Now that you can describe your agents, learn how the central registry manages and routes the flow between them.

**→ [P2P Registry and Routing](p2p-registry-and-routing.md)** - Agent discovery and management.
