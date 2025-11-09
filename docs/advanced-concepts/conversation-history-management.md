# Conversation History Management

## Table of Contents
- [Overview](#overview)
- [Core Types](#core-types)
- [Building Histories](#building-histories)
- [Automatic Pipe Wrapping](#automatic-pipe-wrapping)
- [Integrating with ContextWindow](#integrating-with-contextwindow)
- [Global Context Bank](#global-context-bank)
- [Truncation and Token Budgets](#truncation-and-token-budgets)
- [Serialization](#serialization)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

## Overview

TPipe stores multi-turn conversations in lightweight data structures that mirror the chat paradigms
of modern LLM APIs. These utilities let you preserve user/assistant/system roles, capture
multimodal payloads, and feed the accumulated dialogue back into the next request through
`ContextWindow`.

## Core Types

### `ConverseRole`

Defined in `Context/ConverseData.kt`, the role enum differentiates who produced a message:

```kotlin
enum class ConverseRole {
    developer,
    system,
    user,
    agent,
    assistant
}
```

### `ConverseData`

Represents a single turn of conversation. Each instance stores a role, a `MultimodalContent`
payload, and a UUID used for deduplication.

```kotlin
val turn = ConverseData(
    role = ConverseRole.user,
    content = MultimodalContent().apply { text = "Can you review my code?" }
).also {
    it.setUUID()
}
```

`setUUID()` assigns a random identifier so repeated insertions can be ignored.

### `ConverseHistory`

Ordered list of `ConverseData` entries. Adding an item that already exists (same UUID) is a no-op.

```kotlin
val history = ConverseHistory()
history.add(ConverseRole.system, MultimodalContent("You are a helpful assistant."))
history.add(turn)
```

## Building Histories

Construct histories incrementally as the dialogue progresses:

```kotlin
fun appendUserMessage(history: ConverseHistory, text: String) {
    history.add(
        ConverseRole.user,
        MultimodalContent().apply { this.text = text }
    )
}

fun appendAssistantMessage(history: ConverseHistory, text: String) {
    history.add(
        ConverseRole.assistant,
        MultimodalContent().apply { this.text = text }
    )
}
```

For multimodal messages use `MultimodalContent` helpers (e.g. `addImageFromPath`) before calling
`add`.

## Automatic Pipe Wrapping

Individual pipes can automatically manage conversation history without manual construction. This is particularly useful for pipeline chains where each pipe contributes to an ongoing conversation.

### Basic Usage

```kotlin
val conversationPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("You are a helpful assistant.")
    .wrapContentWithConverse()  // Enable automatic wrapping
```

### Pipeline Integration

When multiple pipes in a pipeline have conversation wrapping enabled, they automatically build on each other's conversation history:

```kotlin
val conversationPipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("You are a research assistant.")
        .wrapContentWithConverse(ConverseRole.assistant))
    .add(BedrockPipe()
        .setSystemPrompt("You are a fact checker.")
        .wrapContentWithConverse(ConverseRole.agent))
    .add(BedrockPipe()
        .setSystemPrompt("You are an editor.")
        .wrapContentWithConverse(ConverseRole.assistant))

// Each pipe automatically builds on the conversation history
val result = conversationPipeline.execute("Research the history of AI")
```

### How It Works

1. **Input Detection**: Each pipe checks if its input is already in `ConverseHistory` format
2. **History Storage**: If detected, the conversation history is stored in the pipe's metadata
3. **Output Wrapping**: The pipe's output is automatically wrapped with the specified role and added to the conversation
4. **Chain Continuity**: Subsequent pipes detect and continue building the conversation

### System Prompt Conversion

For models that work better with conversation format than system prompts:

```kotlin
val conversationPipe = BedrockPipe()
    .setSystemPrompt("You are a helpful assistant.")
    .copySystemToUserPrompt()  // Convert to conversation format
```

This creates a conversation with the system prompt as a developer role entry and the user input as a user role entry.

### Important Considerations

- **Chain Continuity**: All pipes in a conversation chain should have `wrapContentWithConverse()` enabled
- **Silent Breaking**: If any pipe lacks wrapping, the conversation chain breaks silently
- **Role Selection**: Choose appropriate roles (`assistant`, `agent`, `system`, etc.) for each pipe's function
- **JSON Agnostic**: Works regardless of the JSON structure the pipe produces

## Integrating with ContextWindow

`ContextWindow` contains a `ConverseHistory` instance that is automatically included when TPipe
builds prompts. To load historic messages:

```kotlin
val contextWindow = ContextWindow()
contextWindow.converseHistory = history
```

Whenever you send a request through a pipe, the engine merges conversation content with other
context window elements (lorebooks, additional context strings, etc.).

## Global Context Bank

`ContextBank` persists context windows across pipelines or requests. Use it when multiple pipes need
shared conversation state.

```kotlin
ContextBank.updateBankedContext(contextWindow)

val copy = ContextBank.copyBankedContextWindow()
copy?.let {
    it.converseHistory.add(
        ConverseRole.agent,
        MultimodalContent("Forwarding to review agent")
    )
    ContextBank.updateBankedContext(it)
}
```

When concurrency matters, call `updateBankedContextWithMutex` or `swapBankWithMutex` to avoid race
conditions.

## Truncation and Token Budgets

`TokenBudgetSettings` controls how much of the conversation survives when space is limited. Assign
it via `Pipe.setTokenBudget`:

```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = 2_000,
    contextWindowSize = 16_000,
    allowUserPromptTruncation = true,
    truncationMethod = ContextWindowSettings.TruncateTop
)
pipe.setTokenBudget(budget)
```

To manually trim a history using the same logic as the pipeline, call
`ContextWindow.truncateConverseHistoryWithObject`:

```kotlin
val truncationSettings = TruncationSettings()
contextWindow.truncateConverseHistoryWithObject(
    tokenBudget = 1_500,
    multiplyBy = 0,
    truncateMethod = ContextWindowSettings.TruncateTop,
    truncationSettings = truncationSettings
)
```

The helper calculates token usage using the dictionary tokenizer and removes older entries until the
budget is satisfied.

## Serialization

Persist histories alongside other context data by using the shared `serialize` helpers:

```kotlin
import com.TTT.Util.serialize
import com.TTT.Util.deserialize

val json = serialize(history)
val restored = deserialize<ConverseHistory>(json)
```

This is the same mechanism `ContextBank` uses internally when returning copies of the banked
context window.

## Best Practices

- **Assign roles consistently**: stick to `user`/`assistant` alternation for clarity and add
  `system` or `developer` messages only when instructions change.
- **Deduplicate aggressively**: call `setUUID()` on incoming messages before adding them; the
  `ConverseHistory.add` overload already handles UUID generation when omitted.
- **Monitor growth**: large histories impact token budgets quickly. Combine truncation with summary
  messages to keep context compact.
- **Multimodal payloads**: populate `MultimodalContent` fully; downstream pipes will render whatever
  text or binary content you include.

## Next Steps

- Use conversation histories alongside PCP tools by pairing this guide with
  [Basic PCP Usage](basic-pcp-usage.md) and [Intermediate PCP Features](intermediate-pcp-features.md).
- Explore [Context and Tokens](../core-concepts/context-and-tokens.md) for deeper token-management strategies.
- Enable tracing via [Tracing and Debugging](../core-concepts/tracing-and-debugging.md) to audit how conversation history is fed into provider prompts.
