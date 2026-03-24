# TPipe-Defaults Package API

## Table of Contents
- [Overview](#overview)
- [Configuration Classes](#configuration-classes)
  - [ProviderConfiguration](#providerconfiguration)
  - [BedrockConfiguration](#bedrockconfiguration)
  - [OllamaConfiguration](#ollamaconfiguration)
- [Factory Classes](#factory-classes)
  - [ManifoldDefaults](#manifolddefaults)
  - [BedrockDefaults](#bedrockdefaults)
  - [OllamaDefaults](#ollamadefaults)
- [Reasoning System](#reasoning-system)
  - [ReasoningBuilder](#reasoningbuilder)
  - [ReasoningPrompts](#reasoningprompts)
  - [Enums](#enums)
- [Manifold DSL Integration](#manifold-dsl-integration)

## Overview

The TPipe-Defaults package provides pre-configured factories and builders for creating Manifold instances, pipelines, and reasoning systems with provider-specific optimizations for AWS Bedrock, Ollama, and other LLM providers.

---

## Configuration Classes

### ProviderConfiguration

Abstract base class for provider-specific configuration parameters.

```kotlin
sealed class ProviderConfiguration
```

#### Public Functions

**`validate(): Boolean`**
Validates configuration parameters for the provider.

**Behavior:** Abstract method implemented by each provider to ensure required parameters are present and valid.

---

### BedrockConfiguration

Configuration for AWS Bedrock provider with comprehensive AWS integration.

```kotlin
data class BedrockConfiguration(
    var region: String,
    var model: String,
    var pipeCount: Int = 2,
    var inferenceProfile: String = "",
    var useConverseApi: Boolean = true,
    var accessKey: String? = null,
    var secretKey: String? = null,
    var sessionToken: String? = null,
    var profileName: String? = null
) : ProviderConfiguration()
```

#### Public Properties

**Core Settings:**
- **`region`**: AWS region for Bedrock API calls (required)
- **`model`**: Bedrock model identifier (required)
- **`pipeCount`**: Number of pipes to create in manager pipeline (default: 2)

**Advanced Settings:**
- **`inferenceProfile`**: Optional inference profile for binding calls
- **`useConverseApi`**: Whether to use Converse API vs Invoke API (default: true)

**Authentication:**
- `accessKey` / `secretKey` - AWS credentials (optional if using profile/IAM)
- **`sessionToken`**: AWS session token for temporary credentials
- **`profileName`**: AWS profile name to use

#### Public Functions

**`validate(): Boolean`**
Validates Bedrock configuration parameters.

**Behavior:** Ensures region and model are non-blank and pipeCount is positive.

**`make(region: String, model: String)`**
Configures Bedrock settings with automatic inference profile detection.

**Behavior:** Sets region and model, automatically retrieves inference profile ID if available.

---

### OllamaConfiguration

Configuration for Ollama provider with local server settings.

```kotlin
data class OllamaConfiguration(
    val model: String,
    val pipeCount: Int = 2,
    val host: String = "localhost",
    val port: Int = 11434,
    val timeout: Long = 30000,
    val useHttps: Boolean = false
) : ProviderConfiguration()
```

#### Public Properties

**Core Settings:**
- **`model`**: Model name to use (required)
- **`pipeCount`**: Number of pipes in manager pipeline (default: 2)

**Connection Settings:**
- **`host`**: Ollama server host (default: "localhost")
- **`port`**: Ollama server port (default: 11434)
- **`timeout`**: Connection timeout in milliseconds (default: 30000)
- **`useHttps`**: Whether to use HTTPS (default: false)

#### Public Functions

**`validate(): Boolean`**
Validates Ollama configuration parameters.

**Behavior:** Ensures host and model are non-blank, port and pipeCount are positive.

---

## Factory Classes

### ManifoldDefaults

Central factory for creating pre-configured Manifold instances with provider-specific defaults.

For the actual startup sequence after creating a manifold, see [Manifold - Multi-Agent Orchestration](../containers/manifold.md). `ManifoldDefaults` creates the manager side, but you still need worker pipelines and `init()` before execution.

If you want `TPipe-Defaults` to configure the manager and manager-memory policy inside the new Kotlin manifold DSL, use `manifold { defaults { bedrock(...) } }` or `manifold { defaults { ollama(...) } }`.

```kotlin
object ManifoldDefaults
```

#### Public Functions

**`withBedrock(configuration: BedrockConfiguration): Manifold`**
Creates Manifold instance configured for AWS Bedrock.

**Behavior:**
- Validates configuration parameters
- Creates Bedrock-optimized Manifold with manager pipeline
- Throws IllegalArgumentException for invalid configuration
- Throws RuntimeException if Bedrock provider unavailable

**`withOllama(configuration: OllamaConfiguration): Manifold`**
Creates Manifold instance configured for Ollama.

**Behavior:**
- Validates configuration parameters
- Creates Ollama-optimized Manifold with manager pipeline
- Throws IllegalArgumentException for invalid configuration
- Throws RuntimeException if Ollama provider unavailable

**`getAvailableProviders(): List<String>`**
Lists all available providers with implementations.

**Behavior:** Checks class availability and returns list of provider names ("bedrock", "ollama").

**`isProviderAvailable(providerName: String): Boolean`**
Checks if specific provider is available.

**Behavior:** Uses reflection to check for provider class availability, returns false for ClassNotFoundException.

**`buildDefaultManagerPipeline(bedrockConfig: BedrockConfiguration): Pipeline`**
Creates pre-configured manager pipeline for Bedrock.

**Behavior:**
- Creates 2-pipe manager pipeline with task analysis and agent selection
- Applies comprehensive default prompts and settings
- Configures JSON input/output for TaskProgress and AgentRequest

**`buildDefaultManagerPipeline(ollamaConfig: OllamaConfiguration): Pipeline`**
Creates pre-configured manager pipeline for Ollama.

**`assignManagerPipelineDefaults(pipeline: Pipeline): Pipeline`**
Applies default configuration to manager pipelines.

**Behavior:**
- **Entry Pipe**: Analyzes task completion status, outputs TaskProgress
- **Agent Selector Pipe**: Determines next agent to call, outputs AgentRequest
- **Configuration**: Sets temperature, context windows, truncation, prompts
- **Validation**: Requires exactly 2 pipes in pipeline

---

### BedrockDefaults

Internal factory for Bedrock-specific Manifold creation.

```kotlin
internal object BedrockDefaults
```

#### Public Functions

**`createManifold(config: BedrockConfiguration): Manifold`**
Creates basic Bedrock-configured Manifold.

**`createManagerPipeline(config: BedrockConfiguration): Pipeline`**
Creates manager pipeline with specified number of Bedrock pipes.

**`createWorkerPipe(config: BedrockConfiguration): BedrockMultimodalPipe`**
Creates worker pipe with Bedrock configuration.

**`createBedrockPipe(config: BedrockConfiguration): BedrockMultimodalPipe`**
Creates fully configured BedrockPipe.

**Behavior:**
- Sets model (uses inference profile if provided)
- Configures AWS region
- Enables Converse API if specified

---

### OllamaDefaults

Internal factory for Ollama-specific Manifold creation.

```kotlin
internal object OllamaDefaults
```

#### Public Functions

**`createManifold(config: OllamaConfiguration): Manifold`**
Creates basic Ollama-configured Manifold.

**`createManagerPipeline(config: OllamaConfiguration): Pipeline`**
Creates manager pipeline with specified number of Ollama pipes.

**`createWorkerPipe(config: OllamaConfiguration): OllamaPipe`**
Creates worker pipe with Ollama configuration.

**`createOllamaPipe(config: OllamaConfiguration): OllamaPipe`**
Creates fully configured OllamaPipe.

**Behavior:**
- Sets model name
- Configures host IP and port
- Sets TaskProgress as JSON input

---

## Reasoning System

### ReasoningBuilder

Factory for creating reasoning-enabled pipes with various thinking strategies.

```kotlin
object ReasoningBuilder
```

#### Public Functions

**`assignDefaults(settings: ReasoningSettings, pipeSettings: PipeSettings, targetPipe: Pipe)`**
Applies reasoning configuration to existing pipe.

**Behavior:**
- **System Prompt Assignment**: Sets reasoning-specific prompts based on method
- **JSON Configuration**: Configures appropriate input/output objects for reasoning type
- **Settings Application**: Applies temperature, tokens, context window settings
- **Multi-Round Support**: Uses `roundDirectives` as the canonical blind/merge configuration. Blind rounds are isolated by the harness, merge rounds synthesize the accumulated flattened thought stream, and legacy `focusPoints` remains available only when no round directives are supplied
- **Metadata Binding**: Stores reasoning configuration in pipe metadata

**`reasonWithBedrock(bedrockConfig: BedrockConfiguration, reasoningSettings: ReasoningSettings, pipeSettings: PipeSettings): Pipe`**
Creates Bedrock reasoning pipe with defaults.

**Behavior:** Creates BedrockPipe, applies reasoning defaults, returns as generic Pipe.

**`reasonWithOllama(ollamaConfig: OllamaConfiguration, reasoningSettings: ReasoningSettings, pipeSettings: PipeSettings): Pipe`**
Creates Ollama reasoning pipe with defaults.

**Behavior:** Creates OllamaPipe, applies reasoning defaults, returns as generic Pipe.

### ReasoningPrompts

Static prompts for different reasoning strategies.

```kotlin
object ReasoningPrompts
```

#### Public Functions

**`chainOfThoughtSystemPrompt(depth: String = "", duration: String = "", method: ReasoningMethod = ReasoningMethod.StructuredCot): String`**
Generates system prompts for chain-of-thought reasoning.

**Behavior:**
- **Explicit Reasoning**: Step-by-step analysis with clear transitions
- **Structured CoT**: Phase-based framework (analyze→plan→execute→validate)
- **Process-Focused**: Methodological justification and adaptive thinking

**`bestIdeaPrompt(): String`**
Prompt for single best idea generation.

**`comprehensivePlanPrompt(): String`**
Prompt for comprehensive planning approach.

**`rolePlayPrompt(character: String): String`**
Prompt for character-based reasoning.

**`semanticDecompressionPrompt(depth: ReasoningDepth = ReasoningDepth.Med, duration: ReasoningDuration = ReasoningDuration.Med): String`**
Prompt for legend-backed semantic decompression reasoning.

**`selectDepth(depth: ReasoningDepth): String`**
Returns depth-specific reasoning instructions.

**`selectDuration(duration: ReasoningDuration): String`**
Returns duration-specific reasoning instructions.

---

## Enums

### ReasoningMethod
```kotlin
enum class ReasoningMethod {
    BestIdea,           // Single best solution approach
    ComprehensivePlan,  // Detailed planning strategy
    ExplicitCot,        // Step-by-step reasoning
    StructuredCot,      // Phase-based framework
    processFocusedCot,  // Methodological focus
    RolePlay,           // Character-based reasoning
    SemanticDecompression // Legend-backed prompt reconstruction
}
```

### ReasoningInjector
```kotlin
enum class ReasoningInjector {
    SystemPrompt,                    // End of system prompt
    BeforeUserPrompt,               // Before user input
    BeforeUserPromptWithConverse,   // In ConverseHistory before user
    AfterUserPrompt,                // After user input
    AfterUserPromptWithConverse,    // In ConverseHistory after user
    AsContext                       // As context page key
}
```

### ReasoningDepth
```kotlin
enum class ReasoningDepth { Low, Med, High }
```

### ReasoningDuration
```kotlin
enum class ReasoningDuration { Short, Med, Long }
```

### ReasoningSettings

Configuration for reasoning behavior.

```kotlin
data class ReasoningSettings(
    var reasoningMethod: ReasoningMethod = ReasoningMethod.StructuredCot,
    var depth: ReasoningDepth = ReasoningDepth.Med,
    var duration: ReasoningDuration = ReasoningDuration.Med,
    var roleCharacter: String = "You are a helpful assistant.",
    var reasoningInjector: ReasoningInjector = ReasoningInjector.SystemPrompt,
    var numberOfRounds: Int = 1,
    var focusPoints: MutableMap<Int, String> = mutableMapOf(),
    var roundDirectives: MutableMap<Int, ReasoningRoundDirective> = mutableMapOf()
)
```

### ReasoningRoundDirective

Round-scoped configuration for multi-round reasoning.

```kotlin
data class ReasoningRoundDirective(
    var focusPoint: String = "",
    var mode: ReasoningRoundMode = ReasoningRoundMode.Blind
)
```

### ReasoningRoundMode

```kotlin
enum class ReasoningRoundMode {
    Blind,
    Merge
}
```

---

## Manifold DSL Integration

TPipe-Defaults provides extension functions that bridge the defaults module into the [Manifold DSL](../containers/manifold.md#dsl-builder). This lets you use `defaults { bedrock(...) }` or `defaults { ollama(...) }` inside a `manifold { }` block to configure the manager pipeline and history policy from provider defaults.

### `ManifoldDsl.defaults(block)`

Entry point for defaults-backed configuration inside the manifold DSL.

```kotlin
import com.TTT.Pipeline.manifold
import Defaults.BedrockConfiguration
import Defaults.defaults

val builtManifold = manifold {
    defaults {
        bedrock(BedrockConfiguration(
            region = "us-east-1",
            model = "anthropic.claude-3-haiku-20240307-v1:0"
        ))
    }

    worker("my-worker") {
        description("Does work.")
        pipeline(workerPipeline)
    }
}
```

**Behavior:** The `defaults` block creates a `DefaultsManifoldDsl` receiver that exposes `bedrock(...)` and `ollama(...)` methods. Each method:

1. Validates the provider configuration
2. Builds the default manager pipeline via `ManifoldDefaults.buildDefaultManagerPipeline(...)`
3. Configures the `manager { }` block with the built pipeline and the standard `"Agent caller pipe"` dispatch name
4. Configures the `history { }` block with the provider's `ManifoldMemoryConfiguration` (truncation method, context window size, token budget)

### `HistoryDsl.applyDefaults(memoryConfiguration)`

Applies a `ManifoldMemoryConfiguration` to the DSL history block. This mirrors the memory settings that `ManifoldDefaults.withBedrock(...)` or `ManifoldDefaults.withOllama(...)` would apply when using the non-DSL path.

The applied settings include:
- `managerTruncationMethod` → `defaultsTruncationMethod(...)`
- `managerContextWindowSize` → `defaultsContextWindowSize(...)`
- `enableManagerBudgetControl` → `autoTruncate()`
- `managerTokenBudget` → `managerTokenBudget(...)`

## Key Behaviors

### Provider Abstraction
Unified interface for different LLM providers while maintaining provider-specific optimizations and configurations.

### Default Configuration
Comprehensive default settings for common use cases, reducing setup complexity while maintaining flexibility for customization.

### Reasoning Integration
Sophisticated reasoning system that transforms standard pipes into thinking systems for LLMs without native reasoning support.

### Error Handling
Robust validation and error reporting with descriptive messages for configuration issues and provider availability.

### Extensibility
Modular design allows easy addition of new providers and reasoning methods without affecting existing functionality.
