# P2P Requirements and Validation - The Security Gate

Every inter-agent request in TPipe must pass through a **Security Gate**. The `P2PRequirements` class defines the Pressure Settings and Operational Rules for an agent, ensuring that callers cannot overwhelm its reservoir or override its foundational blueprints without permission.

Think of requirements as the Automated Inspector that stands at the entrance of every agent's mainline.

## Configuration: Setting the Rules

You configure these requirements on your agent's `P2PInterface`.

```kotlin
val gate = P2PRequirements(
    allowExternalConnections = true,   // Allow agents from other systems to call
    requireConverseInput = true,       // Force callers to use structured conversation history
    allowAgentDuplication = true,      // Allow forking the mainline for custom requests
    allowCustomJson = false,           // Block callers from changing output schemas
    maxTokens = 32000,                // Total intake limit
    maxBinarySize = 10 * 1024 * 1024, // 10MB limit for images/docs

    // Custom Authentication Valve
    authMechanism = { credentials ->
        verifyVaultToken(credentials)
    }
)
```

---

## Validation Order: The Multi-Point Check

When a `P2PRequest` arrives, the `P2PRegistry` performs a multi-point inspection in this specific order:

1.  **Transport Check**: Is the caller allowed to connect to this agent (internal vs. global)?
2.  **Authentication**: If an `authMechanism` is set, the incoming `authBody` is Pressure Tested (validated).
3.  **Format Validation**: If `requireConverseInput` is true, the registry verifies the request contains a valid `ConverseHistory`.
4.  **Duplication Policy**: If the request contains schema overrides but `allowAgentDuplication` is false, the request is rejected immediately.
5.  **Volume Check**: The system counts the tokens in the prompt and the size of any binary cargo. If it exceeds the `maxTokens` or `maxBinarySize`, the valve shuts.
6.  **Content Filtering**: The registry checks if the agent supports the incoming content types (e.g., rejecting an image if the agent only speaks text).

---

## Error Handling: The Rejection Packet

If any part of the validation fails, the system does **not** execute the agent. Instead, it returns a `P2PRejection` packet to the caller.

```kotlin
val result = registry.executeP2pRequest(cargo)

if (result.rejection != null) {
    // Audit why the gate was closed
    println("Gate Error: ${result.rejection.errorType}") // e.g., auth, content, transport
    println("Reason: ${result.rejection.reason}")
}
```

---

## Key Operational Behaviors

### 1. Unified Token Metering
The Security Gate uses the same `Dictionary` logic as the rest of the TPipe ecosystem. This ensures that token limits are enforced consistently across the entire infrastructure, regardless of which agent is calling.

### 2. Flexible Security Contexts
By using the `authMechanism` lambda, you can integrate TPipe with any industrial security provider (e.g., LDAP, OAuth, or custom vault systems). The gate remains agnostic to your security provider while ensuring the protocol is honored.

### 3. Blueprint Protection
Agents are the "Expert Technicians" of your infrastructure. By setting `allowCustomJson = false`, you protect an agent's expert reasoning from being compromised by a caller who might try to force it into an incompatible format.

## Next Steps

Now that you understand how inter-agent communication is secured, return to the overview to see the big picture.

**→ [P2P Overview](p2p-overview.md)** - Distributed agent communication.
