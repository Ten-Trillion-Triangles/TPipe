# TPipe-Defaults Package API

The TPipe-Defaults package provides the Standard Fittings—a collection of pre-configured factories, builders, and templates that accelerate the setup of complex agentic infrastructure. It handles the heavy lifting of provider-specific optimizations for AWS Bedrock and Ollama, while offering a comprehensive system for building sophisticated reasoning mainlines.

## Table of Contents
- [Configuration Classes](#configuration-classes)
- [Factory Classes](#factory-classes)
- [Reasoning System](#reasoning-system)
- [Enums and Settings](#enums-and-settings)

---

## Configuration Classes

### ProviderConfiguration
The foundational blueprint for provider settings. It ensures that any "Mainline Source" (Bedrock, Ollama) has the necessary technical specs before it starts generating flow.

#### BedrockConfiguration
- **`region` / `model`**: The primary coordinates for the AWS refinery.
- **`pipeCount`**: The number of specialized valves to create when building a manager pipeline (default is 2).
- **`inferenceProfile`**: Allows for binding the configuration to a specific cross-region ARN for optimal throughput.

#### OllamaConfiguration
- **`host` / `port`**: The local address of the Ollama server.
- **`timeout`**: Standard connection limit (default is 30,000ms).

---

## Factory Classes

### ManifoldDefaults
The central assembly line for creating **Manifold** orchestrators with provider-specific defaults.

*   **`withBedrock(config)` / `withOllama(config)`**: Creates a fully-plumbed Manifold instance.
*   **`buildDefaultManagerPipeline(config)`**: Constructs a standard 2-valve manager pipeline:
    1.  **Task Analyst**: Determines if the current job is complete or needs more work.
    2.  **Agent Selector**: Decides which specialized sub-agent (worker) to call next.

### BedrockDefaults / OllamaDefaults
Internal factories that handle the low-level "Piping" required to initialize specific model instances, set up JSON schemas for task progress, and enable provider-specific features like the Bedrock Converse API.

---

## Reasoning System

The reasoning system transforms standard pipes into high-pressure "Thinking Tanks."

### ReasoningBuilder
A high-level factory for applying reasoning templates to any Pipe.
- **`assignDefaults(settings, pipeSettings, targetPipe)`**: Applies a reasoning strategy to an existing valve, configuring its system prompt, multi-round history, and token budgets in one operation.
- **`reasonWithBedrock()` / `reasonWithOllama()`**: Convenience methods to create a new reasoning-enabled pipe from scratch.

### ReasoningPrompts
A repository of industrial-grade prompts for different thinking strategies, including:
- **`StructuredCot`**: A phase-based framework (Analyze → Plan → Execute → Validate).
- **`ChainOfDraft`**: A lean, high-efficiency reasoning method.
- **`RolePlay`**: Domain-specific expertise simulation.

---

## Enums and Settings

### ReasoningSettings
The technical specification for a reasoning tank.
- **`reasoningMethod`**: Defines the "Thinking Style" (e.g., `ComprehensivePlan`, `ExplicitCot`).
- **`depth`**: `Low`, `Med`, or `High` complexity of thought.
- **`duration`**: `Short`, `Med`, or `Long` generation cycles.
- **`numberOfRounds`**: Enables multi-turn "Deep Thinking" where the agent reflects on its own logic multiple times before answering.

### ReasoningInjector
Determines where the "Thought Cargo" is placed in the mainline:
- **`SystemPrompt`**: Injected as foundational instructions.
- **`BeforeUserPrompt`**: Injected as immediate context for the current turn.
- **`AsContext`**: Injected as a structured page in the **ContextBank**.
