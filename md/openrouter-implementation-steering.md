# TPipe-OpenRouter: Implementation Steering Document

**Purpose**: Guide the first-stage implementation of OpenRouter as a TPipe provider module.
**Status**: Draft — for review and validation before implementation begins
**Last Updated**: 2026-04-11

---

## Table of Contents

1. [OpenRouter API Overview](#1-openrouter-api-overview)
2. [TPipe Provider Module Pattern](#2-tpipe-provider-module-pattern)
3. [TPipe-OpenRouter Module Design](#3-tpipe-openrouter-module-design)
4. [Implementation Roadmap](#4-implementation-roadmap)
5. [File Inventory](#5-file-inventory)
6. [Validation Checklist](#6-validation-checklist)
7. [Risks and Mitigations](#7-risks-and-mitigations)

---

## 1. OpenRouter API Overview

### 1.1 What is OpenRouter?

OpenRouter provides a **unified API** that aggregates hundreds of LLM providers (OpenAI, Anthropic, Google, Meta, DeepSeek, Mistral, etc.) behind a single OpenAI-compatible endpoint. It handles model routing, fallback selection, and credit management centrally.

**Key advantages for TPipe integration:**
- Single API key grants access to 300+ models across all major providers
- OpenAI-compatible (`/v1/chat/completions`) — minimal adaptation required
- Built-in streaming support matching OpenAI's SSE format
- Automatic model fallback on provider failures
- Per-request model selection (no need to pre-select a specific provider)

### 1.2 Base URL and Authentication

```
Base URL: https://openrouter.ai/api/v1
```

**Required Headers:**

| Header | Value | Purpose |
|--------|-------|---------|
| `Authorization` | `Bearer <OPENROUTER_API_KEY>` | API key authentication |
| `Content-Type` | `application/json` | Request body format |

**Optional Headers:**

| Header | Value | Purpose |
|--------|-------|---------|
| `HTTP-Referer` | `<YOUR_SITE_URL>` | Attribution for OpenRouter site rankings |
| `X-OpenRouter-Title` | `<YOUR_APP_NAME>` | App name for OpenRouter leaderboard |

### 1.3 Core Endpoint: Chat Completions

**Endpoint:** `POST https://openrouter.ai/api/v1/chat/completions`

**Request Body Schema:**

```json
{
  "model": "anthropic/claude-3-5-sonnet-20241022",   // Required - OpenRouter model ID
  "messages": [                                        // Required - conversation messages
    { "role": "system", "content": "You are helpful." },
    { "role": "user", "content": "Hello" }
  ],
  "temperature": 1.0,          // Optional (0-2, default varies by model)
  "top_p": 1.0,               // Optional (0-1)
  "max_tokens": 4096,         // Optional - max response tokens
  "max_completion_tokens": 8192, // Alternative to max_tokens
  "stream": false,             // Optional - enable streaming (SSE)
  "top_logprobs": 0,           // Optional - for logprob sampling
  "logprobs": false,           // Optional - return log probabilities
  "presence_penalty": 0.0,    // Optional (-2.0 to 2.0)
  "frequency_penalty": 0.0,   // Optional (-2.0 to 2.0)
  "stop": ["END"],            // Optional - stop sequences (up to 4)
  "seed": null,                // Optional - deterministic sampling
  "tools": [],                // Optional - tool definitions (function calling)
  "tool_choice": "auto",      // Optional - tool selection mode
  "response_format": {},      // Optional - json_object, json_schema, etc.
  "modalities": ["text"],     // Optional - output types: text, image, audio
  "plugins": [],             // Optional - web search, moderation, etc.
  "provider": {},             // Optional - routing preferences
  "reasoning": {}             // Optional - reasoning model config (e.g. DeepSeek R1)
}
```

**Response Schema (non-streaming):**

```json
{
  "id": "gen-1234567890",
  "object": "chat.completion",
  "created": 1715728400,
  "model": "anthropic/claude-3-5-sonnet-20241022",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 25,
    "completion_tokens": 20,
    "total_tokens": 45
  },
  "system_fingerprint": "fp_abc123",
  "service_tier": "default"
}
```

**Response Schema (streaming):**

```
data: {"id":"gen-123","choices":[{"delta":{"content":"Hello"},"index":0}]}
data: {"id":"gen-123","choices":[{"delta":{"content":"!"},"index":0}]}
data: [DONE]
```

### 1.4 Model Discovery API

**Endpoint:** `GET https://openrouter.ai/api/v1/models`

Returns a list of all available models. Each model entry contains:

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | OpenRouter model ID (e.g., `anthropic/claude-3-5-sonnet-20241022`) |
| `name` | string | Human-readable display name |
| `description` | string | Model description |
| `context_length` | integer | Maximum context window (tokens) |
| `pricing.prompt` | string | Price per 1M input tokens (USD) |
| `pricing.completion` | string | Price per 1M output tokens (USD) |
| `supported_parameters` | array | List of supported API parameters |
| `architecture.modality` | string | Input/output modalities (text, image, audio) |
| `top_provider` | object | Best provider for this model |

**Example model ID formats:**
- `anthropic/claude-3-5-sonnet-20241022`
- `openai/gpt-4-turbo`
- `google/gemini-pro-1.5`
- `deepseek/deepseek-r1`
- `meta-llama/llama-3-70b-instruct`

### 1.5 HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad Request — invalid parameters |
| 401 | Unauthorized — invalid or missing API key |
| 402 | Payment Required — insufficient credits |
| 404 | Not Found — model or endpoint not found |
| 408 | Request Timeout |
| 413 | Payload Too Large |
| 422 | Unprocessable Entity — validation error |
| 429 | Rate Limit Exceeded |
| 500 | Internal Server Error |
| 502 | Bad Gateway — upstream provider error |
| 503 | Service Unavailable |

### 1.6 Key Integration Points for TPipe

**Compatibility with existing TPipe patterns:**
- OpenAI-compatible endpoint means the request/response shape closely matches how TPipe-Bedrock handles AWS Converse API responses
- Streaming uses Server-Sent Events (SSE) — same pattern as OllamaPipe's streaming implementation
- Model ID is a string parameter, not baked into the client — aligns with TPipe's `setModel()` pattern
- No provider-specific SDK required — use raw HTTP (like OllamaPipe) or Ktor client (like BedrockPipe)

---

## 2. TPipe Provider Module Pattern

### 2.1 Module Structure

Every TPipe provider module follows this directory pattern:

```
TPipe-{ProviderName}/
├── build.gradle.kts
└── src/
    ├── main/kotlin/
    │   ├── env/                    # (optional) API request/response models
    │   │   ├── Endpoints.kt
    │   │   ├── Options.kt
    │   │   └── Response.kt
    │   └── {providerName}Pipe/
    │       └── {ProviderName}Pipe.kt
    └── test/kotlin/
        └── {providerName}Pipe/
            └── {ProviderName}ValidationTest.kt
```

**Existing modules for reference:**
- `TPipe-Ollama/` — simplest pattern (raw HTTP client)
- `TPipe-Bedrock/` — AWS SDK-based (BedrockRuntimeClient)

### 2.2 Build Configuration

Each provider module's `build.gradle.kts` follows this template:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

group = "com.TTT"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))     // Depends on root TPipe
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // HTTP client (Ktor client for OpenRouter)
    implementation("io.ktor:ktor-client-core:${version}")
    implementation("io.ktor:ktor-client-cio:${version}")
    implementation("io.ktor:ktor-client-content-negotiation:${version}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${version}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
```

### 2.3 ProviderName Enum

Add `OpenRouter` to the `ProviderName` enum in `src/main/kotlin/Enums/ProviderName.kt`:

```kotlin
enum class ProviderName {
    Aws,
    Nai,
    Gemini,
    Gpt,
    Ollama,
    OpenRouter   // <-- Add this
}
```

### 2.4 Core Interface to Implement

Providers extend `abstract class Pipe` which inherits from `P2PInterface` and `ProviderInterface`.

**Key abstract methods to implement:**

| Method | Purpose |
|--------|---------|
| `override suspend fun init(): Pipe` | Set `provider = ProviderName.OpenRouter`, validate API key, initialize HTTP client |
| `override fun truncateModuleContext(): Pipe` | Model-specific tokenization truncation (OpenRouter handles many model families) |
| `override suspend fun truncateModuleContextSuspend(): Pipe` | Suspend-safe truncation for remote-aware lorebook |

**Optional override:**

| Method | Purpose |
|--------|---------|
| `override fun cleanPromptText(content: MultimodalContent): MultimodalContent` | Clean prompt text for OpenRouter compatibility |

**Key inherited properties to set via fluent setters:**
- `model: String` — OpenRouter model ID (e.g., `anthropic/claude-3-5-sonnet-20241022`)
- `systemPrompt: String` — System prompt
- `maxTokens: Int` — Max response tokens
- `temperature: Double` — Sampling temperature

### 2.5 Existing Configuration Pattern (TPipe-Defaults)

Provider configurations live in `TPipe-Defaults/src/main/kotlin/Defaults/providers/`:

```kotlin
data class OpenRouterConfiguration(
    var model: String,
    var apiKey: String,
    var pipeCount: Int = 2,
    var baseUrl: String = "https://openrouter.ai/api/v1",
    var httpReferer: String = "",
    var openRouterTitle: String = "",
    var manifoldMemory: ManifoldMemoryConfiguration = ManifoldMemoryConfiguration()
) : ProviderConfiguration() {
    override fun validate(): Boolean =
        apiKey.isNotBlank() && model.isNotBlank() && pipeCount > 0
}

internal object OpenRouterDefaults {
    fun createManifold(config: OpenRouterConfiguration): Manifold {
        val managerPipeline = createManagerPipeline(config)
        return Manifold().apply {
            setManagerPipeline(managerPipeline)
            ManifoldDefaults.applyManifoldMemoryConfiguration(this, config.manifoldMemory)
        }
    }

    fun createWorkerPipe(config: OpenRouterConfiguration): OpenRouterPipe =
        createOpenRouterPipe(config)

    fun createOpenRouterPipe(config: OpenRouterConfiguration): OpenRouterPipe =
        OpenRouterPipe()
            .setModel(config.model)
            .setApiKey(config.apiKey)
            .setBaseUrl(config.baseUrl)
            .apply { config.httpReferer.takeIf { it.isNotBlank() }?.let { setHttpReferer(it) } }
            .apply { config.openRouterTitle.takeIf { it.isNotBlank() }?.let { setOpenRouterTitle(it) } }
}
```

---

## 3. TPipe-OpenRouter Module Design

### 3.1 Module Location

```
TPipe-OpenRouter/
├── build.gradle.kts
└── src/
    ├── main/kotlin/
    │   ├── env/
    │   │   ├── Endpoints.kt       # API endpoint constants
    │   │   ├── OpenRouterOptions.kt  # Request options
    │   │   └── ChatResponse.kt    # Response models
    │   └── openrouterPipe/
    │       └── OpenRouterPipe.kt  # Main Pipe implementation
    └── test/kotlin/
        └── openrouterPipe/
            └── OpenRouterPipeTest.kt
```

### 3.2 OpenRouterPipe Class Design

```kotlin
@kotlinx.serialization.Serializable
class OpenRouterPipe : Pipe() {

    // === Serialized Configuration Properties ===

    @kotlinx.serialization.Serializable
    private var apiKey: String = ""

    @kotlinx.serialization.Serializable
    private var baseUrl: String = "https://openrouter.ai/api/v1"

    @kotlinx.serialization.Serializable
    private var httpReferer: String = ""

    @kotlinx.serialization.Serializable
    private var openRouterTitle: String = ""

    @kotlinx.serialization.Serializable
    private var routingMode: RoutingMode = RoutingMode.Auto

    // === Transient (non-serialized) Runtime State ===

    @kotlinx.serialization.Transient
    private var httpClient: HttpClient? = null

    // === Builder Methods (fluent pattern, return OpenRouterPipe) ===

    fun setApiKey(key: String): OpenRouterPipe
    fun setBaseUrl(url: String): OpenRouterPipe
    fun setHttpReferer(referer: String): OpenRouterPipe
    fun setOpenRouterTitle(title: String): OpenRouterPipe
    fun setRoutingMode(mode: RoutingMode): OpenRouterPipe  // Auto, preferring specific provider

    // === Pipe Lifecycle Methods ===

    override suspend fun init(): Pipe {
        super.init()  // propagate to child pipes
        provider = ProviderName.OpenRouter
        httpClient = buildKtorHttpClient()  // Ktor CIO engine with JSON serialization
        validateConfiguration()
        return this
    }

    override suspend fun abort() {
        httpClient?.close()
        super.abort()
    }

    override suspend fun generateText(promptInjector: String = ""): String {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized")
        val requestBody = buildChatCompletionRequest(promptInjector)
        val response = executeRequest(client, requestBody)
        return parseResponse(response)
    }

    override fun truncateModuleContext(): Pipe {
        // OpenRouter supports multiple underlying models;
        // default to conservative token estimation
        return this
    }

    override suspend fun truncateModuleContextSuspend(): Pipe = truncateModuleContext()

    // === ProviderInterface ===

    override fun cleanPromptText(content: MultimodalContent): MultimodalContent {
        // OpenRouter is OpenAI-compatible — no special cleanup needed
        // but can strip any non-UTF8 sequences if needed
        return content
    }
}
```

### 3.3 Request Model: OpenRouterChatRequest

```kotlin
@Serializable
data class OpenRouterChatRequest(
    val model: String,                    // OpenRouter model ID
    val messages: List<ChatMessage>,       // Conversation messages
    val temperature: Double? = null,       // 0-2 range
    val top_p: Double? = null,            // 0-1 range
    val max_tokens: Int? = null,           // Max response tokens
    val max_completion_tokens: Int? = null,
    val stream: Boolean = false,          // Streaming flag
    val stop: List<String>? = null,        // Stop sequences
    val presence_penalty: Double? = null,  // -2.0 to 2.0
    val frequency_penalty: Double? = null, // -2.0 to 2.0
    val seed: Int? = null,                 // Deterministic sampling
    val tools: List<ToolDefinition>? = null,// Function calling
    val tool_choice: String? = null,       // Tool selection mode
    val response_format: ResponseFormat? = null,
    val modalities: List<String>? = null,  // Output types
    val provider: ProviderPreferences? = null, // Routing preferences
    val reasoning: ReasoningConfig? = null  // For reasoning models (e.g. DeepSeek R1)
)

@Serializable
data class ChatMessage(
    val role: String,        // "system", "user", "assistant", "developer"
    val content: String
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSchema
)

@Serializable
data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject  // JSON Schema for function params
)
```

### 3.4 Response Model: OpenRouterChatResponse

```kotlin
@Serializable
data class OpenRouterChatResponse(
    val id: String,
    val object_: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: UsageInfo,
    val system_fingerprint: String? = null,
    val service_tier: String? = null
) {
    // OpenAPI-compatible "object" is reserved keyword in Kotlin
    // Use @JsonProperty annotation or rename
}

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String,
    val logprobs: LogProbs? = null
)

@Serializable
data class UsageInfo(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
```

### 3.5 Streaming Support

OpenRouter uses Server-Sent Events (SSE) for streaming, identical to OpenAI's format:

```kotlin
// In OpenRouterPipe.generateText() or streaming variant:
if (streamingEnabled) {
    val streamResponse = client.post("$baseUrl/chat/completions") {
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $apiKey")
        httpReferer.takeIf { it.isNotBlank() }?.let { header("HTTP-Referer", it) }
        openRouterTitle.takeIf { it.isNotBlank() }?.let { header("X-OpenRouter-Title", it) }
        setBody(requestBody)
    }
    val channel = streamResponse.bodyAsChannel()
    // Parse SSE lines: "data: {...}" and "data: [DONE]"
    // Emit chunks via StreamingCallbackManager.emitToAll(chunk)
}
```

### 3.6 HTTP Client Setup

Use Ktor client (same as TPipe-Bedrock for HTTP operations):

```kotlin
private fun buildKtorHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(JsonContentNegotiation) {
            serialization(KotlinxSerializationInitializer.json)
        }
        engine {
            requestTimeout = 120_000  // 2 minute timeout
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }
}
```

### 3.7 RoutingMode Enum

OpenRouter supports provider routing preferences:

```kotlin
enum class RoutingMode {
    Auto,                    // OpenRouter chooses best provider
    PreferFastest,           // Prefer lowest latency
    PreferCheapest,          // Prefer lowest cost
    PreferQuality,           // Prefer best output quality
    SpecificProvider         // User-specified provider preference
}
```

---

## 4. Implementation Roadmap

### Phase 1: Module Skeleton and Build Configuration

**Goal**: Establish the `TPipe-OpenRouter` module with correct Gradle configuration.

**Files to create:**
1. `TPipe-OpenRouter/build.gradle.kts` — Module build file with Ktor dependencies
2. `settings.gradle.kts` — Add `include("TPipe-OpenRouter")`
3. `src/main/kotlin/openrouterPipe/.gitkeep` — Placeholder for source directory

**Validation**: `./gradlew :TPipe-OpenRouter:build` succeeds without compilation errors.

---

### Phase 2: Request/Response Models

**Goal**: Implement the data transfer objects for OpenRouter API.

**Files to create:**
1. `TPipe-OpenRouter/src/main/kotlin/env/Endpoints.kt` — API URL constants
2. `TPipe-OpenRouter/src/main/kotlin/env/OpenRouterOptions.kt` — Request options
3. `TPipe-OpenRouter/src/main/kotlin/env/ChatResponse.kt` — Response models

**Implementation detail**: Use `@Serializable` annotations with Kotlinx Serialization. Handle the `object` field in responses using `@JsonProperty("object")`.

**Validation**: Write unit tests that verify serialization/deserialization of request and response JSON.

---

### Phase 3: OpenRouterPipe Core Implementation

**Goal**: Implement the `OpenRouterPipe` class with non-streaming chat completions.

**Files to create:**
1. `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/OpenRouterPipe.kt` — Main pipe implementation

**Methods to implement:**
- `setApiKey()`, `setBaseUrl()`, `setHttpReferer()`, `setOpenRouterTitle()` — Builder methods
- `init()` — Set provider, build HTTP client, validate configuration
- `generateText()` — Build request, execute via Ktor, parse response
- `truncateModuleContext()` — Conservative token estimation
- `cleanPromptText()` — Pass-through (no special cleanup needed for OpenRouter)

**Validation**: `./gradlew :test --tests "*OpenRouterPipeTest*"` — Basic smoke test with a real or mocked OpenRouter API call.

---

### Phase 4: Streaming Support

**Goal**: Add streaming callback support to `OpenRouterPipe`.

**Changes to `OpenRouterPipe.kt`:**
- Add `streamingEnabled: Boolean = false` configuration flag
- Add `setStreamingEnabled(enabled: Boolean): OpenRouterPipe` builder method
- In `generateText()`, detect streaming flag and route to streaming executor
- Parse SSE `data: {...}` chunks and emit via `emitStreamingChunk()`

**Validation**: Verify streaming works end-to-end with a real model.

---

### Phase 5: TPipe-Defaults Integration

**Goal**: Add `OpenRouterDefaults` and `OpenRouterConfiguration` to TPipe-Defaults.

**Files to create in `TPipe-Defaults/src/main/kotlin/Defaults/providers/`:**
1. `OpenRouterDefaults.kt`
2. `OpenRouterConfiguration.kt`

**Validation**: Verify `Manifold` creation via `OpenRouterDefaults.createManifold(config)` works.

---

### Phase 6: ProviderName Enum Update

**Goal**: Add `OpenRouter` to the `ProviderName` enum.

**File to modify:** `src/main/kotlin/Enums/ProviderName.kt`
```kotlin
enum class ProviderName {
    Aws, Nai, Gemini, Gpt, Ollama, OpenRouter
}
```

**Validation**: Verify `ProviderName.OpenRouter` is accessible from `TPipe-OpenRouter`.

---

## 5. File Inventory

| # | File | Phase | Purpose |
|---|------|-------|---------|
| 1 | `TPipe-OpenRouter/build.gradle.kts` | 1 | Gradle build config with Ktor dependencies |
| 2 | `TPipe-OpenRouter/src/main/kotlin/env/Endpoints.kt` | 2 | API URL constants (`https://openrouter.ai/api/v1`) |
| 3 | `TPipe-OpenRouter/src/main/kotlin/env/OpenRouterOptions.kt` | 2 | Request body models (OpenRouterChatRequest, ChatMessage) |
| 4 | `TPipe-OpenRouter/src/main/kotlin/env/ChatResponse.kt` | 2 | Response models (OpenRouterChatResponse, ChatChoice, UsageInfo) |
| 5 | `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/OpenRouterPipe.kt` | 3, 4 | Main Pipe implementation with streaming support |
| 6 | `TPipe-Defaults/.../OpenRouterDefaults.kt` | 5 | Factory object for manifold/pipe creation |
| 7 | `TPipe-Defaults/.../OpenRouterConfiguration.kt` | 5 | Configuration data class |
| 8 | `src/main/kotlin/Enums/ProviderName.kt` | 6 | Add `OpenRouter` enum entry |
| 9 | `settings.gradle.kts` | 1 | Add `include("TPipe-OpenRouter")` |
| 10 | `TPipe-OpenRouter/src/test/kotlin/openrouterPipe/OpenRouterPipeTest.kt` | 3+ | Unit tests |

---

## 6. Validation Checklist

### Build Validation
- [ ] `./gradlew :TPipe-OpenRouter:build` completes without errors
- [ ] `./gradlew :TPipe-OpenRouter:test` runs and passes
- [ ] Module compiles with Java 24 and Kotlin 2.2.20

### Functional Validation
- [ ] `OpenRouterPipe().setApiKey("sk-...").setModel("anthropic/claude-3-5-sonnet-20241022").init()` succeeds
- [ ] `execute(MultimodalContent("Hello"))` returns a non-empty string response
- [ ] Streaming callback fires for each chunk when streaming is enabled
- [ ] Invalid API key returns a meaningful error via `P2PRejection`
- [ ] `truncateModuleContext()` correctly adjusts context for the selected model

### Integration Validation
- [ ] `OpenRouterDefaults.createOpenRouterPipe(config)` produces a configured pipe
- [ ] `OpenRouterDefaults.createManifold(config)` creates a working manifold
- [ ] Provider name appears correctly in `P2PDescriptor` when registered

### Error Handling
- [ ] 401 error produces `P2PRejection` with `P2PError.auth`
- [ ] 422 error produces `P2PRejection` with appropriate error type
- [ ] Network timeout produces `P2PRejection` with `P2PError.transport`
- [ ] Invalid model ID produces a meaningful error message

---

## 7. Risks and Mitigations

### Risk 1: OpenRouter API Changes

**Severity**: Medium
**Likelihood**: Low (API is stable, OpenAI-compatible)
**Impact**: Breaking changes to request/response schema could break the pipe

**Mitigation**:
- Pin to a specific API version by including the version in the base URL (`/v1/`)
- Version endpoint structure follows OpenAI's pattern, which is widely stable
- Write deserialization to be resilient to additional unknown fields

### Risk 2: Streaming SSE Parsing Complexity

**Severity**: Medium
**Likelihood**: Medium
**Impact**: Malformed SSE chunks could crash streaming or produce garbled output

**Mitigation**:
- Use Ktor's built-in SSE handling where possible
- Write a dedicated `SseParser` utility class with unit tests
- Implement graceful fallback: if streaming fails mid-stream, attempt non-streaming retry

### Risk 3: Model-Specific Tokenization

**Severity**: Low
**Likelihood**: Medium
**Impact**: Incorrect token counting leads to context overflow or wasted tokens

**Mitigation**:
- OpenRouter model IDs carry provider-specific tokenization
- Default to conservative estimation (1 token ≈ 4 characters)
- Allow model-specific truncation overrides via `setModel()` triggering `updateTruncationStrategy()`
- Leverage existing `TokenBudgetSettings` from the base Pipe class

### Risk 4: API Key Exposure

**Severity**: High
**Likelihood**: Low (team awareness)
**Impact**: Exposed API keys lead to unauthorized usage and cost overruns

**Mitigation**:
- Never serialize the API key to disk or logs
- Use `@Transient` on the `apiKey` field so it's excluded from serialization
- Support environment variable injection: `System.getenv("OPENROUTER_API_KEY")`
- Add `.gitignore` entry for any local config files that might store keys

### Risk 5: New Provider Addition Disrupts Existing Modules

**Severity**: Low
**Likelihood**: Low
**Impact**: Adding `OpenRouter` to `ProviderName` enum and new module could break existing builds

**Mitigation**:
- Follow the exact existing pattern for Ollama/Bedrock — no structural deviations
- Run the full test suite (`./gradlew test`) before considering the work complete
- Keep the new module completely decoupled — no circular dependencies

---

## Appendix A: OpenRouter vs. Direct Provider Comparison

| Aspect | OpenRouter | Direct Provider (e.g., Bedrock) |
|--------|-------------|----------------------------------|
| **Models** | 300+ aggregated | 1-10 per provider |
| **API Key** | Single key for all models | Per-provider keys |
| **SDK Required** | No (raw HTTP or Ktor) | Yes (AWS SDK) |
| **Authentication** | Bearer token | Signature/V4 signing |
| **Streaming** | SSE | Provider-specific |
| **Cost** | Pay per model via OpenRouter | Provider direct pricing |
| **Fallback** | Automatic retries | Manual implementation |
| **Latency** | Extra hop (routing) | Direct to provider |

---

## Appendix B: Reference Implementation Comparison

### OllamaPipe (simplest reference)

```kotlin
// TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt
class OllamaPipe : Pipe() {
    private var ip: String = "127.0.0.1"
    private var port: Int = 11434

    override suspend fun init(): Pipe {
        provider = ProviderName.Ollama
        Endpoints.init(this.ip, this.port)
        return this
    }

    override suspend fun generateText(promptInjector: String): String {
        val content = MultimodalContent(text = promptInjector)
        val result = execute(content)
        return result.text
    }
}
```

**Key difference for OpenRouterPipe**: OpenRouter requires authentication (API key header), supports many more models, and uses a public internet API rather than a local server. The Ktor HTTP client will be shared with BedrockPipe's approach.

### BedrockPipe (HTTP client reference)

```kotlin
// TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
override suspend fun init(): Pipe {
    super.init()
    bedrockClient = BedrockRuntimeClient {
        region = this@BedrockPipe.region
        httpClient(OkHttpEngine) { ... }
    }
    return this
}

override suspend fun generateText(promptInjector: String): String {
    val client = bedrockClient ?: return ""
    val result = executeBedrockApi(client, getRequestedModelId(), fullPrompt)
    return result.text
}
```

**Key pattern for OpenRouterPipe**: Instead of AWS SDK, use Ktor `HttpClient` with JSON serialization for OpenAI-compatible requests.

---

*This document serves as the steering reference for TPipe-OpenRouter implementation.
For questions or updates, maintain this file as the source of truth for design decisions.*