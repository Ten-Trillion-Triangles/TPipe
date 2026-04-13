# Getting Started with OpenRouter

## Introduction

TPipe-OpenRouter provides access to OpenRouter's unified API that aggregates 300+ LLM models from various providers. It offers a single, consistent interface to models from Anthropic, OpenAI, Google, Meta, DeepSeek, Mistral, and many others.

## Prerequisites

1.  **OpenRouter API Key:** Obtain an API key from [openrouter.ai](https://openrouter.ai). You can get started with free models without adding credits.
2.  **Project Dependency:** Add the `:TPipe-OpenRouter` module to your Gradle configuration.

## Basic Usage

To get started, create an `OpenRouterPipe` instance and configure your model and API key.

```kotlin
import openrouterPipe.OpenRouterPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = OpenRouterPipe()
        .setModel("anthropic/claude-3.5-sonnet")
        .setApiKey("sk-or-...")
        .setTemperature(0.7)
        .setSystemPrompt("You are a helpful assistant.")

    pipe.init()

    val result = pipe.execute("What is the capital of France?")
    println(result.text)
}
```

## Using Free Models

OpenRouter provides free access to certain models via the special `openrouter/free` routing. This allows experimentation without incurring costs.

```kotlin
val pipe = OpenRouterPipe()
    .setModel("openrouter/free")
    .setApiKey("sk-or-...")  // Still required for identification
    .setHttpReferer("https://yourapp.com")
    .setOpenRouterTitle("My Application")
```

Note: Even with free models, the API key is required for request identification and tracking.

## Advanced Configuration

### Provider Preferences

Request routing through specific providers:

```kotlin
val pipe = OpenRouterPipe()
    .setModel("openai/gpt-4o")
    .setProviderPreferences(
        env.ProviderPreferences(
            order = listOf("openai", "anthropic"),
            allow_fallbacks = true
        )
    )
```

### Reasoning Models

For models that support reasoning (like Claude with extended thinking):

```kotlin
val pipe = OpenRouterPipe()
    .setModel("anthropic/claude-3.5-sonnet")
    .setReasoningEffort("high")
    // or with full config:
    .setReasoningConfig(
        env.ReasoningConfig(
            effort = "high",
            maxTokens = 8192
        )
    )
```

### Caching and Service Tiers

Enable request caching for improved performance:

```kotlin
val pipe = OpenRouterPipe()
    .setModel("anthropic/claude-3.5-sonnet")
    .setCacheControl("5m")  // Cache for 5 minutes
    .setServiceTier("priority")  // Priority request handling
```

### Structured Outputs

Request JSON schema-validated responses:

```kotlin
val pipe = OpenRouterPipe()
    .setModel("openai/gpt-4o")
    .setResponseFormat("json_schema", jsonObjectOf(
        "type" to "object",
        "properties" to jsonObjectOf(
            "name" to jsonObjectOf("type" to "string"),
            "age" to jsonObjectOf("type" to "number")
        ),
        "required" to listOf("name", "age")
    ))
    .setStructuredOutputs(true)
```

## Comparison with Other Providers

| Feature | BedrockPipe | OllamaPipe | OpenRouterPipe |
| :--- | :--- | :--- | :--- |
| **Provider** | AWS Bedrock | Local Ollama | OpenRouter (Multi-provider) |
| **Model Access** | AWS-hosted | Local models | 300+ models |
| **Reasoning** | Native support | `<think>` extraction | Extended thinking |
| **Tool Calling** | Converse API | Native tools | OpenAI-compatible |
| **Streaming** | Stream handler | Ktor async | Server-Sent Events |
| **Free Tier** | No | Yes (local) | Yes (limited models) |

## Next Steps

- [OpenRouterPipe Class API](../api/openrouter-pipe.md) - Full API reference for OpenRouterPipe.
- [Pipe Class API](../api/pipe.md) - Core pipe abstraction.