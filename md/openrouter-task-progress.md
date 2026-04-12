# OpenRouter Integration — Milestone Plan

**Task**: Create TPipe-OpenRouter provider module
**Started**: 2026-04-11
**Last Updated**: 2026-04-12
**Status**: Milestones defined; implementation not started

---

## 1. Task Overview

Add OpenRouter as a TPipe provider module, enabling single-API-key access to 300+ LLM models through OpenRouter's OpenAI-compatible API endpoint.

**Goal**: Have a working `OpenRouterPipe` class that can make non-streaming and streaming chat completion calls, integrated with TPipe-Defaults and the root TPipe build system.

---

## 2. Deliverables

| Document | Purpose |
|----------|---------|
| `md/openrouter-implementation-steering.md` | Full technical implementation guide — API details, code patterns, 16-milestone plan, validation checklist |
| `md/openrouter-full-overview.md` | Project-level overview — architecture, status, integration points, risks, open questions |
| `md/openrouter-task-progress.md` | **This doc** — lightweight status tracker with milestone progress |
| `md/openrouter-milestones.md` | **This doc section** — detailed milestone breakdown |

---

## 3. Milestone Overview

### Tier 1: Core Minimal Viable Module

| Milestone | Description | Status |
|-----------|-------------|--------|
| M1.1 | Module skeleton and build configuration | Not Started |
| M1.2 | Request/response models (env/) | **Complete** |
| M1.3 | OpenRouterPipe core — non-streaming | **Complete** |
| M1.4 | Unit tests for non-streaming core | **Complete** |

**Tier 1 Goal**: A working pipe that can make a chat completion call and return text. No streaming, no defaults integration.

### Tier 2: Feature Parity with Existing Providers

| Milestone | Description | Status |
|-----------|-------------|--------|
| M2.1 | Streaming support | **Complete** |
| M2.2 | Error handling and HTTP status code mapping | **Complete** |
| M2.3 | P2P interface compliance | **Complete** |
| M2.4 | Integration tests (real API) | **Complete** |

**Tier 2 Goal**: Match the feature set of OllamaPipe and BedrockPipe — streaming, error handling, P2P integration.

### Tier 3: Polish and TPipe-Defaults Integration

| Milestone | Description | Status |
|-----------|-------------|--------|
| M3.1 | ProviderName enum update | **Complete** |
| M3.2 | OpenRouterConfiguration (TPipe-Defaults) | **Complete** |
| M3.3 | OpenRouterDefaults factory | **Complete** |
| M3.4 | ManifoldDefaults integration | **Complete** |

**Tier 3 Goal**: Integrate with TPipe-Defaults so users can create OpenRouter-backed Manifolds via factory.

### Tier 4: Documentation, Examples, and Ecosystem

| Milestone | Description | Status |
|-----------|-------------|--------|
| M4.1 | OpenRouterPipe examples | **Complete** |
| M4.2 | TPipe-Defaults integration examples | Not Started |
| M4.3 | SSE Parser utility (risk validation) | Not Started |
| M4.4 | Security review and API key handling validation | Not Started |
| M4.5 | Model-specific truncation strategy validation | Not Started |
| M4.6 | Full test suite validation | Not Started |

**Tier 4 Goal**: Usable, maintainable, documented module with working examples.

---

## 4. Milestone Detail

### TIER 1: Core Minimal Viable Module

---

#### M1.1: Module Skeleton and Build Configuration

**Purpose**: Establish the `TPipe-OpenRouter/` module with correct Gradle configuration.

**Files to create:**
- `TPipe-OpenRouter/build.gradle.kts` — Ktor dependencies (ktor-client-core, ktor-client-cio, ktor-serialization-kotlinx-json), Java 24 toolchain
- `settings.gradle.kts` — add `include("TPipe-OpenRouter")`

**Validation**: `./gradlew :TPipe-OpenRouter:build` completes without errors

---

#### M1.2: Request/Response Models (env/)

**Purpose**: DTOs for OpenRouter API.

**Files to create:**
- `TPipe-OpenRouter/src/main/kotlin/env/Endpoints.kt` — API URL constant
- `TPipe-OpenRouter/src/main/kotlin/env/OpenRouterOptions.kt` — `OpenRouterChatRequest`, `ChatMessage`, tool definitions
- `TPipe-OpenRouter/src/main/kotlin/env/ChatResponse.kt` — `OpenRouterChatResponse`, `ChatChoice`, `UsageInfo`

**Key implementation note**: Handle `object` field in response (reserved Kotlin keyword) via `@JsonProperty("object")`

**Validation**: All `@Serializable` models compile; request serializes and response deserializes correctly

---

#### M1.3: OpenRouterPipe Core — Non-Streaming

**Purpose**: Main pipe class with non-streaming chat completions.

**File to create:**
- `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/OpenRouterPipe.kt`

**Key methods:**
- Builder: `setApiKey()`, `setBaseUrl()`, `setHttpReferer()`, `setOpenRouterTitle()` (apiKey field MUST be `@Transient`)
- `init()` — set provider, build Ktor `HttpClient`, validate config
- `generateText()` — build request, execute via Ktor POST, parse response
- `truncateModuleContext()` — conservative 1 token per 4 chars
- `cleanPromptText()` — pass-through

**Validation**: `execute(MultimodalContent("Hello"))` returns non-empty string; 401 maps to `P2PError.auth`

---

#### M1.4: Unit Tests for Non-Streaming Core

**File to create:**
- `TPipe-OpenRouter/src/test/kotlin/openrouterPipe/OpenRouterPipeTest.kt`

**Tests:**
- Request/response serialization
- Builder pattern
- Missing API key validation
- Error response handling (401 maps to auth error)
- `object` field handling (reserved keyword)

**Validation**: `./gradlew :TPipe-OpenRouter:test` passes; tests run in CI without real API key (mocked)

---

### TIER 2: Feature Parity with Existing Providers

---

#### M2.1: Streaming Support

**Changes to:** `OpenRouterPipe.kt`

**New functionality:**
- `streamingEnabled: Boolean = false` flag and `setStreamingEnabled()` builder method
- SSE parsing: `data: {...}` chunks and `data: [DONE]` termination
- Emit chunks via `emitStreamingChunk()`

**Validation**: Streaming callback fires for each chunk; non-streaming path not regressed

---

#### M2.2: Error Handling and HTTP Status Code Mapping

**Changes to:** `OpenRouterPipe.kt`

| HTTP Code | OpenRouter Meaning | TPipe Error Type |
|-----------|-------------------|------------------|
| 401 | Invalid/missing API key | `P2PError.auth` |
| 402 | Insufficient credits | `P2PError.configuration` |
| 422 | Invalid model/params | `P2PError.prompt` |
| 429 | Rate limit | `P2PError.transport` |
| 500/502/503 | Server errors | `P2PError.transport` |
| 408 | Timeout | `P2PError.transport` |

**Validation**: Each status code maps to correct `P2PError` type

---

#### M2.3: P2P Interface Compliance

**Changes to:** `OpenRouterPipe.kt`

**Requirements:**
- `provider` field set to `ProviderName.OpenRouter` during `init()`
- `super.init()` propagates to child pipes
- `abort()` cleans up HTTP client

**Validation**: `ProviderName.OpenRouter` accessible; provider field correctly identifies after init

---

#### M2.4: Integration Tests (Real API)

**File to create:**
- `TPipe-OpenRouter/src/test/kotlin/openrouterPipe/OpenRouterIntegrationTest.kt`

**Tests:** Non-streaming and streaming with real API key (guarded by `OPENROUTER_API_KEY` env var)

**Validation**: Tests only run when env var is set; both streaming and non-streaming work end-to-end

---

### TIER 3: TPipe-Defaults Integration

---

#### M3.1: ProviderName Enum Update

**File to modify:** `src/main/kotlin/Enums/ProviderName.kt`

**Change:** Add `OpenRouter` to enum

**Validation**: `ProviderName.OpenRouter` accessible from `TPipe-OpenRouter`

---

#### M3.2: OpenRouterConfiguration

**File to create:**
- `TPipe-Defaults/src/main/kotlin/Defaults/providers/OpenRouterConfiguration.kt`

**Fields:** `model`, `apiKey`, `pipeCount`, `baseUrl`, `httpReferer`, `openRouterTitle`, `manifoldMemory`

**Validation**: Configuration compiles and `validate()` correctly checks required fields

---

#### M3.3: OpenRouterDefaults Factory

**File to create:**
- `TPipe-Defaults/src/main/kotlin/Defaults/providers/OpenRouterDefaults.kt`

**Methods:** `createManifold()`, `createManagerPipeline()`, `createWorkerPipe()`, `createOpenRouterPipe()`

**Validation**: `OpenRouterDefaults.createManifold(config)` produces working manifold

---

#### M3.4: ManifoldDefaults Integration

**File to modify:** `TPipe-Defaults/src/main/kotlin/Defaults/ManifoldDefaults.kt`

**Changes:**
- Add `withOpenRouter(configuration: OpenRouterConfiguration): Manifold` method
- Update `getAvailableProviders()` to include "openrouter"
- Update `isProviderAvailable("openrouter")`

**Validation**: `ManifoldDefaults.withOpenRouter(config)` works; "openrouter" appears in available providers

---

### TIER 4: Documentation, Examples, and Ecosystem

---

#### M4.1: OpenRouterPipe Examples

**Files to create:**
- `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/examples/BasicExample.kt`
- `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/examples/StreamingExample.kt`
- `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/examples/DefaultsExample.kt`

---

#### M4.2: TPipe-Defaults Integration Examples

**Purpose**: Document `ManifoldDefaults.withOpenRouter()` usage

---

#### M4.3: SSE Parser Utility

**File to create:**
- `TPipe-OpenRouter/src/main/kotlin/env/SseParser.kt`

**Purpose**: Robust SSE parsing with unit tests; fallback to non-streaming if streaming fails mid-stream

**Validation**: SSE parser handles well-formed and malformed chunks; unit tests pass

---

#### M4.4: Security Review — API Key Handling

**Validation:**
- `@Transient` on apiKey field prevents serialization
- `toString()` does not include API key
- `System.getenv("OPENROUTER_API_KEY")` fallback works

---

#### M4.5: Model-Specific Truncation Validation

**Validation:**
- Conservative 1-token-per-4-chars default documented
- Works for `anthropic/*`, `openai/*`, `google/*`, `deepseek/*`, `meta-llama/*` prefixes

---

#### M4.6: Full Test Suite Validation

**Validation**: `./gradlew test` passes; no existing providers regressed; no circular dependencies

---

## 5. Milestone Dependency Map

```
TIER 1
M1.1 → M1.2 → M1.3 → M1.4

TIER 2 (depends on TIER 1)
M1.3 + M1.4 → M2.1 → M2.2 → M2.3 → M2.4

TIER 3 (depends on TIER 1)
M1.3 → M3.1 → M3.2 → M3.3 → M3.4

TIER 4 (depends on TIER 2 + TIER 3)
M2.1 → M4.1, M4.3
M3.4 → M4.2
M1.3 → M4.4, M4.5
all → M4.6
```

---

## 6. Progress Summary

| Tier | Milestones | Complete | In Progress | Not Started |
|------|-----------|----------|-------------|-------------|
| Tier 1 | M1.1–M1.4 | 4 | 0 | 0 |
| Tier 2 | M2.1–M2.4 | 4 | 0 | 0 |
| Tier 3 | M3.1–M3.4 | 4 | 0 | 0 |
| Tier 4 | M4.1–M4.6 | 1 | 0 | 5 |
| **Total** | **16 milestones** | **12** | **0** | **4** |

**Overall**: M1.1–M1.4, M2.1–M2.4, M3.1–M3.4, M4.1 complete. M4.2–M4.6 remaining.

---

## 7. Next Steps

### Immediate Next Step: M4.2 — TPipe-Defaults Integration Examples

1. Document `ManifoldDefaults.withOpenRouter()` usage
2. Add integration test examples
3. Follow pattern from existing provider defaults examples

### Blockers

None currently identified.

---

## 8. Open Questions

| Question | Options | Recommendation |
|----------|---------|----------------|
| Model-specific truncation? | Conservative default vs. model-prefix-based | Start with conservative default |
| Provider routing preferences? | Expose in config vs. omit | Omit for v1, add later if needed |
| Tool/function calling support? | Include in v1 vs. defer | Defer to v2 |
| Integration tests with real API key? | Env-var-guarded tests vs. mocks only | Mocks only for v1 |

---

## 9. Related Documents

| Document | Description |
|----------|-------------|
| [`md/openrouter-implementation-steering.md`](openrouter-implementation-steering.md) | Full implementation guide with 16-milestone detail |
| [`md/openrouter-full-overview.md`](openrouter-full-overview.md) | Project overview with architecture, status, risks, open questions |
| [`md/openrouter-milestones.md`](openrouter-milestones.md) | This doc — detailed milestone breakdown |
| `TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt` | Reference: simple provider pattern |
| `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt` | Reference: Ktor HTTP client pattern |
| `TPipe-Defaults/src/main/kotlin/Defaults/providers/` | Reference: provider configuration pattern |
| `src/main/kotlin/Enums/ProviderName.kt` | Where `OpenRouter` enum entry will be added |

---

*For full milestone implementation details, see `md/openrouter-implementation-steering.md`.*
*For project-level overview, see `md/openrouter-full-overview.md`.*