# TPipe-OpenRouter Integration: Full Overview

**Project**: Adding OpenRouter as a TPipe provider module
**Status**: Research complete, implementation not started
**Last Updated**: 2026-04-11

---

## 1. Executive Summary

TPipe is adding OpenRouter as a provider module, enabling single-API-key access to 300+ LLM models across OpenAI, Anthropic, Google, Meta, DeepSeek, Mistral, and others. The integration leverages OpenRouter's OpenAI-compatible `/v1/chat/completions` endpoint, using the same Ktor HTTP client pattern as TPipe-Bedrock. A research-driven implementation steering document exists; actual code implementation has not yet begun.

---

## 2. What Is OpenRouter?

OpenRouter is an **LLM aggregation layer** that provides a unified API across hundreds of models from different providers. Instead of managing separate API keys and endpoint configurations for each provider, users access everything through OpenRouter with a single API key.

**Key characteristics:**
- **300+ models** from OpenAI, Anthropic, Google, Meta, DeepSeek, Mistral, and many others
- **OpenAI-compatible endpoint** — `POST https://openrouter.ai/api/v1/chat/completions`
- **Automatic fallback** — OpenRouter retries failed requests with alternative providers
- **Per-request model selection** — switch models without changing client configuration
- **SSE streaming** — Server-Sent Events matching OpenAI's streaming format
- **Bearer token authentication** — simple API key in Authorization header

**Comparison to existing TPipe providers:**

| Aspect | OpenRouter | Ollama | Bedrock |
|--------|-----------|--------|---------|
| Models | 300+ aggregated | 1 (local) | ~10 AWS models |
| API Key | Single key for all | None (local) | AWS credentials |
| SDK Required | No (raw HTTP) | No (raw HTTP) | Yes (AWS SDK) |
| Authentication | Bearer token | None | AWS Signature V4 |
| Streaming | SSE | SSE | Provider-specific |
| Latency | Extra routing hop | Fast (local) | Direct |

---

## 3. Current State

### What Exists

- **Implementation Steering Document** — `md/openrouter-implementation-steering.md`
  - OpenRouter API research (endpoint, auth, request/response schemas, streaming)
  - TPipe provider module pattern analysis (based on OllamaPipe and BedrockPipe)
  - `OpenRouterPipe` class design with code examples
  - 6-phase implementation roadmap with file inventory
  - Validation checklist and risk/mitigation table

### What Needs to Be Built

The `TPipe-OpenRouter` module is not yet created. All implementation phases remain:

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Module skeleton and build configuration | Not Started |
| 2 | Request/response models (env/) | Not Started |
| 3 | OpenRouterPipe core implementation | Not Started |
| 4 | Streaming support | Not Started |
| 5 | TPipe-Defaults integration | Not Started |
| 6 | ProviderName enum update | Not Started |

---

## 4. Completed Work

### Research Phase (Done)

The implementation steering document (`md/openrouter-implementation-steering.md`) was created through:
1. Web search and documentation fetching for OpenRouter API
2. Codebase analysis of OllamaPipe (simple HTTP pattern) and BedrockPipe (Ktor HTTP pattern)
3. Identification of TPipe provider module conventions and integration points

### Key Findings from Research

**OpenRouter API:**
- Base URL: `https://openrouter.ai/api/v1`
- Auth: `Authorization: Bearer <OPENROUTER_API_KEY>`
- Endpoint: `POST /chat/completions` (OpenAI-compatible)
- Streaming: SSE format identical to OpenAI
- Models: identified by OpenRouter IDs (e.g., `anthropic/claude-3-5-sonnet-20241022`)

**TPipe Module Pattern:**
- Module directory: `TPipe-{ProviderName}/`
- Build file: `build.gradle.kts` with Kotlin JVM plugin, Ktor dependencies
- Main class: `{ProviderName}Pipe : Pipe()` implementing `init()`, `generateText()`, `truncateModuleContext()`
- Env models: `@Serializable` data classes for request/response
- Settings update: `include("TPipe-{ProviderName}")` in `settings.gradle.kts`
- Enum update: add to `ProviderName` in root module

---

## 5. Remaining Work (16 Milestones Across 4 Tiers)

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
| M2.2 | Error handling and HTTP status code mapping | Not Started |
| M2.3 | P2P interface compliance | Not Started |
| M2.4 | Integration tests (real API) | Not Started |

**Tier 2 Goal**: Match the feature set of OllamaPipe and BedrockPipe — streaming, error handling, P2P integration.

### Tier 3: Polish and TPipe-Defaults Integration

| Milestone | Description | Status |
|-----------|-------------|--------|
| M3.1 | ProviderName enum update | Not Started |
| M3.2 | OpenRouterConfiguration (TPipe-Defaults) | Not Started |
| M3.3 | OpenRouterDefaults factory | Not Started |
| M3.4 | ManifoldDefaults integration | Not Started |

**Tier 3 Goal**: Integrate with TPipe-Defaults so users can create OpenRouter-backed Manifolds via factory.

### Tier 4: Documentation, Examples, and Ecosystem

| Milestone | Description | Status |
|-----------|-------------|--------|
| M4.1 | OpenRouterPipe examples | Not Started |
| M4.2 | TPipe-Defaults integration examples | Not Started |
| M4.3 | SSE Parser utility (risk validation) | Not Started |
| M4.4 | Security review — API key handling | Not Started |
| M4.5 | Model-specific truncation strategy validation | Not Started |
| M4.6 | Full test suite validation | Not Started |

**Tier 4 Goal**: Usable, maintainable, documented module with working examples.

---

### Milestone Dependency Map

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

### Key Files per Milestone

| Milestone | Key Files |
|-----------|-----------|
| M1.1 | `TPipe-OpenRouter/build.gradle.kts`, `settings.gradle.kts` |
| M1.2 | `TPipe-OpenRouter/src/main/kotlin/env/Endpoints.kt`, `OpenRouterOptions.kt`, `ChatResponse.kt` |
| M1.3 | `TPipe-OpenRouter/src/main/kotlin/openrouterPipe/OpenRouterPipe.kt` |
| M1.4 | `TPipe-OpenRouter/src/test/kotlin/openrouterPipe/OpenRouterPipeTest.kt` |
| M2.1 | `TPipe-OpenRouter/src/main/kotlin/env/SseParser.kt` |
| M3.1 | `src/main/kotlin/Enums/ProviderName.kt` |
| M3.2 | `TPipe-Defaults/.../OpenRouterConfiguration.kt` |
| M3.3 | `TPipe-Defaults/.../OpenRouterDefaults.kt` |
| M3.4 | `TPipe-Defaults/.../ManifoldDefaults.kt` |

---

## 6. Architecture Overview

### How OpenRouterPipe Fits Into TPipe

```
TPipe (root module)
├── Pipe (abstract base class)
│   ├── init(), generateText(), truncateModuleContext()
│   ├── P2PInterface — distributed agent communication
│   └── ProviderInterface — cleanPromptText() hook
│
├── TPipe-Ollama/
│   └── OllamaPipe : Pipe  (raw HTTP, no auth, local)
│
├── TPipe-Bedrock/
│   └── BedrockPipe : Pipe  (AWS SDK, Signature V4 auth)
│
└── TPipe-OpenRouter/           ← NEW
    └── OpenRouterPipe : Pipe   (Ktor HTTP, Bearer token auth)
```

### HTTP Client Choice

Both BedrockPipe and OpenRouterPipe use **Ktor** as the HTTP client:
- `ktor-client-core` — core HTTP abstractions
- `ktor-client-cio` — CIO engine for JVM
- `ktor-client-content-negotiation` + `ktor-serialization-kotlinx-json` — JSON serialization

OllamaPipe uses raw `HttpURLConnection` instead, but for OpenRouter the Ktor pattern from BedrockPipe is more appropriate due to authentication complexity and JSON handling needs.

---

## 7. Module Structure

```
TPipe-OpenRouter/
├── build.gradle.kts                    # Ktor dependencies, Java 24 toolchain
└── src/
    ├── main/kotlin/
    │   ├── env/
    │   │   ├── Endpoints.kt            # BASE_URL = "https://openrouter.ai/api/v1"
    │   │   ├── OpenRouterOptions.kt    # OpenRouterChatRequest, ChatMessage, tools
    │   │   └── ChatResponse.kt         # OpenRouterChatResponse, ChatChoice, UsageInfo
    │   └── openrouterPipe/
    │       └── OpenRouterPipe.kt       # Main class: setApiKey(), init(), generateText()
    └── test/kotlin/
        └── openrouterPipe/
            └── OpenRouterPipeTest.kt    # Unit tests
```

**Root TPipe changes:**
- `settings.gradle.kts` — add `include("TPipe-OpenRouter")`
- `src/main/kotlin/Enums/ProviderName.kt` — add `OpenRouter`

**TPipe-Defaults changes:**
- `Defaults/providers/OpenRouterConfiguration.kt` — config data class
- `Defaults/providers/OpenRouterDefaults.kt` — factory object

---

## 8. Integration Points

### With Root TPipe
- `ProviderName.OpenRouter` enum entry — used in `pipe.provider` field
- `Pipe.init()` propagation — `super.init()` cascades to child pipes
- `P2PDescriptor` and `P2PRequirements` — used if module is registered with P2PRegistry

### With TPipe-Defaults
- `OpenRouterDefaults.createOpenRouterPipe(config)` — factory method matching `OllamaDefaults`, `BedrockDefaults` pattern
- `OpenRouterConfiguration` — sealed to `ProviderConfiguration` interface with `validate()`

### With Existing Providers
- Same builder pattern as OllamaPipe (`setModel()`, `setApiKey()`, etc.)
- Same Ktor HTTP pattern as BedrockPipe (separate from Ollama's `HttpURLConnection`)
- Streaming via `StreamingCallbackManager` same as all providers

---

## 9. Validation & Testing

### Build Validation
```bash
./gradlew :TPipe-OpenRouter:build      # Compiles without errors
./gradlew :TPipe-OpenRouter:test      # Tests pass
```

### Functional Validation
1. **Initialization**: `OpenRouterPipe().setApiKey("sk-...").setModel("...").init()` succeeds
2. **Non-streaming**: `execute(MultimodalContent("Hello"))` returns string response
3. **Streaming**: callback receives chunks when streaming enabled
4. **Error handling**: 401 → `P2PError.auth`, 422 → appropriate error type, timeout → `P2PError.transport`

### Integration Validation
1. `OpenRouterDefaults.createOpenRouterPipe(config)` produces configured pipe
2. `OpenRouterDefaults.createManifold(config)` creates working manifold
3. `ProviderName.OpenRouter` appears in `P2PDescriptor` when registered

### Security Validation
- `apiKey` field is `@Transient` — not serialized to disk
- API key not logged in `toString()` output
- Environment variable support: `System.getenv("OPENROUTER_API_KEY")`

---

## 10. Risks & Open Questions

### Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| OpenRouter API changes | Medium | Pin to `/v1/` versioned endpoint, write resilient deserialization |
| SSE streaming parsing bugs | Medium | Dedicated `SseParser` class with unit tests, fallback to non-streaming |
| Model-specific tokenization | Low | Conservative 1-token-per-4-chars default, allow per-model overrides |
| API key exposure | High | `@Transient` on field, env var support, no logging of key value |
| Module breaks existing build | Low | Follow existing Ollama/Bedrock patterns exactly, run full test suite |

### Open Questions

1. **Model-specific truncation**: OpenRouter aggregates many model families with different tokenizers. Should `truncateModuleContext()` attempt model-specific truncation based on the model ID prefix (e.g., `anthropic/*`, `openai/*`), or default to conservative estimation?

2. **Provider preferences**: OpenRouter supports routing preferences (`provider: { prefer_suffix: "..." }`). Should TPipe-OpenRouter expose this as a configuration option in `OpenRouterConfiguration`?

3. **Tools/function calling**: The implementation steering doc includes `tools` and `tool_choice` in the request model. Should Phase 3 include tool support, or defer to a later phase?

4. **Test API key**: Should the test suite include integration tests with a real OpenRouter API key (guarded by an environment variable), or only unit tests with mocked responses?

---

## 11. Appendix: Reference Materials

### OpenRouter Documentation
- [Quickstart Guide](https://openrouter.ai/docs/quickstart) — Base URL, auth, first request
- [Chat Completions API](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request) — Request/response schema
- [Models API](https://openrouter.ai/docs/api/api-reference/models/get-models) — Model discovery
- [Authentication](https://openrouter.ai/docs/api/reference/authentication) — API key usage

### TPipe Provider Patterns
- `TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt` — Simple raw-HTTP pattern
- `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt` — Ktor HTTP client pattern
- `TPipe-Defaults/src/main/kotlin/Defaults/providers/` — Provider configuration pattern
- `src/main/kotlin/Enums/ProviderName.kt` — Provider enum entries

### Implementation Steering Document
- `md/openrouter-implementation-steering.md` — Full technical details, code examples, risk analysis

---

*This document provides the full project overview for TPipe-OpenRouter integration.
For specific implementation details, see `md/openrouter-implementation-steering.md`.*