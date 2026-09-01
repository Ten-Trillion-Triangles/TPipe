# Getting Started with GenericOpenAI

## Table of Contents
- [Introduction](#introduction)
- [Prerequisites](#prerequisites)
- [Basic Usage](#basic-usage)
- [API Modes](#api-modes)
- [Authentication](#authentication)
- [Codex OAuth access profile](#codex-oauth-access-profile)
- [Your First Pipe (per mode)](#your-first-pipe-per-mode)
- [Third-Party Providers](#third-party-providers)
- [Structured Outputs](#structured-outputs)
- [Function Calling](#function-calling)
- [Reasoning Models](#reasoning-models)
  - [Bedrock Mantle Reasoning](#bedrock-mantle-reasoning)
- [Streaming](#streaming)
  - [Streaming Trace Events and Token Reporting](#streaming-trace-events-and-token-reporting)
- [Anthropic-Style Caching](#anthropic-style-caching)
- [Multimodal Content](#multimodal-content)
- [Comparison with Other Providers](#comparison-with-other-providers)
- [Troubleshooting](#troubleshooting)
- [Next Steps](#next-steps)

## Introduction

`TPipe-GenericOpenAI` is a single TPipe pipe that talks to any provider implementing an OpenAI-compatible API surface. That includes the obvious targets — OpenAI, Azure OpenAI — but also Anthropic's `/v1/messages` endpoint, DeepSeek, Groq, Together, MiniMax, and any custom in-house proxy that speaks the OpenAI Chat Completions, Anthropic Messages, or OpenAI Responses wire spec. You pick the wire format with `setApiMode(ApiMode.…)`, set the endpoint with `setBaseUrl(…)`, and the rest of TPipe's pipeline/orchestration surface (pipelines, junctions, distribution grids, PCP tools) stays identical to every other TPipe provider.

## Prerequisites

1.  **API key for hosted providers.** OpenAI keys come from [platform.openai.com](https://platform.openai.com), Anthropic keys from [console.anthropic.com](https://console.anthropic.com), and so on. Exact loopback servers may be used without a key. For hosted endpoints, you can rely on the `GENERIC_OPENAI_API_KEY` environment variable and skip programmatic key management entirely.
2.  **A Gradle Kotlin DSL project.** Maven and Groovy DSL are not supported by TPipe.
3.  **Java 24+ (GraalVM CE 24 recommended).** Matches the rest of the TPipe toolchain.
4.  **Project dependency.** Add the `:TPipe-GenericOpenAI` module:
    ```kotlin
    // settings.gradle.kts
    include(":TPipe-GenericOpenAI")

    // build.gradle.kts
    dependencies {
        implementation(project(":TPipe-GenericOpenAI"))
    }
    ```

## Basic Usage

The shortest path to a working pipe: build it, configure the API key, pick a model, init, and execute. The default `ApiMode.OpenAI` mode is used unless you call `setApiMode(...)`.

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o")
        .setSystemPrompt("You are a helpful assistant.")
        .setTemperature(0.7)
        .init()

    val result = pipe.execute("What is the capital of France?")
    println(result.text)
}
```

If you would rather resolve the key once for the whole process, set the `GENERIC_OPENAI_API_KEY` environment variable and skip `setApiKey()`:

```bash
export GENERIC_OPENAI_API_KEY="sk-..."
```

`init()` reads it automatically. It throws `IllegalStateException` when neither path is configured for a non-loopback endpoint; local loopback endpoints may proceed with no key.

## API Modes

`GenericOpenAIPipe` is wire-format polymorphic. The `ApiMode` sealed class selects which endpoint, auth header set, request serializer, and SSE parser the pipe uses. The mode is locked after the first API call — see the [warning box](#your-first-pipe-per-mode) below.

| Mode | Endpoint | Auth header(s) | When to use |
|:---|:---|:---|:---|
| `ApiMode.OpenAI` (default) | `${baseUrl}/chat/completions` by default | `Authorization: Bearer <key>` when keyed | OpenAI, Azure OpenAI, DeepSeek, Groq, Together, MiniMax, or a compatible proxy |
| `ApiMode.Anthropic` | `${baseUrl}/v1/messages` by default | `x-api-key: <key>`, `anthropic-version: 2023-06-01` | Anthropic Claude or a Messages-compatible proxy |
| `ApiMode.OpenAIResponses` | `${baseUrl}/responses` by default | `Authorization: Bearer <key>` when keyed | OpenAI's newer Responses wire spec (`response.created` / `response.output_text.delta` / `response.completed` events) |

For local servers that expose all routes beneath `/v1`, use `GenericOpenAIEndpointProfile.localV1()`. This changes the OpenAI and Responses paths; Anthropic already uses `/v1/messages` by default. For MiniMax's `/anthropic/v1/messages` route, use `GenericOpenAIEndpointProfile.miniMax()` explicitly when the base URL is `https://api.minimax.io`.

The `getAuthHeaders()` and `getEndpoint()` methods on the pipe select the right values for the active mode at the moment the request is built, so the rest of your configuration is identical across modes.

## Authentication

There are three ways to provide credentials, checked in this order by `init()`:

1.  **Programmatic** via the pipe's builder:
    ```kotlin
    val pipe = GenericOpenAIPipe()
        .setApiKey("sk-...")
    ```
2.  **Process-global** via the `GenericOpenAIEnv` singleton:
    ```kotlin
    import genericOpenAIPipe.env.genericOpenAIEnv

    genericOpenAIEnv.setApiKey("sk-...")
    val pipe = GenericOpenAIPipe() // no setApiKey needed
    ```
3.  **Environment variable** `GENERIC_OPENAI_API_KEY`:
    ```bash
    export GENERIC_OPENAI_API_KEY="sk-..."
    ```

`init()` walks the chain in this order: pipe-level `apiKey` field → `GenericOpenAIEnv.resolveApiKey()` (programmatic + env). If nothing resolves, only an exact loopback base URL may continue without credentials; hosted endpoints throw `IllegalStateException`. Use `genericOpenAIEnv.hasApiKey()` to probe before constructing a hosted pipe.

### Codex OAuth access profile

For ChatGPT subscription-backed Codex access, use the `TPipe-Codex` factory. It
returns the same `GenericOpenAIPipe` class, but supplies a transient OAuth access
profile and Codex-specific Responses wire policy:

```kotlin
implementation(project(":TPipe-Codex"))
```

```kotlin
val auth = CodexAuthManager.default()
val pipe = CodexPipes.create("gpt-5-codex", auth)
```

The profile forces streaming on the wire, emits `store=false` and
`include=["reasoning.encrypted_content"]`, suppresses generic sampling and
identity controls, and does not translate TPipe PCP tools into native provider
tools. See [Codex OAuth](../codex/getting-started.md) for login, file import,
refresh, and model discovery.

### Endpoint overrides for proxies and enterprise gateways

Most providers expose the OpenAI Chat Completions surface at a different base URL. Use `setBaseUrl(...)` to point the pipe at it. HTTPS is accepted for valid hosts. Plain HTTP is accepted automatically only for exact loopback targets (`localhost`, `127.0.0.0/8`, and `::1`):

```kotlin
.setBaseUrl("https://api.openai.com/v1")        // OpenAI
.setBaseUrl("https://api.anthropic.com")         // Anthropic
.setBaseUrl("https://api.deepseek.com/v1")       // DeepSeek
.setBaseUrl("https://api.groq.com/openai/v1")    // Groq
.setBaseUrl("https://api.together.xyz/v1")       // Together
.setBaseUrl("https://api.minimax.io/v1")         // MiniMax
.setBaseUrl("https://openai.myenterprise.com")   // internal proxy
```

The trailing slash is stripped automatically.

### Local OpenAI-compatible server

The generic pipe can target a plaintext local server without a provider-specific module. `localV1()` selects the common `/v1` route convention, and no API key is required for loopback URLs:

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.GenericOpenAIEndpointProfile
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setBaseUrl("http://127.0.0.1:8080")
        .setEndpointProfile(GenericOpenAIEndpointProfile.localV1())
        .setModel("local-model")
        .init()

    println(pipe.execute("Say hello in one sentence.").text)
}
```

The automatic plaintext exception is a narrow security boundary: only `localhost`, `127.0.0.0/8`, and `::1` qualify. Private-network addresses such as `192.168.x.x` and `10.x.x.x`, LAN hostnames, hostname-spoofing names such as `localhost.example.com`, embedded credentials, and malformed URLs are rejected. A non-loopback HTTP endpoint requires the explicit `TPIPE_ALLOW_INSECURE_BASEURL=true` environment variable or `tpipe.allowInsecureBaseUrl=true` system property override.

## Your First Pipe (per mode)

The same pipe class behaves differently per `ApiMode`. Three minimal, end-to-end working examples:

### OpenAI mode (default)

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o")
        .setSystemPrompt("You are a helpful assistant.")
        .init()

    println(pipe.execute("What is TPipe?").text)
}
```

### Anthropic mode (Claude via `/messages`)

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("ANTHROPIC_API_KEY"))
        .setBaseUrl("https://api.anthropic.com")
        .setModel("claude-3-5-sonnet-20241022")
        .setApiMode(ApiMode.Anthropic)
        .setMaxTokens(1024)
        .init()

    println(pipe.execute("Explain quantum entanglement in one sentence.").text)
}
```

The Anthropic mode swaps the auth header set to `x-api-key` + `anthropic-version` automatically, routes through `AnthropicRequestSerializer` to produce an `AnthropicMessagesRequest`, and uses `AnthropicSseParser` for streaming.

### OpenAI Responses mode

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o-2025-04-16")
        .setApiMode(ApiMode.OpenAIResponses)
        .init()

    println(pipe.execute("Summarize the plot of Hamlet in three bullets.").text)
}
```

> **⚠ `apiMode` and `endpointProfile` are locked after the first API call.** Calling either setter after `execute()` / `generateText()` / `generateContent()` has been invoked throws `IllegalStateException`. Set both up front, before the first call. If you need to switch modes or route profiles, build a new pipe instance.

## Third-Party Providers

Every provider on this list is reached by combining `setApiMode(ApiMode.OpenAI)` with a provider-specific `setBaseUrl(...)`. The wire format is the same; only the URL and the key change.

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // DeepSeek
    val deepseek = GenericOpenAIPipe()
        .setApiKey(System.getenv("DEEPSEEK_API_KEY"))
        .setBaseUrl("https://api.deepseek.com/v1")
        .setModel("deepseek-chat")
        .setApiMode(ApiMode.OpenAI)
        .init()

    // Groq
    val groq = GenericOpenAIPipe()
        .setApiKey(System.getenv("GROQ_API_KEY"))
        .setBaseUrl("https://api.groq.com/openai/v1")
        .setModel("llama-3.1-70b-versatile")
        .setApiMode(ApiMode.OpenAI)
        .init()

    // Together
    val together = GenericOpenAIPipe()
        .setApiKey(System.getenv("TOGETHER_API_KEY"))
        .setBaseUrl("https://api.together.xyz/v1")
        .setModel("meta-llama/Llama-3-70b-chat-hf")
        .setApiMode(ApiMode.OpenAI)
        .init()

    // MiniMax
    val MiniMax = GenericOpenAIPipe()
        .setApiKey(System.getenv("MINIMAX_API_KEY"))
        .setBaseUrl("https://api.minimax.io/v1")
        .setModel("MiniMax-M2")
        .setApiMode(ApiMode.OpenAI)
        .init()

    // Azure-style proxy (OpenAI Responses spec)
    val azureResponses = GenericOpenAIPipe()
        .setApiKey(System.getenv("AZURE_OPENAI_KEY"))
        .setBaseUrl("https://my-resource.openai.azure.com/openai/deployments/gpt-4o")
        .setModel("gpt-4o")
        .setApiMode(ApiMode.OpenAIResponses)
        .init()

    println(deepseek.execute("Hello").text)
    println(groq.execute("Hello").text)
}
```

If you want to point the pipe at an internal proxy, set its base URL with `setBaseUrl("https://openai.myenterprise.com")` — as long as it speaks one of the three supported wire formats, no other configuration is needed.

## Structured Outputs

Constrain the model to a JSON shape using `setResponseFormat(...)` together with `setStructuredOutputs(true)`. The schema is a standard JSON Schema `JsonObject`:

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun main() = runBlocking {
    val schema = Json.parseToJsonElement("""
        {
            "type": "object",
            "properties": {
                "name":  {"type": "string"},
                "score": {"type": "number"}
            },
            "required": ["name", "score"]
        }
    """).jsonObject

    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o")
        .setResponseFormat("json_schema", schema)
        .setStructuredOutputs(true)
        .init()

    println(pipe.execute("Score the name 'Ada Lovelace' from 0 to 10 and explain why.").text)
}
```

Three `type` values are supported: `"text"` (default, plain prose), `"json_object"` (free-form JSON object mode), and `"json_schema"` (validated against the supplied schema — `jsonSchema` becomes required for that type).

## Function Calling

Register function-calling tools with `setTools(...)`, choose the model's tool-selection mode with `setToolChoice(...)`, and optionally allow parallel calls with `setParallelToolCalls(true)`:

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.ToolDefinition
import genericOpenAIPipe.env.FunctionSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun main() = runBlocking {
    val weatherParams = Json.parseToJsonElement("""
        {
            "type": "object",
            "properties": {
                "location": {"type": "string"},
                "unit":     {"type": "string", "enum": ["c", "f"]}
            },
            "required": ["location"]
        }
    """).jsonObject

    val tools = listOf(
        ToolDefinition(
            type = "function",
            function = FunctionSchema(
                name = "get_weather",
                description = "Get current weather for a location.",
                parameters = weatherParams
            )
        )
    )

    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o")
        .setTools(tools)
        .setToolChoice("auto")
        .setParallelToolCalls(true)
        .init()

    val reply = pipe.execute("What is the weather in Paris and Tokyo?")
    println(reply.text)
}
```

Valid `setToolChoice(...)` values are `"auto"`, `"none"`, and `"required"`. `setParallelToolCalls(true)` lets the model emit multiple tool calls in one response (default behaviour of most modern tool-calling models).

## Reasoning Models

Reasoning-capable models (OpenAI o3 / o4-mini, DeepSeek-R1, etc.) accept a `ReasoningConfig` with effort, max-tokens, and visibility flags. The fields are serialized into the request body for the active mode:

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.ReasoningConfig
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("o4-mini")
        .setReasoningConfig(
            ReasoningConfig(
                effort = "high",         // "xhigh", "high", "medium", "low", "minimal", "none"
                maxTokens = 8192,
                exclude = false,         // include reasoning in the final output
                enabled = true
            )
        )
        .init()

    val result = pipe.execute("Plan a 3-day trip to Kyoto in JSON.")
    println(result.text)
    // result.modelReasoning carries the chain-of-thought when supported
    println(result.modelReasoning)
}
```

Reasoning effort enum values: `"xhigh" | "high" | "medium" | "low" | "minimal" | "none"`. The Responses API mode (OpenAI) additionally populates `streamingReasoningTokens` from the wire and exposes them via tracing metadata.

### Bedrock Mantle Reasoning

For AWS Bedrock Mantle models, configure via `BedrockMantleConfiguration` and `BedrockMantleAuth`:

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.BedrockMantleConfiguration
import genericOpenAIPipe.env.BedrockMantleAuth
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("AWS_ACCESS_KEY_ID"))       // not used for Mantle auth
        .setModel("google.gemma-4-e2b")
        .configureBedrockMantle(
            BedrockMantleConfiguration(
                modelId = "google.gemma-4-e2b",
                region  = "us-east-1"
            )
        )
        .init()

    val result = pipe.execute("Explain the CAP theorem in one sentence.")
    println(result.text)
}
```

`configureBedrockMantle` is available on `GenericOpenAIPipe` after the `BedrockMantleConfiguration` import. It wires SigV4 credentials from the environment (AWS access key + secret, or an attached IAM role) and sets the internal `ApiMode` to `Anthropic`.

The function also populates the reasoning-pipe metadata contract that TPipe's `getMiddlePromptForReasoning()` and `getFooterPromptForReasoning()` read at execution time:

| Metadata key | Value | Corresponding `ReasoningSettings` default |
|:---|:---|:---|
| `injectMiddlePrompt` | `false` | `ReasoningSettings:142` |
| `injectFooterPrompt` | `false` | `ReasoningSettings:143` |
| `reinforceSystemPrompt` | `false` | `ReasoningSettings:144` |

These defaults match the TPipe `ReasoningSettings` defaults. Mantle has no `ReasoningBuilder`-style settings object — the values are written by hand inside `configureBedrockMantle`.

If you require a JSON-completion footer prompt (for example, to force structured output), set `injectFooterPrompt = true` on the pipe after construction and call `setFooterPrompt(...)` yourself:

```kotlin
val pipe = GenericOpenAIPipe()
    .setModel("google.gemma-4-e2b")
    .configureBedrockMantle(BedrockMantleConfiguration(modelId = "google.gemma-4-e2b", region = "us-east-1"))
    .apply {
        pipeMetadata["injectFooterPrompt"] = true
        setFooterPrompt("You must respond with valid JSON matching the following schema...")
    }
    .init()
```

`getFooterPromptForReasoning()` reads `footerPrompt` only when `injectFooterPrompt` is `true`; without that flag, the footer is silent at the wire.

## Streaming

Both `setStreamingEnabled(true)` and `setStreamingCallback { ... }` enable Server-Sent Events streaming. The callback receives text chunks as they arrive and the pipe also accumulates them into the final result:

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = GenericOpenAIPipe()
        .setApiKey(System.getenv("OPENAI_API_KEY"))
        .setModel("gpt-4o")
        .setStreamingCallback { chunk ->
            print(chunk)
            kotlin.io.stdout.flush()
        }
        .init()

    pipe.execute("Stream a haiku about distributed systems.")
    println()
}
```

SSE format is mode-specific:
- `ApiMode.OpenAI` — standard `data: {...}` lines parsed by `SseParser`.
- `ApiMode.Anthropic` — `event:` + `data:` lines parsed by `AnthropicSseParser`.
- `ApiMode.OpenAIResponses` — `response.created` / `response.output_text.delta` / `response.completed` events parsed by `OpenAIResponsesSseParser`.

The pipe automatically routes to the right parser based on the active `ApiMode`.

### Streaming Trace Events and Token Reporting

When tracing is enabled, each streaming turn emits an `API_CALL_SUCCESS` event whose metadata carries `inputTokens`, `outputTokens`, and `totalTokens` as reported by the provider.

For `ApiMode.Anthropic` (including Mantle via `configureBedrockMantle`), the pipe reads `input_tokens` from the `message_start.usage` SSE event — the official provider source — and carries that value into the trace. The SSE loop does not discard `message_start` for token purposes; the first accurate value is captured and not overwritten by a later `message_delta` that carries `input_tokens: 0`.

For `ApiMode.OpenAIResponses`, `inputTokens` flows from `response.completed.usage.input_tokens`. This path has always been correct and is pinned by a regression test.

For `ApiMode.OpenAI`, token counts are read from the non-streaming response body after the SSE loop completes.

The `totalTokens` field in the trace is the arithmetic sum `inputTokens + outputTokens` for all three modes.

```
// Enable tracing to see token metadata on every call
pipe.enableTracing(TraceConfig(enabled = true, includeMetadata = true))
pipe.addTraceId("my-pipeline")
pipe.init()

val result = pipe.execute("Hello")
// After the call, read the trace:
//   PipeTracer.getTrace("my-pipeline")
//   → API_CALL_SUCCESS
//   → metadata.inputTokens   = provider-billed input tokens
//   → metadata.outputTokens  = provider-billed output tokens
//   → metadata.totalTokens   = inputTokens + outputTokens
```

If you observe `inputTokens = 0` for an Anthropic streaming call in the trace, the pipe is not reading `message_start.usage.input_tokens` from the SSE stream correctly. Verify that the SSE loop captures that event before the first `content_block_delta`.

## Anthropic-Style Caching

The `CacheControl` data class carries Anthropic-style prompt-caching hints (cache type + optional TTL). It is defined in `genericOpenAIPipe.env` and serialized into the request body as `cache_control` when present:

```kotlin
import genericOpenAIPipe.env.CacheControl

val caching = CacheControl(
    type = "ephemeral",  // required: e.g., "ephemeral"
    ttl = "5m"           // optional: e.g., "5m", "1h", "24h"
)
```

`CacheControl` is a wire-level field on `GenericOpenAIChatRequest` (set to `cache_control` in the JSON body), so you use it when you construct a request directly or wire it into a custom request pipeline. A dedicated `setCacheControl(...)` builder is not currently exposed on the pipe — for a higher-level ergonomic API, use `setApiMode(ApiMode.Anthropic)` and the Anthropic-specific caching behaviour that the provider supports.

## Multimodal Content

Pass images and documents to the pipe via `MultimodalContent.binaryContent`. The pipe converts each `BinaryContent` variant to the right content block shape for the active mode at request-build time:

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
            BinaryContent.Bytes(
                data = imageBytes,
                mimeType = "image/png"
            )
        )
    )

    println(pipe.execute(content).text)
}
```

The conversion matrix for each `BinaryContent` variant across the three modes:

| `BinaryContent` type | OpenAI mode | Anthropic mode | OpenAI Responses mode |
|:---|:---|:---|:---|
| `Bytes` | base64 data URI image block | base64 image block | base64 input item |
| `Base64String` | base64 data URI image block | base64 image block | base64 input item |
| `CloudReference` | URL image block | URL image block | URL input item |
| `TextDocument` | text block | text block | text input item |

`Bytes` and `Base64String` are equivalent on the wire — both end up as a `data:<mime>;base64,...` image URL. `CloudReference` passes the URL through as-is. `TextDocument` injects the text into the content array.

## Comparison with Other Providers

| Feature | `GenericOpenAIPipe` | `OllamaPipe` | `OpenRouterPipe` | `BedrockPipe` |
|:---|:---|:---|:---|:---|
| **Provider access** | Any OpenAI-compatible API (OpenAI, Azure, Anthropic via `/messages`, DeepSeek, Groq, Together, MiniMax, custom proxies) | Local Ollama runtime | 300+ models through OpenRouter | AWS Bedrock (Claude, Titan, Llama, Cohere, …) |
| **API modes** | 3 (OpenAI, Anthropic, OpenAIResponses) | 2 (`/api/chat`, legacy `/api/generate`) | 1 (OpenAI Chat Completions) | 1 (Bedrock Converse API) |
| **Tool calling** | `setTools(...)` + parallel + `setToolChoice(...)` | Native tool calling | OpenAI-compatible | Converse API tools |
| **Reasoning** | `setReasoningConfig(ReasoningConfig(...))` | `enableThink()` extracts `<think>` | `setReasoningConfig(...)` / `setReasoningEffort(...)` | Native `reasoningContent` |
| **Streaming** | SSE callback, mode-specific parsers | Ktor async + `enableStreaming(...)` | Server-Sent Events | Converse stream handler |
| **Multimodal** | `MultimodalContent.binaryContent` with mode-specific conversion | Base64 images | OpenAI-compatible | Base64 images |
| **Caching** | `CacheControl(type, ttl)` (Anthropic-style) | n/a | `setCacheControl(ttl)` | n/a |
| **Free tier** | Depends on provider | Yes (local) | Yes (limited free models) | No |
| **Auth pattern** | `setApiKey` / `GenericOpenAIEnv` / `GENERIC_OPENAI_API_KEY`; loopback may omit key | Local socket | `setApiKey` / `OPENROUTER_API_KEY` | AWS credentials / IAM roles |
| **Endpoint override** | `setBaseUrl(...)` + optional `localV1()` profile | `setIP` / `setPort` | `setBaseUrl(...)` | `setRegion(...)` |

Use `GenericOpenAIPipe` when the provider you want is not first-class in TPipe, or when you need the Anthropic `/messages` or OpenAI Responses surface from a single pipe class.

## Troubleshooting

### `IllegalStateException: GenericOpenAI API key is required`

You called `init()` without a key for a non-loopback endpoint. Fix in one of three ways:
- Call `.setApiKey("sk-...")` on the pipe before `init()`.
- Call `genericOpenAIEnv.setApiKey("sk-...")` before constructing the pipe.
- Export `GENERIC_OPENAI_API_KEY` in the process environment.

Probe at startup with `genericOpenAIEnv.hasApiKey()` if you want to fail fast with a friendlier error.

Loopback endpoints are the exception: `http://localhost`, `http://127.0.0.0/8`, and `http://[::1]` may run without a key. Blank keys omit Bearer / `x-api-key` headers; Anthropic still receives `anthropic-version`.

### `IllegalArgumentException: baseUrl must use HTTPS for security`

`setBaseUrl(...)` enforces HTTPS except for exact loopback targets. If you are behind a TLS-terminating proxy, configure it to forward HTTPS upstream. For a deliberate non-loopback plaintext compatibility endpoint, set `TPIPE_ALLOW_INSECURE_BASEURL=true` or `tpipe.allowInsecureBaseUrl=true` explicitly and understand the transport-security tradeoff.

### `IllegalStateException: apiMode cannot be changed after the first API request`

You called `setApiMode(...)` after `execute()`, `generateText()`, or `generateContent()`. The mode is locked at first use. Build a second `GenericOpenAIPipe` with the new mode, or set the mode before the first call.

### 401 / 403 / 429 / 5xx responses

`GenericOpenAIPipe` maps these to `P2PError` types — see the [Error Mapping](../api/generic-openai-pipe.md#error-mapping) table in the API reference. The most common root causes:

- **401** — wrong provider key, or key and base URL are mismatched (DeepSeek key against OpenAI's URL, for example).
- **403** — region / model access not granted. AWS Bedrock and Azure both gate specific models; request access in the provider console.
- **429** — rate limit. Back off and retry, or use a higher service tier / quota.
- **5xx** — provider-side fault. Retry with exponential backoff.

### Output truncated / `finishReason = "length"`

You hit the model's max-output-tokens limit. Raise `setMaxTokens(...)` or shorten the prompt. For very long completions, prefer streaming with `setStreamingCallback(...)` so partial output is visible.

### Streaming callback never fires

You called `setStreamingEnabled(false)` (or didn't call `setStreamingCallback`/`setStreamingEnabled(true)`). The pipe defaults to non-streaming mode. Either set `setStreamingEnabled(true)` explicitly, or pass a `setStreamingCallback { ... }` — registering a callback auto-enables streaming.

### `apiMode` does not change wire format mid-conversation

Even if you re-call `setApiMode(...)` between calls, the second call throws. Re-create the pipe. Internally, `apiModeLocked` is set to `true` inside `sendRequest(...)` on the first request, before the response is even read.

## Next Steps

- [`GenericOpenAIPipe` Class API](../api/generic-openai-pipe.md) — Full builder reference, env singleton, error mapping, and `ApiMode` details.
- [`Pipe` Class API](../api/pipe.md) — Core pipe abstraction and base-class builders (`setModel`, `setTemperature`, `setMaxTokens`, `setSystemPrompt`, etc.).
- [Pipe Context Protocol](../advanced-concepts/pipe-context-protocol.md) — Attach PCP tools to a `GenericOpenAIPipe` for sandboxed multi-language tool execution.
