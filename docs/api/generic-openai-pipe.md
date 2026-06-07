# GenericOpenAI Pipe Class API

## Table of Contents
- [Overview](#overview)
- [ApiMode](#apimode)
- [Public Functions](#public-functions)
  - [Authentication & Endpoint](#authentication--endpoint)
  - [API Mode](#api-mode)
  - [Inference Settings](#inference-settings)
  - [Function Calling](#function-calling)
  - [Structured Output](#structured-output)
  - [Streaming](#streaming)
  - [Reasoning](#reasoning)
  - [Resource Management](#resource-management)

## Overview

The `GenericOpenAIPipe` class provides a TPipe abstraction for OpenAI-compatible APIs. It supports three API modes — OpenAI Chat Completions, Anthropic Messages, and OpenAI Responses — and works with any provider that implements one of these specifications.

```kotlin
class GenericOpenAIPipe : Pipe()
```

**Requirements:** Call `setApiKey()` before `init()`, or set the `GENERIC_OPENAI_API_KEY` environment variable.

**Example — OpenAI mode (default):**
```kotlin
val pipe = GenericOpenAIPipe()
    .setApiKey(System.getenv("OPENAI_API_KEY"))
    .setModel("gpt-4o")
    .setSystemPrompt("You are a helpful assistant.")
    .init()

val result = pipe.execute("What is TPipe?")
```

**Example — Anthropic mode:**
```kotlin
val pipe = GenericOpenAIPipe()
    .setApiKey(System.getenv("ANTHROPIC_API_KEY"))
    .setBaseUrl("https://api.anthropic.com")
    .setModel("claude-3-5-sonnet-20241022")
    .setApiMode(ApiMode.Anthropic)
    .init()
```

**Example — OpenAI Responses mode:**
```kotlin
val pipe = GenericOpenAIPipe()
    .setApiKey(System.getenv("OPENAI_API_KEY"))
    .setModel("gpt-4o-2025-04-16")
    .setApiMode(ApiMode.OpenAIResponses)
    .init()
```

## ApiMode

`ApiMode` is a sealed class that selects which wire format and endpoint the pipe uses:

| Mode | Endpoint | Auth Header |
|------|----------|-------------|
| `ApiMode.OpenAI` (default) | `/v1/chat/completions` | `Authorization: Bearer <key>` |
| `ApiMode.Anthropic` | `/anthropic/v1/messages` | `x-api-key: <key>`, `anthropic-version: 2023-06-01` |
| `ApiMode.OpenAIResponses` | `/v1/responses` | `Authorization: Bearer <key>` |

**Note:** `apiMode` is locked after the first API call. Attempting to change it afterwards throws `IllegalStateException`.

## Public Functions

### Authentication & Endpoint

#### `setApiKey(key: String): GenericOpenAIPipe`
Sets the API key. Required unless `GENERIC_OPENAI_API_KEY` environment variable is set.

#### `setBaseUrl(url: String): GenericOpenAIPipe`
Sets the base URL. Defaults to `https://api.openai.com/v1`. Must use HTTPS.

**Example:**
```kotlin
.setBaseUrl("https://api.openai.com/v1")       // OpenAI (default)
.setBaseUrl("https://api.anthropic.com")        // Anthropic
.setBaseUrl("https://openai.myenterprise.com") // Third-party proxy
```

---

### API Mode

#### `setApiMode(mode: ApiMode): GenericOpenAIPipe`
Sets the wire format and endpoint. Default is `ApiMode.OpenAI`.

**Parameters:**
- `ApiMode.OpenAI` — OpenAI Chat Completions format at `/v1/chat/completions`
- `ApiMode.Anthropic` — Anthropic messages format at `/anthropic/v1/messages`
- `ApiMode.OpenAIResponses` — OpenAI Responses format at `/v1/responses`

**Example:**
```kotlin
.setApiMode(ApiMode.Anthropic)
```

---

### Inference Settings

#### `setFrequencyPenalty(penalty: Double): GenericOpenAIPipe`
Sets frequency penalty (-2.0 to 2.0). Reduces repetition of tokens proportional to their prior frequency.

#### `setModalities(modalities: List<String>): GenericOpenAIPipe`
Sets output modalities (e.g., `["text", "image", "audio"]`) for multimodal models.

---

### Function Calling

#### `setTools(tools: List<ToolDefinition>): GenericOpenAIPipe`
Registers function definitions for tool-calling models.

**Example:**
```kotlin
import genericOpenAIPipe.env.ToolDefinition

val tools = listOf(
    ToolDefinition(
        name = "get_weather",
        description = "Get current weather",
        parameters = Json.parseToJsonElement("""{
            "type": "object",
            "properties": {
                "location": {"type": "string"}
            },
            "required": ["location"]
        }""").jsonObject
    )
)

pipe.setTools(tools)
```

#### `setToolChoice(choice: String): GenericOpenAIPipe`
Sets the tool choice mode: `"auto"`, `"none"`, or `"required"`.

#### `setParallelToolCalls(enabled: Boolean): GenericOpenAIPipe`
Enables or disables parallel function calling (default: `true`).

---

### Structured Output

#### `setResponseFormat(type: String, jsonSchema: JsonObject? = null): GenericOpenAIPipe`
Sets the response format for structured output.

- `"text"` — plain text (default)
- `"json_object"` — JSON object mode
- `"json_schema"` — requires `jsonSchema` parameter

#### `setStructuredOutputs(enabled: Boolean): GenericOpenAIPipe`
Enables structured outputs via `json_schema`. Must be used with `setResponseFormat("json_schema", schema)`.

---

### Streaming

#### `setStreamingEnabled(enabled: Boolean): GenericOpenAIPipe`
Enables Server-Sent Events (SSE) streaming. When enabled, the response is delivered as a series of chunks.

#### `setStreamingCallback(callback: suspend (String) -> Unit): GenericOpenAIPipe`
Registers a callback for streaming response chunks. Automatically enables streaming.

**Example:**
```kotlin
pipe.setStreamingCallback { chunk ->
    print(chunk)
    flush()
}
```

---

### Reasoning

#### `setReasoningConfig(config: ReasoningConfig): GenericOpenAIPipe`
Configures reasoning for capable models (e.g., o3, o4-mini, DeepSeek-R1).

```kotlin
data class ReasoningConfig(
    val effort: String? = null,       // e.g., "low", "medium", "high"
    val maxTokens: Int? = null,      // max tokens for reasoning output
    val exclude: Boolean? = null,    // exclude reasoning from final output
    val enabled: Boolean? = null      // enable/disable reasoning
)
```

**Example:**
```kotlin
pipe.setReasoningConfig(ReasoningConfig(
    effort = "medium",
    maxTokens = 2048,
    exclude = false
))
```

---

### Resource Management

#### `init(): Pipe`
Initializes the pipe. Validates configuration and sets up the HTTP client.

**Behavior:**
- Resolves API key from parameter or `GENERIC_OPENAI_API_KEY` env var
- Sets `provider = ProviderName.Gpt`
- Creates CIO-based HTTP client with 120s request timeout

**Throws:** `IllegalStateException` if no API key is configured.

#### `abort()`
Closes the HTTP client and cleans up resources. Call when done with the pipe.

---

## Multimodal Content

`GenericOpenAIPipe` supports multimodal inputs (images, documents) via `MultimodalContent.binaryContent`. Binary content is automatically converted to the appropriate format for the selected `ApiMode`:

| BinaryContent type | OpenAI mode | Anthropic mode | OpenAI Responses mode |
|---|---|---|---|
| `Bytes` | base64 data URI | base64 image block | base64 input item |
| `Base64String` | base64 data URI | base64 image block | base64 input item |
| `CloudReference` | URL image block | URL image block | URL input item |
| `TextDocument` | text block | text block | text input item |

## Next Steps

- [Pipe Context Protocol](../advanced-concepts/pipe-context-protocol.md) — Attach PCP tools to a GenericOpenAI pipe
- [DistributionGrid](../containers/distributiongrid.md) — Route tasks across workers with a GenericOpenAI router