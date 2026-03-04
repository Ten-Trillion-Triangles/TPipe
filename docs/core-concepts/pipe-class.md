# Pipe Class - Core Concepts

The `Pipe` class is the fundamental unit of the TPipe ecosystem. It acts as a single valve that controls the flow of data to and from an AI model. Every interaction in TPipe—whether a simple chat or a complex reasoning task—is anchored by a Pipe.

As an abstract base class, `Pipe` defines the standard interface for all provider-specific implementations, such as `BedrockPipe` and `OllamaPipe`.

```kotlin
abstract class Pipe : P2PInterface, ProviderInterface {
    // Core properties and methods
}
```

## Table of Contents
- [The Builder Pattern](#the-builder-pattern)
- [Core Configuration](#core-configuration)
- [Configuration Examples](#configuration-examples)
- [Advanced Multi-Turn Flow: Conversation Wrapping](#advanced-multi-turn-flow-conversation-wrapping)
- [Summary of Key Properties](#summary-of-key-properties)
- [Next Steps](#next-steps)

---

## The Builder Pattern

TPipe uses a fluent builder pattern to configure pipes. This allows you to chain settings together into a single, readable initialization block. Each configuration method returns the `Pipe` instance, enabling a seamless setup process.

```kotlin
val valve = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    .setRegion("us-west-2")
    .setMaxTokens(1000)
    .setTemperature(0.7)
    .setSystemPrompt("You are a professional hydraulic engineer.")
```

---

## Core Configuration

### Model & Provider
The **Model** represents the engine of intelligence. While specific pipe classes (like `BedrockPipe`) handle the provider assignment internally, you must set the model ID to define which specific LLM will process the flow.

```kotlin
pipe.setModel("model-id")

// Set provider (usually handled by specific pipe classes)
pipe.setProvider(ProviderName.BEDROCK)
```

### Generation Parameters
These parameters control the output's precision and volume:

*   **Max Tokens**: Defines the maximum size of the response (the total capacity of the output stream).
*   **Temperature**: Controls randomness. Set to **0.0** for deterministic, repeatable results; set to **1.0** or higher for creative exploration.
*   **Top-P**: Uses nucleus sampling to control the diversity of the output.
*   **Stop Sequences**: Acts as a cutoff valve, stopping generation immediately when specific strings are found.

```kotlin
pipe.setMaxTokens(1500)
    .setTemperature(0.3)
    .setTopP(0.9)
    .setStopSequences(listOf("### END", "TERMINATE"))
```

### The System Prompt
The system prompt is the operational manual for the Pipe. It defines the persona, goals, and constraints that govern how the model processes the incoming data.

```kotlin
pipe.setSystemPrompt("""
    You are an industrial auditor.
    Analyze the provided sensor logs for signs of valve failure.
    Be precise and report only confirmed anomalies.
""".trimIndent())
```

> [!NOTE]
> **Dynamic Processing**: TPipe automatically enhances your system prompt with additional components, including JSON schemas, Tool Belt definitions (PCP), and injected context. See [System Prompt Processing](#system-prompt-processing) for technical details on this assembly.

### Pipe Identification
```kotlin
// Set a name for this pipe instance (useful for debugging/tracing)
pipe.setPipeName("my-chat-bot")
```

---

## Configuration Examples

### Basic Chat Bot
```kotlin
val chatBot = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(500)
    .setTemperature(0.7)
    .setSystemPrompt("You are a friendly chat bot. Keep responses brief and helpful.")
    .setPipeName("chat-bot")
```

### Creative Writing Assistant
```kotlin
val writer = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(2000)
    .setTemperature(0.9)  // Higher temperature for creativity
    .setSystemPrompt("""
        You are a creative writing assistant.
        Help users with:
        - Story ideas and plot development
        - Character creation
        - Writing style improvements
    """.trimIndent())
    .setPipeName("creative-writer")
```

### Code Assistant
```kotlin
val codeAssistant = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(1500)
    .setTemperature(0.2)  // Lower temperature for accuracy
    .setStopSequences(listOf("```\n\n", "END_CODE"))
    .setSystemPrompt("""
        You are a programming assistant.

        When providing code:
        - Use proper syntax highlighting
        - Include comments explaining complex logic
        - Follow best practices
    """.trimIndent())
    .setPipeName("code-assistant")
```

---

## Advanced Multi-Turn Flow: Conversation Wrapping

In complex agentic systems, you need to maintain a history of what has flowed through your infrastructure. TPipe allows individual pipes to automatically wrap their outputs into a standardized conversation format. This is particularly useful for multi-turn conversations and agent-based systems.

### Why use Wrapping?
By default, a Pipe returns raw text. By calling `wrapContentWithConverse()`, the Pipe becomes aware of its identity (e.g., as an assistant or a specialized agent). It packages its output so that the next Pipe in the mainline can interpret it as part of a structured conversation.

```kotlin
val chatValve = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .wrapContentWithConverse(ConverseRole.assistant)
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

### System Prompt to Conversation Conversion
For models that perform better when instructions are part of the conversation history rather than a separate system message, use `copySystemToUserPrompt()`. This creates a conversation history with the system prompt as a developer role entry and the user input as a user role entry.

> [!IMPORTANT]
> **Mainline Continuity**: To maintain a multi-turn conversation across a **Pipeline**, ensure that every pipe in the chain has wrapping enabled. A single unwrapped valve will break the conversation history for all subsequent pipes.

---

## System Prompt Processing

The final system prompt undergoes several processing stages:

1.  **Raw System Prompt**: Your original instructions.
2.  **PCP Context**: Tool definitions (if enabled).
3.  **JSON Requirements**: Formatting instructions (if needed).
4.  **Middle Prompt**: Instructions injected between input and output JSON schemas.
5.  **Context Instructions**: Auto-injected background knowledge (if enabled).
6.  **Footer Prompt**: Final instructions appended at the end.

---

## Summary of Key Properties

| Property | Type | Technical Purpose |
| :--- | :--- | :--- |
| `model` | `String` | The unique identifier for the LLM (e.g., `anthropic.claude-3-5-sonnet`). |
| `maxTokens` | `Int` | The absolute limit on tokens generated in a single turn. |
| `temperature` | `Double` | The randomness factor, typically between 0.0 and 1.0. |
| `pipeName` | `String` | A unique label used for high-resolution tracing and debugging. |
| `systemPrompt`| `String` | The foundational behavioral instructions for the agent. |
| `stopSequences` | `List<String>` | Sequences that stop generation. |
| `topP` | `Double` | Nucleus sampling parameter. |
| `provider` | `ProviderName` | The AI provider (BEDROCK, OLLAMA, etc.). |

---

## Next Steps

A single valve is the starting point, but industrial-grade workflows require connecting multiple valves together.

**→ [Pipeline Class - Orchestrating Multiple Pipes](pipeline-class.md)** - Learn how to build a complex mainline by chaining pipes together.
