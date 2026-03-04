# Reasoning Pipes

Reasoning pipes provide chain-of-thought capabilities to any AI model, enabling complex problem-solving and step-by-step analysis even on models that don't natively support reasoning modes. TPipe provides proven reasoning builders that create optimized thinking stages for different problem types.

## Table of Contents
- [What are Reasoning Pipes?](#what-are-reasoning-pipes)
- [Building the Thinking Tank (ReasoningBuilder)](#building-the-thinking-tank-reasoningbuilder)
- [Reasoning Methods](#reasoning-methods)
- [Multi-Round Reasoning with Focus Points](#multi-round-reasoning-with-focus-points)
- [Reasoning Injection Methods](#reasoning-injection-methods)
- [Nested Reasoning](#nested-reasoning)
- [Cross-Provider Reasoning](#cross-provider-reasoning)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## What are Reasoning Pipes?

A Reasoning Pipe is a specialized valve that executes **before** your main pipe. Its only job is to generate a detailed "thought process" that is then injected into the mainline to inform the final answer.

**The Flow:**
1.  **Input** enters the Mainline.
2.  **Reasoning Pipe** executes first, generating a detailed plan or analysis.
3.  **Thought Cargo** is pumped into the Main Pipe's prompt.
4.  **Main Pipe** executes with the benefit of the deep reasoning context.

---

## Building the Thinking Tank (`ReasoningBuilder`)

TPipe provides several proven Thinking Strategies out of the box. You don't need to craft the perfect reasoning prompt; the `ReasoningBuilder` handles it for you.

```kotlin
val reasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    numberOfRounds = 1
)

val mainPipe = BedrockPipe()
    .setReasoningPipe(reasonWithBedrock(config, reasoningSettings, pipeSettings))
    .setTokenBudget(TokenBudgetSettings(reasoningBudget = 2000))
```

---

## Reasoning Methods

### 1. Structured Chain-of-Thought (StructuredCot)
The industrial standard. It uses a formal phase-based framework: **Analyze → Plan → Execute → Validate**.
- **Best for**: General problem solving, systematic analysis, and structured decision making.

### 2. Chain of Draft (CoD)
The lean pipe. This strategy forces the model to think in concise, high-signal drafts of **5 words or less** per step.
- **Performance**: Up to 75% reduction in token usage and over 78% decrease in latency while maintaining accuracy.
- **Best for**: Mathematical calculations and logical puzzles.

### 3. Role-Play Reasoning
The specialist consultant. The model reasons from the perspective of a specific character (e.g., "You are a senior software architect").
- **Best for**: Domain expertise simulation and perspective-based analysis.

### 4. Comprehensive Plan
Asks the AI to develop a substantial, multi-step strategy before acting.
- **Best for**: Strategic planning and complex task decomposition.

---

## Multi-Round Reasoning with Focus Points

Sometimes a single thought turn isn't enough. TPipe supports **Multi-Round Reasoning**, where each round can have a specific **Focus Point** to guide the AI's attention.

```kotlin
val focusPoints = mapOf(
    1 to "identify potential failure points in the valve design",
    2 to "propose a redundant safety system",
    3 to "calculate the total cost of implementation"
)

val strategicReasoning = ReasoningSettings(
    numberOfRounds = 3,
    focusPoints = focusPoints
)
```

**What happens in each round:**
- **Round 1**: AI focuses specifically on the first point.
- **Round 2**: AI concentrates on the second point while building on the insights from Round 1.
- **Round 3**: AI finalizes its thoughts based on the accumulated analysis.

---

## Reasoning Injection Methods

You can choose where the reasoning "thought cargo" is placed in your main pipe:

*   **`SystemPrompt`**: Injected as foundational instructions (Standard).
*   **`BeforeUserPrompt`**: Injected as immediate context for the current turn.
*   **`AfterUserPrompt`**: Injected after the user's input.
*   **`AsContext`**: Injected as a structured page in the **ContextWindow**.
*   **`BeforeUserPromptWithConverse`**: Injected into a structured conversation block.

---

## Nested Reasoning

TPipe supports unlimited nesting. You can have a Reasoning Pipe that has its own Reasoning Pipe, allowing you to build multi-layered brains where one level of thinking informs the next.

```kotlin
val deepLogic = ReasoningBuilder().setReasoningMethod(ReasoningMethod.StructuredCot).build()
val strategy = ReasoningBuilder().setReasoningMethod(ReasoningMethod.ComprehensivePlan).build()
    .setReasoningPipe(deepLogic) // Level 2 thinking informs Level 1

val agent = BedrockPipe()
    .setReasoningPipe(strategy) // Level 1 thinking informs the final answer
```

---

## Cross-Provider Reasoning

You can use different providers for reasoning and execution. For example, use a high-powered local Ollama model for reasoning and a cloud-based Bedrock model for the final response.

```kotlin
val ollamaReasoning = reasonWithOllama(ollamaConfig, settings, pipeSettings)

val hybridPipe = BedrockPipe()
    .setReasoningPipe(ollamaReasoning)
```

---

## Best Practices

*   **Match the Strategy**: Use `ChainOfDraft` for speed and cost-savings; use `StructuredCot` for mission-critical precision.
*   **Set a Budget**: Always define a `reasoningBudget` in your `TokenBudgetSettings` to ensure your Thinking doesn't crowd out your actual response.
*   **Trace the Thoughts**: Enable tracing to see the raw reasoning tokens. This is the only way to understand the logic that led to a specific decision.
*   **Limit Nesting**: While unlimited nesting is supported, 3-4 levels is typically the industrial limit for optimal performance.

---

## Next Steps

Now that your pipes can think, learn how to control the direction of the flow based on those thoughts.

**→ [Pipeline Flow Control](pipeline-flow-control.md)** - Dynamic routing and conditional execution.
