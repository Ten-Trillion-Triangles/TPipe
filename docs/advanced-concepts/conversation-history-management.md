# Conversation History Management - The Stream Record

TPipe provides a high-reliability framework for managing multi-turn conversations. By using structured data types that mirror the chat paradigms of modern LLM APIs, TPipe ensures that user, assistant, and system roles are preserved accurately across your entire infrastructure. These utilities allow you to capture multimodal payloads (text and images) and feed them back into your agents through the **ContextWindow** reservoir.

## Table of Contents
- [Core Types](#core-types)
- [Automatic Pipe Wrapping](#automatic-pipe-wrapping)
- [Mainline Integration](#mainline-integration)
- [Truncation and Volume Control](#truncation-and-volume-control)
- [Key Operational Behaviors](#key-operational-behaviors)

---

## Core Types

### 1. ConverseRole
Defines exactly who produced a specific Drop in the conversation stream.
- **`developer`**: System-level instructions that should have high adherence.
- **`system`**: General behavioral rules for the model.
- **`user`**: The human operator or external trigger.
- **`agent`**: A specialized sub-agent within the infrastructure.
- **`assistant`**: The primary model's previous response.

### 2. ConverseData: The Cargo Unit
Represents a single turn. Every instance contains a role, a `MultimodalContent` payload, and a **UUID**. TPipe uses this UUID to perform aggressive deduplication, ensuring that if multiple pipes attempt to merge their local histories, no turn is recorded twice.

### 3. ConverseHistory: The Ordered Stream
An ordered list of `ConverseData` entries. Adding an item that already exists (same UUID) is a silent no-op, keeping the stream clean and coherent.

---

## Automatic Pipe Wrapping: Seamless History

Manually constructing conversation history for every interaction is tedious and error-prone. TPipe allows individual valves to manage this automatically.

### Basic Implementation
```kotlin
val agent = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    .wrapContentWithConverse(ConverseRole.assistant) // Automatically identifies as the assistant
```

### The Mainline Pattern
When you enable wrapping on multiple pipes in a pipeline, they automatically build on each other's work.
1.  **Valve 1** finishes its work and wraps its output as an `assistant` message.
2.  **Valve 2** detects that the input is already a conversation. It continues the stream, adding its own output as an `agent` message.
3.  **Result**: The final output is a complete, multi-turn history of the entire mainline's Thoughts and "Actions."

---

## Mainline Integration: The Reservoirs

### ContextWindow
Every `ContextWindow` reservoir has a dedicated `converseHistory` slot. When a Pipe executes, TPipe automatically merges this history with your LoreBooks and raw context elements to build the final compound prompt.

### ContextBank
The global `ContextBank` allows you to persist these conversation streams across restarts. You can pull a history from the bank, add new turns, and push it back to the central reservoir to maintain a "Long-Term Memory" for your agents.

---

## Truncation and Volume Control

Conversation histories can grow massive, potentially bursting your model's context window. TPipe provides specialized truncation logic for these streams.

### Automated Budgeting
Use `TokenBudgetSettings` to define exactly how much of the conversation should be preserved.
- **`TruncateTop`**: The industrial standard for chat. It drops the oldest messages first, ensuring the agent always remembers the most recent instructions and data.

```kotlin
val budget = TokenBudgetSettings(
    contextWindowSize = 32000,
    truncationMethod = ContextWindowSettings.TruncateTop
)
pipe.setTokenBudget(budget)
```

---

## Key Operational Behaviors

### 1. Multi-Step Continuity
Because TPipe uses UUID-based tracking, you can safely run loops or complex branches without fear of duplicating history. If an agent repeats a task (e.g., in a refinement loop), TPipe ensures the history remains a linear, logical record.

### 2. High Portability
All conversation types are `@Serializable`. This means you can easily "Pipe" a conversation over a network to a remote `MemoryServer` or save it to disk as a project audit trail.

### 3. Role-Specific Logic
Modern models (like Claude 3.5 or GPT-4o) treat different roles with different levels of attention. By using the structured `ConverseRole` enum, you are ensuring that your instructions are placed in the specific Logical Compartments where the model expects them.

## Next Steps

Now that you can manage conversation streams, learn about how to equip your agents with tools.

**→ [Basic PCP Usage](basic-pcp-usage.md)** - Getting started with shell and HTTP patterns.
