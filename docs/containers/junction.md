# Junction - Democratic Discussion and Consensus

In a complex municipal infrastructure, critical decisions shouldn't always be made by a single agent. Sometimes you need a **Council of Experts**—a forum where specialists can debate different perspectives, refine a solution through multiple rounds, and reach a consensus through structured voting. **Junction** is the orchestration container designed for these collaborative decision-making patterns.

Think of Junction as the **Strategic Boardroom** where the Security Expert, the Performance Engineer, and the Business Analyst come together to approve a major infrastructure change.

> [!WARNING]
> **Experimental Status**: The `Junction` is currently a **Stub Implementation**. The structural blueprint and intended strategies are defined, but the collaborative discussion engine is not yet functional.

## Intended Design: The Collaborative Yard

When complete, Junction will orchestrate democratic discussions between multiple specialized mainlines:

### 1. The Moderator
A high-priority pipeline that controls the flow of the discussion. It evaluates the current state of consensus and makes the final decision call when the debate is finished.

### 2. The Participants
A collection of specialist agents (Registered in the **P2PRegistry**) with distinct viewpoints (e.g., "Security," "Usability," "Cost").

### 3. Consensus Strategies
Junction is designed to support several Discussion Blueprints:
*   **SIMULTANEOUS**: All experts give their opinion at once, then respond to each other in a second round before voting.
*   **CONVERSATIONAL**: Agents dynamically choose who they want to engage with in each turn, moderated by the central controller.
*   **ROUND_ROBIN**: A structured, turn-taking debate where each expert speaks in order.

---

## Planned Data Structures: The Discussion Ledger

The Junction will maintain a `DiscussionState` to track the "Mainline of Thought."

```kotlin
@Serializable
data class DiscussionState(
    var topic: String = "",
    var participantOpinions: MutableMap<String, String> = mutableMapOf(), // Expert findings
    var votes: MutableMap<String, String> = mutableMapOf(),             // Formal approvals
    var consensusReached: Boolean = false,
    var finalDecision: String = ""
)
```

---

## Planned Features: Consensus Mechanisms

To ensure the discussion leads to a reliable outcome, Junction will include:

*   **Vote Thresholds**: Require a specific Pressure Level (e.g., 75% agreement) to conclude a decision.
*   **Weighted Voting**: Give certain experts more influence on specific topics (e.g., the Security Expert has more weight on safety-related decisions).
*   **Conflict Resolution**: Automated logic for the Moderator to intervene when progress stalls or agents disagree fundamentally.
*   **High-Resolution Debate Tracing**: High-resolution telemetry to visualize how a decision evolved from round to round.

---

## Technical Implementation Roadmap

To reach industrial readiness, Junction requires:
1.  **Async Coordination**: An engine that can handle multiple simultaneous agent calls during the opinion rounds.
2.  **State Preservation**: Mechanisms to share the evolving `DiscussionState` across all participants without creating context pollution.
3.  **P2P Integration**: A full implementation of the `P2PInterface` so the council can be called as a single "Consensus Agent."

## Next Steps

Since Junction is experimental, start your orchestration journey with the production-ready manager-worker system.

**→ [Manifold - Multi-Agent Orchestration](manifold.md)** - Coordinating specialized workers with a central manager.
