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
  - [Prompt Caching](#prompt-caching)
  - [Reasoning](#reasoning)
  - [Resource Management](#resource-management)
- [GenericOpenAIEnv](#genericopenaienv)
- [ToolDefinition / FunctionSchema](#tooldefinition--functionschema)
- [ResponseFormat](#responseformat)
- [CacheControl](#cachecontrol)
- [Error Mapping](#error-mapping)
- [Multimodal Content](#multimodal-content)
- [Next Steps](#next-steps)

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

`ApiMode` is a sealed class that selects which wire format, endpoint, request serializer, response parser, SSE parser, and auth header set the pipe uses. The default is `ApiMode.OpenAI`.

```kotlin
sealed class ApiMode
{
    data object OpenAI : ApiMode()
    data object Anthropic : ApiMode()
    data object OpenAIResponses : ApiMode()
    companion object { val DEFAULT: ApiMode = OpenAI }
}
```

| Mode | Endpoint | Auth Header |
|------|----------|-------------|
| `ApiMode.OpenAI` (default) | `${baseUrl}/chat/completions` | `Authorization: Bearer <key>` |
| `ApiMode.Anthropic` | `${baseUrl}/anthropic/v1/messages` | `x-api-key: <key>`, `anthropic-version: 2023-06-01` |
| `ApiMode.OpenAIResponses` | `${baseUrl}/responses` | `Authorization: Bearer <key>` |

> **⚠ `apiMode` is locked after the first API call.** Once `sendRequest(...)` runs (i.e. after the first `execute()` / `generateText()` / `generateContent()`), the internal `apiModeLocked` flag flips to `true`. Calling `setApiMode(...)` afterwards throws `IllegalStateException("apiMode cannot be changed after the first API request")`. Set the mode up front, before the first call, and create a new pipe instance if you need a different mode.

`OpenAIResponses` is a sealed data object that targets OpenAI's newer Responses wire spec — top-level `instructions`, `input` items, and a streaming protocol driven by `response.created` / `response.output_text.delta` / `response.completed` events. See `OpenAIResponsesRequestSerializer` / `OpenAIResponsesSseParser` for the wire details.

## Public Functions

### Authentication & Endpoint

#### `setApiKey(key: String): GenericOpenAIPipe`
Sets the API key. Required unless `GENERIC_OPENAI_API_KEY` environment variable is set. The pipe-level value is checked first; if blank, `init()` falls back to `GenericOpenAIEnv.resolveApiKey()`.

#### `setBaseUrl(url: String): GenericOpenAIPipe`
Sets the base URL. Defaults to `https://api.openai.com/v1`. Must use HTTPS — passing an `http://` URL throws `IllegalArgumentException("baseUrl must use HTTPS for security")`. Trailing slashes are stripped.

**Example:**
```kotlin
.setBaseUrl("https://api.openai.com/v1")       // OpenAI (default)
.setBaseUrl("https://api.anthropic.com")        // Anthropic
.setBaseUrl("https://openai.myenterprise.com") // Third-party proxy
```

---

### API Mode

#### `setApiMode(mode: ApiMode): GenericOpenAIPipe`
Sets the wire format and endpoint. Default is `ApiMode.OpenAI`. Throws `IllegalStateException` if called after the first API request.

**Parameters:**
- `ApiMode.OpenAI` — OpenAI Chat Completions format at `${baseUrl}/chat/completions`
- `ApiMode.Anthropic` — Anthropic messages format at `${baseUrl}/anthropic/v1/messages`
- `ApiMode.OpenAIResponses` — OpenAI Responses format at `${baseUrl}/responses`

**Example:**
```kotlin
.setApiMode(ApiMode.Anthropic)
```

---

### Inference Settings

#### `setFrequencyPenalty(penalty: Double): GenericOpenAIPipe`
Sets frequency penalty (-2.0 to 2.0). Reduces repetition of tokens proportional to their prior frequency.

#### `setModalities(modalities: List<String>): GenericOpenAIPipe`
Sets output modalities (e.g., `["text", "image", "audio"]`) for multimodal models. Serialized to the `modalities` field of the request body when set.

---

### Function Calling

#### `setTools(tools: List<ToolDefinition>): GenericOpenAIPipe`
Registers function definitions for tool-calling models. See [ToolDefinition / FunctionSchema](#tooldefinition--functionschema) for the data class shape.

**Example:**
```kotlin
import genericOpenAIPipe.env.ToolDefinition
import genericOpenAIPipe.env.FunctionSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

val tools = listOf(
    ToolDefinition(
        type = "function",
        function = FunctionSchema(
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
)

pipe.setTools(tools)
```

#### `setToolChoice(choice: String): GenericOpenAIPipe`
Sets the tool choice mode: `"auto"`, `"none"`, or `"required"`.

#### `setParallelToolCalls(enabled: Boolean): GenericOpenAIPipe`
Enables or disables parallel function calling (default: `true`).

---

### Structured Output

#### `setResponseFormat(type: String, jsonSchema: kotlinx.serialization.json.JsonObject? = null): GenericOpenAIPipe`
Sets the response format for structured output. Internally stores a [ResponseFormat](#responseformat) instance.

- `"text"` — plain text (default)
- `"json_object"` — JSON object mode
- `"json_schema"` — requires `jsonSchema` parameter

#### `setStructuredOutputs(enabled: Boolean): GenericOpenAIPipe`
Enables structured outputs via `json_schema`. Must be used with `setResponseFormat("json_schema", schema)`.

---

### Streaming

#### `setStreamingEnabled(enabled: Boolean): GenericOpenAIPipe`
Enables Server-Sent Events (SSE) streaming. When enabled, the response is delivered as a series of chunks. The active `ApiMode` selects the SSE parser:
- `ApiMode.OpenAI` → `SseParser`
- `ApiMode.Anthropic` → `AnthropicSseParser`
- `ApiMode.OpenAIResponses` → `OpenAIResponsesSseParser`

#### `setStreamingCallback(callback: suspend (String) -> Unit): GenericOpenAIPipe`
Registers a callback for streaming response chunks. Automatically enables streaming by flipping `streamingEnabled = true` and adding the callback via `obtainStreamingCallbackManager()`.

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
Configures reasoning for capable models (e.g., o3, o4-mini, DeepSeek-R1). Serialized into the request body for the active mode.

```kotlin
data class ReasoningConfig(
    val effort: String? = null,       // "xhigh", "high", "medium", "low", "minimal", "none"
    @SerialName("max_tokens")
    val maxTokens: Int? = null,      // max tokens for reasoning output
    val exclude: Boolean? = null,    // exclude reasoning from final output
    val enabled: Boolean? = null      // enable/disable reasoning
)
```

**Example:**
```kotlin
import genericOpenAIPipe.env.ReasoningConfig

pipe.setReasoningConfig(ReasoningConfig(
    effort = "high",
    maxTokens = 8192,
    exclude = false,
    enabled = true
))
```

---

### Prompt Caching

#### `setCacheControl(type: String = "ephemeral", ttl: String? = null): GenericOpenAIPipe`
Enables explicit prompt caching on the Anthropic API path. The cache breakpoint is placed on the **last system block**, caching the full system prompt prefix (tools + system) per the Anthropic/MiniMax spec.

**Supported models:** MiniMax-M2.7, M2.5, M2.1, M2. Not supported on M3 — use passive auto-cache on `ApiMode.OpenAI` instead.

**TTL behavior by provider:**

| Provider | TTL support |
|----------|-------------|
| Direct Anthropic API | `"5m"` (default, 5 min) or `"1h"` (1 hour) |
| MiniMax `/anthropic` endpoint | TTL **ignored** — cache is always 5 minutes, auto-refreshes on hit at no additional cost |

**Example — MiniMax (no TTL):**
```kotlin
val pipe = GenericOpenAIPipe()
    .setApiKey(System.getenv("MINIMAX_API_KEY"))
    .setBaseUrl("https://api.minimax.io")
    .setModel("MiniMax-M2.7")
    .setApiMode(ApiMode.Anthropic)
    .setSystemPrompt(systemPrompt)
    .setCacheControl()  // ttl omitted — MiniMax uses 5 min default
    .init()
```

**Example — Direct Anthropic (with 1h TTL):**
```kotlin
val pipe = GenericOpenAIPipe()
    .setApiKey(System.getenv("ANTHROPIC_API_KEY"))
    .setBaseUrl("https://api.anthropic.com")
    .setModel("claude-3-5-sonnet-20241022")
    .setApiMode(ApiMode.Anthropic)
    .setSystemPrompt(systemPrompt)
    .setCacheControl(ttl = "1h")  // 1-hour cache on Anthropic
    .init()
```

**Note:** Passive auto-cache (on `ApiMode.OpenAI`) requires no code — MiniMax automatically caches at 512+ input tokens at no cost. Use explicit `setCacheControl` only when you need the longer TTL available on direct Anthropic, or when targeting M2-family models that support it.

---

### Resource Management

#### `init(): Pipe`
Initializes the pipe. Validates configuration and sets up the HTTP client.

**Behavior:**
- Resolves API key from parameter or `GenericOpenAIEnv.resolveApiKey()` (which checks the env var `GENERIC_OPENAI_API_KEY`)
- Sets `provider = ProviderName.Gpt`
- Creates a CIO-based HTTP client with 120s request timeout, 30s connect timeout, 120s socket timeout
- Emits a `TraceEventType.PIPE_START` trace event tagged `provider = "GenericOpenAI"`

**Throws:** `IllegalStateException("GenericOpenAI API key is required. Call setApiKey(), genericOpenAIEnv.setApiKey(), or set GENERIC_OPENAI_API_KEY environment variable before init().")` if no API key is configured.

#### `abort()`
Closes the HTTP client and cleans up resources. Emits a `TraceEventType.PIPE_FAILURE` trace event with `action = "abort"`. Call when done with the pipe.

---

## GenericOpenAIEnv

The `GenericOpenAIEnv` object is a process-wide singleton that owns the API key fallback chain. It lives in `genericOpenAIPipe.env`:

```kotlin
package genericOpenAIPipe.env

object GenericOpenAIEnv
{
    fun setApiKey(key: String)
    fun getApiKey(): String
    fun getApiKeyFromEnv(): String
    fun resolveApiKey(): String
    fun clearApiKey()
    fun hasApiKey(): Boolean
}
```

#### `setApiKey(key: String)`
Sets the Generic OpenAI API key programmatically. Persists for the lifetime of the process unless `clearApiKey()` is called.

```kotlin
import genericOpenAIPipe.env.GenericOpenAIEnv

GenericOpenAIEnv.setApiKey("sk-...")
```

#### `getApiKey(): String`
Returns the currently configured API key, or empty string if not set. Reads the in-process field only — does **not** consult the environment variable.

#### `getApiKeyFromEnv(): String`
Returns the API key from the `GENERIC_OPENAI_API_KEY` environment variable, or empty string if not set.

#### `resolveApiKey(): String`
Returns the effective API key: programmatic value if non-blank, otherwise the environment variable. This is the value `init()` uses as its fallback.

#### `clearApiKey()`
Clears the programmatically set API key. After this call, only the environment variable is used.

#### `hasApiKey(): Boolean`
Returns `true` if any API key is available (programmatic or env var). Useful for pre-flight checks before constructing a pipe.

**Usage pattern:**

```kotlin
import genericOpenAIPipe.env.GenericOpenAIEnv
import genericOpenAIPipe.GenericOpenAIPipe

if (!GenericOpenAIEnv.hasApiKey()) {
    GenericOpenAIEnv.setApiKey(System.getenv("OPENAI_API_KEY"))
}

val pipe = GenericOpenAIPipe()
    .setModel("gpt-4o")
    .init()
```

The `init()` chain is: pipe-level `apiKey` field → `GenericOpenAIEnv.resolveApiKey()` → `IllegalStateException`.

---

## ToolDefinition / FunctionSchema

`setTools(...)` accepts a list of `ToolDefinition` data classes. These are the function-calling payloads serialized to the `tools` array of the request body.

```kotlin
package genericOpenAIPipe.env

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSchema
)

@Serializable
data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)
```

| Field | Type | Required | Description |
|:---|:---|:---|:---|
| `ToolDefinition.type` | `String` | optional, default `"function"` | Tool type discriminator. Only `"function"` is supported today. |
| `ToolDefinition.function` | `FunctionSchema` | yes | The callable function schema. |
| `FunctionSchema.name` | `String` | yes | Function name; must match across calls. |
| `FunctionSchema.description` | `String` | yes | Natural-language description of the function. The model uses this to decide when to call it. |
| `FunctionSchema.parameters` | `JsonObject` | yes | Standard JSON Schema describing the function's parameters. |

Pair `setTools(...)` with `setToolChoice("auto" | "none" | "required")` to control when the model may call, and `setParallelToolCalls(true | false)` to allow multiple tool calls in a single response.

---

## ResponseFormat

`setResponseFormat(type, jsonSchema)` constructs a `ResponseFormat` instance and stores it on the pipe. It is serialized into the `response_format` field of the request body.

```kotlin
package genericOpenAIPipe.env

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ResponseFormat(
    val type: String,           // "text", "json_object", or "json_schema"
    val jsonSchema: JsonObject? = null
)
```

| Field | Type | Required | Description |
|:---|:---|:---|:---|
| `type` | `String` | yes | Format type. Use `"text"` for plain prose, `"json_object"` for free-form JSON, `"json_schema"` for schema-validated JSON. |
| `jsonSchema` | `JsonObject?` | required when `type == "json_schema"` | A standard JSON Schema describing the desired output shape. |

For schema-validated output, also call `setStructuredOutputs(true)`:

```kotlin
val schema = Json.parseToJsonElement("""{
    "type": "object",
    "properties": { "answer": { "type": "string" } },
    "required": ["answer"]
}""").jsonObject

pipe.setResponseFormat("json_schema", schema)
pipe.setStructuredOutputs(true)
```

---

## CacheControl

Anthropic-style prompt caching hint carried on the wire-level request. Defined in `genericOpenAIPipe.env`:

```kotlin
package genericOpenAIPipe.env

import kotlinx.serialization.Serializable

@Serializable
data class CacheControl(
    val type: String,           // e.g., "ephemeral"
    val ttl: String? = null     // e.g., "5m", "1h", "24h"
)
```

| Field | Type | Required | Description |
|:---|:---|:---|:---|
| `type` | `String` | yes | Cache type discriminator. Common value: `"ephemeral"`. |
| `ttl` | `String?` | optional | Cache time-to-live. Provider-accepted values include `"5m"`, `"1h"`, `"24h"`. |

`CacheControl` is a field on `GenericOpenAIChatRequest` (serialized as `cache_control`) and is plumbed through `generateText(...)` and `generateContent(...)`. The pipe does not currently expose a public `setCacheControl(...)` builder — `CacheControl` is intended for direct request construction or custom request pipelines. For higher-level caching ergonomics, prefer `setApiMode(ApiMode.Anthropic)` and let the Anthropic provider handle prompt caching at the protocol level.

---

## Error Mapping

`GenericOpenAIPipe` maps transport-level and provider-level failures to the [`P2PError`](../../src/main/kotlin/P2P/P2PResponse.kt) enum (values: `auth`, `prompt`, `json`, `content`, `transport`, `context`, `configuration`, `none`):

| HTTP / condition | Provider error type | `P2PError` | When |
|:---|:---|:---|:---|
| 401 | — | `auth` | Invalid or missing API key |
| 403 | — | `auth` | Forbidden (region / model access) |
| 400 | `invalid_request_error` | `prompt` | Malformed request body |
| 400 | `invalid_api_key` | `prompt` | Provider rejected the key shape |
| 429 | `rate_limit_error` | `transport` | Rate limit |
| 5xx | `api_error`, `server_error` | `transport` | Provider server error |
| — | — | `transport` | Network / socket / request timeout |
| — | — | `json` | Response body could not be parsed into `GenericOpenAIChatResponse` |

The mapping is enforced in two places:
- **SSE path** (`executeStreamingOpenAI`) — deserializes the first `data:` payload as `GenericOpenAIErrorResponse` and classifies on `error.type` (`authentication_error → auth`, `rate_limit_error → transport`, `invalid_request_error` / `invalid_api_key → prompt`, `api_error` / `server_error → transport`, default → `transport`).
- **Non-streaming path** — converts `HttpRequestTimeoutException`, `SocketTimeoutException`, and `ConnectException` into `P2PException(P2PError.transport, ...)`, and wraps JSON parse failures as `P2PException(P2PError.json, ...)`.

Catch `P2PException` and inspect `errorType` for typed handling.

---

## Multimodal Content

`GenericOpenAIPipe` supports multimodal inputs (images, documents) via `MultimodalContent.binaryContent`. Binary content is automatically converted to the appropriate format for the selected `ApiMode` inside `generateContent(...)`:

| `BinaryContent` type | OpenAI mode | Anthropic mode | OpenAI Responses mode |
|:---|:---|:---|:---|
| `Bytes` | base64 data URI image block | base64 image block | base64 input item |
| `Base64String` | base64 data URI image block | base64 image block | base64 input item |
| `CloudReference` | URL image block | URL image block | URL input item |
| `TextDocument` | text block | text block | text input item |

`Bytes` and `Base64String` are equivalent on the wire — both end up as a `data:<mime>;base64,...` URL inside an `ImageUrlBlock`. `CloudReference` passes the URL through as-is. `TextDocument` injects the text into the content array as a `TextBlock`.

**Example:**
```kotlin
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.BinaryContent
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val imageBytes = File("paris.png").readBytes()
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o")
        .setApiMode(ApiMode.OpenAI)
        .init()

    val content = MultimodalContent(
        text = "What is in this image?",
        binaryContent = mutableListOf(
            BinaryContent.Bytes(data = imageBytes, mimeType = "image/png")
        )
    )

    println(pipe.execute(content).text)
}
```

## Next Steps

- [Getting Started with GenericOpenAI](../generic-openai/getting-started.md) — Provider recipes, comparison with `OllamaPipe` / `OpenRouterPipe` / `BedrockPipe`, and a troubleshooting guide.
- [Pipe Context Protocol](../advanced-concepts/pipe-context-protocol.md) — Attach PCP tools to a `GenericOpenAIPipe` for sandboxed multi-language tool execution.
- [Pipe Class API](./pipe.md) — Core pipe abstraction and base-class builders (`setModel`, `setTemperature`, `setMaxTokens`, `setSystemPrompt`, `setStopSequences`, `setSeed`, `setUser`, `setLogitBias`, `setN`, `setRepetitionPenalty`, `setPresencePenalty`, `setContextWindowSize`, `setTokenBudget`).
- [DistributionGrid](../containers/distributiongrid.md) — Route tasks across workers with a `GenericOpenAIPipe` router.
