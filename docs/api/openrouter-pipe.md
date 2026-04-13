# OpenRouter Pipe Class API

## Table of Contents
- [Overview](#overview)
- [Authentication](#authentication)
- [Model Configuration](#model-configuration)
- [Inference Settings](#inference-settings)
- [Sampling Parameters](#sampling-parameters)
- [Reasoning](#reasoning)
- [Function Calling](#function-calling)
- [Provider Options](#provider-options)
- [Response Format](#response-format)
- [Streaming](#streaming)
- [Tracing Behavior](#tracing-behavior)
- [Error Mapping](#error-mapping)

## Overview

The `OpenRouterPipe` class provides a TPipe abstraction for interacting with OpenRouter's unified API. It supports 300+ models from various providers with OpenAI-compatible request/response formats and OpenRouter-specific extensions.

```kotlin
class OpenRouterPipe : Pipe()
```

**Key Features:**
- Access to 300+ models from multiple providers
- OpenAI-compatible `/v1/chat/completions` endpoint
- Built-in support for reasoning models
- Provider preference routing
- Request caching and service tiers
- Streaming via Server-Sent Events

## Authentication

### `setApiKey(key: String): OpenRouterPipe`

Sets the OpenRouter API key for authentication.

- `@param key` The API key from openrouter.ai
- `@return` This pipe instance for fluent chaining

```kotlin
val pipe = OpenRouterPipe()
    .setApiKey("sk-or-v1-...")
```

**Note:** The API key can also be set via the `OPENROUTER_API_KEY` environment variable or `openrouterEnv.setApiKey()`. If not set before `init()`, the pipe will attempt to resolve from these sources.

---

### `setHttpReferer(referer: String): OpenRouterPipe`

Sets the HTTP Referer header for site traffic tracking.

- `@param referer` The referer URL to send with requests
- `@return` This pipe instance for fluent chaining

---

### `setOpenRouterTitle(title: String): OpenRouterPipe`

Sets the OpenRouter Title header to identify your application.

- `@param title` The application title
- `@return` This pipe instance for fluent chaining

---

## Model Configuration

### `setModel(model: String): OpenRouterPipe`

Sets the model identifier. Inherited from [Pipe] base class.

Model examples:
- `anthropic/claude-3.5-sonnet`
- `openai/gpt-4o`
- `google/gemini-pro`
- `openrouter/free` (free model routing)

---

### `setBaseUrl(url: String): OpenRouterPipe`

Sets a custom base URL for the API endpoint.

- `@param url` Base URL (defaults to `https://openrouter.ai/api/v1`)
- `@return` This pipe instance for fluent chaining

---

## Inference Settings

### `setTemperature(temperature: Double): OpenRouterPipe`

Sets the sampling temperature. Inherited from [Pipe] base class.

- `@param temperature` Value between 0.0 and 2.0 (default: 0.7)

---

### `setMaxTokens(maxTokens: Int): OpenRouterPipe`

Sets the maximum number of tokens to generate. Inherited from [Pipe] base class.

---

### `setTopP(topP: Double): OpenRouterPipe`

Sets the nucleus sampling threshold. Inherited from [Pipe] base class.

---

## Sampling Parameters

### `setMinP(p: Double): OpenRouterPipe`

Sets the MinP sampling parameter for minimum probability threshold.

- `@param p` MinP value between 0.0 and 1.0
- `@return` This pipe instance for fluent chaining

---

### `setTopA(a: Double): OpenRouterPipe`

Sets the TopA sampling parameter.

- `@param a` TopA value between 0.0 and 1.0
- `@return` This pipe instance for fluent chaining

---

### `setOpenRouterTopK(k: Int): OpenRouterPipe`

Sets the TopK sampling parameter. Note: Not available for OpenAI models.

- `@param k` TopK value between 1 and 255
- `@return` This pipe instance for fluent chaining

---

### `setOpenRouterRepetitionPenalty(penalty: Double): OpenRouterPipe`

Sets the repetition penalty. This is distinct from `setFrequencyPenalty()`.

- `@param penalty` Repetition penalty between 0.0 and 2.0
- `@return` This pipe instance for fluent chaining

---

### `setFrequencyPenalty(penalty: Double): OpenRouterPipe`

Sets the frequency penalty for reducing repetition.

- `@param penalty` Frequency penalty between -2.0 and 2.0
- `@return` This pipe instance for fluent chaining

---

## Reasoning

### `setReasoningEffort(effort: String): OpenRouterPipe`

Sets reasoning effort for reasoning-capable models. Convenience method that creates [ReasoningConfig] with only effort.

- `@param effort` Reasoning effort: "xhigh", "high", "medium", "low", "minimal", or "none"
- `@return` This pipe instance for fluent chaining

---

### `setReasoningConfig(config: env.ReasoningConfig): OpenRouterPipe`

Sets the full reasoning configuration for reasoning-capable models.

- `@param config` Reasoning configuration with effort, maxTokens, exclude, enabled
- `@return` This pipe instance for fluent chaining

```kotlin
val pipe = OpenRouterPipe()
    .setReasoningConfig(
        env.ReasoningConfig(
            effort = "high",
            maxTokens = 8192,
            enabled = true
        )
    )
```

---

## Function Calling

### `setTools(tools: List<env.ToolDefinition>): OpenRouterPipe`

Sets the tool definitions for function calling.

- `@param tools` List of tool definitions
- `@return` This pipe instance for fluent chaining

---

### `setToolChoice(choice: String): OpenRouterPipe`

Sets the tool choice mode for function calling.

- `@param choice` Tool choice: "auto", "none", or "required"
- `@return` This pipe instance for fluent chaining

---

### `setParallelToolCalls(enabled: Boolean): OpenRouterPipe`

Sets whether to enable parallel function calling.

- `@param enabled` True to enable parallel calls (default: true)
- `@return` This pipe instance for fluent chaining

---

## Provider Options

### `setProviderPreferences(prefs: env.ProviderPreferences): OpenRouterPipe`

Sets provider routing preferences to control which providers handle requests.

- `@param prefs` Provider preferences configuration
- `@return` This pipe instance for fluent chaining

```kotlin
val pipe = OpenRouterPipe()
    .setProviderPreferences(
        env.ProviderPreferences(
            order = listOf("openai", "anthropic"),
            allow_fallbacks = true
        )
    )
```

---

### `setServiceTier(tier: String): OpenRouterPipe`

Sets the service tier for request priority.

- `@param tier` Service tier: "auto", "default", "flex", "priority", or "scale"
- `@return` This pipe instance for fluent chaining

---

### `setPlugins(plugins: List<env.Plugin>): OpenRouterPipe`

Sets OpenRouter plugins for extended functionality (web search, file parsing, etc.).

- `@param plugins` List of plugins to enable
- `@return` This pipe instance for fluent chaining

---

### `setOpenRouterUser(userId: String): OpenRouterPipe`

Sets the end-user identifier for abuse detection. Note: Renamed from `user` to avoid collision with base class property.

- `@param userId` User identifier
- `@return` This pipe instance for fluent chaining

---

## Response Format

### `setResponseFormat(type: String, schema: kotlinx.serialization.json.JsonObject? = null): OpenRouterPipe`

Sets the response format for structured output.

- `@param type` Format type: "text", "json_object", or "json_schema"
- `@param schema` Optional JSON schema for json_schema type
- `@return` This pipe instance for fluent chaining

---

### `setStructuredOutputs(enabled: Boolean): OpenRouterPipe`

Sets whether to enable structured outputs via json_schema.

- `@param enabled` True to enable structured outputs
- `@return` This pipe instance for fluent chaining

---

### `setVerbosity(level: String): OpenRouterPipe`

Sets the response verbosity level.

- `@param level` Verbosity: "low", "medium", "high", or "max"
- `@return` This pipe instance for fluent chaining

---

### `setLogprobs(enabled: Boolean): OpenRouterPipe`

Sets whether to return log probabilities.

- `@param enabled` True to return log probabilities
- `@return` This pipe instance for fluent chaining

---

### `setTopLogprobs(count: Int): OpenRouterPipe`

Sets the number of top log probabilities to return.

- `@param count` Number of top log probabilities (0-20)
- `@return` This pipe instance for fluent chaining

---

## Caching

### `setCacheControl(ttl: String): OpenRouterPipe`

Sets cache control with TTL for Anthropic-style caching.

- `@param ttl` Cache TTL (e.g., "5m", "1h", "24h")
- `@return` This pipe instance for fluent chaining

---

### `setSessionId(id: String): OpenRouterPipe`

Sets the session ID for request grouping/observability.

- `@param id` Session identifier
- `@return` This pipe instance for fluent chaining

---

## Streaming

### `setStreamingEnabled(enabled: Boolean): OpenRouterPipe`

Enables or disables streaming mode.

- `@param enabled` True to enable streaming
- `@return` This pipe instance for fluent chaining

---

### `setStreamingCallback(callback: suspend (String) -> Unit): OpenRouterPipe`

Registers a callback for streaming response chunks. Automatically enables streaming mode.

- `@param callback` Suspendable callback receiving text chunks
- `@return` This pipe instance for fluent chaining

---

## Tracing Behavior

**Non-streaming requests:**
- Captures `inputTokens`, `outputTokens`, `totalTokens` from API response
- Records `responseLength` of the generated text
- Includes `model` name from response
- Sets `apiType` to "ChatAPI"

**Streaming requests:**
- Captures `responseLength` of accumulated text
- Traces streaming start and completion
- Sets `streaming` flag to true

All requests trace:
- `provider` ("OpenRouter")
- `baseUrl`
- `model`
- `promptLength`
- `streaming` enabled/disabled

---

## Error Mapping

OpenRouterPipe maps API errors to [P2PError] types:

| Error Type | HTTP Code | P2PError |
| :--- | :--- | :--- |
| Authentication failure | 401 | `P2PError.auth` |
| Invalid API key | 400 | `P2PError.prompt` |
| Invalid request | 400 | `P2PError.prompt` |
| Rate limit | 429 | `P2PError.transport` |
| Server error | 5xx | `P2PError.transport` |
| Network timeout | - | `P2PError.transport` |

---

## See Also

- [Pipe Base Class](./pipe.md)
- [Getting Started Guide](../openrouter/getting-started.md)
- [OpenRouter Documentation](https://openrouter.ai/docs)