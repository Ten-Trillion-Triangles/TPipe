# Pipe Class - The Base Valve

The `Pipe` class is the fundamental unit of the TPipe ecosystem. It acts as a single valve that controls the flow of data to and from an AI model. Every interaction in TPipe—whether a simple chat or a complex reasoning task—is anchored by a Pipe.

As an abstract base class, `Pipe` defines the standard interface for all provider-specific implementations, such as `BedrockPipe` and `OllamaPipe`.

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

## Core Configuration

### Model & Provider
The **Model** represents the engine of intelligence. While specific pipe classes (like `BedrockPipe`) handle the provider assignment internally, you must set the model ID to define which specific LLM will process the flow.

```kotlin
pipe.setModel("model-id")
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

## Advanced Multi-Turn Flow: Conversation Wrapping

In complex agentic systems, you need to maintain a history of what has flowed through your infrastructure. TPipe allows individual pipes to automatically wrap their outputs into a standardized conversation format.

### Why use Wrapping?
By default, a Pipe returns raw text. By calling `wrapContentWithConverse()`, the Pipe becomes aware of its identity (e.g., as an assistant or a specialized agent). It packages its output so that the next Pipe in the mainline can interpret it as part of a structured conversation.

```kotlin
val chatValve = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .wrapContentWithConverse(ConverseRole.assistant)
```

### Automatic Conversion
For models that require instructions to be part of the conversation history rather than a separate system message, use `copySystemToUserPrompt()`. This moves your system prompt into the first turn of the history, ensuring better adherence on certain legacy or smaller models.

> [!IMPORTANT]
> **Mainline Continuity**: To maintain a multi-turn conversation across a **Pipeline**, ensure that every pipe in the chain has wrapping enabled. A single unwrapped valve will break the conversation history for all subsequent pipes.

## Summary of Key Properties

| Property | Type | Technical Purpose |
| :--- | :--- | :--- |
| `model` | `String` | The unique identifier for the LLM (e.g., `anthropic.claude-3-5-sonnet`). |
| `maxTokens` | `Int` | The absolute limit on tokens generated in a single turn. |
| `temperature` | `Double` | The randomness factor, typically between 0.0 and 1.0. |
| `pipeName` | `String` | A unique label used for high-resolution tracing and debugging. |
| `systemPrompt`| `String` | The foundational behavioral instructions for the agent. |

## Next Steps

A single valve is the starting point, but industrial-grade workflows require connecting multiple valves together.

**→ [Pipeline Class - Orchestrating Multiple Pipes](pipeline-class.md)** - Learn how to build a complex mainline by chaining pipes together.
