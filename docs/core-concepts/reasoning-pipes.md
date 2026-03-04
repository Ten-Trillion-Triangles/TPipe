# Reasoning Pipes - High-Pressure Thinking

In a complex municipal infrastructure, sometimes the water needs to be filtered through multiple specialized stages before it's ready for use. **Reasoning Pipes** are the Thinking Tanks of TPipe. They provide **Chain-of-Thought (CoT)** capabilities to any model, even those that don't natively support reasoning.

By attaching a reasoning pipe, you allow your agent to "think before it speaks," performing structured analysis and multi-round problem solving before generating its final response.

## What are Reasoning Pipes?

A Reasoning Pipe is a specialized pipe that executes *before* your main pipe. Its only job is to generate a detailed "thought process" that is then injected into the mainline to inform the final answer.

### The Flow:
1.  **Input** enters the Mainline.
2.  **Reasoning Pipe** executes first, generating a detailed plan or analysis.
3.  **Thought Cargo** is "pumped" into the Main Pipe's prompt.
4.  **Main Pipe** executes with the benefit of the deep reasoning.

---

## Building the Thinking Tank (`ReasoningBuilder`)

TPipe provides several proven Thinking Strategies out of the box. You don't need to craft the perfect reasoning prompt; the `ReasoningBuilder` handles it for you.

### 1. Structured Chain-of-Thought (StructuredCot)
The "Industrial Standard." It uses a formal phase-based framework: **Analyze → Plan → Execute → Validate**.

```kotlin
val reasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    numberOfRounds = 1
)

val mainPipe = BedrockPipe()
    .setReasoningPipe(reasonWithBedrock(config, reasoningSettings, pipeSettings))
```

### 2. Chain of Draft (CoD)
The "Lean Pipe." This strategy forces the model to think in concise, high-signal drafts of 5 words or less. It reduces token usage by up to **75%** while maintaining incredible accuracy for math and logic.

### 3. Role-Play Reasoning
The "Specialist Consultant." The model reasons from the perspective of a specific character (e.g., "You are a senior hydraulic engineer with 30 years experience").

---

## Multi-Round Reasoning: Focus Points

Sometimes a single "thought turn" isn't enough. TPipe supports **Multi-Round Reasoning**, where each round can have a specific **Focus Point**.

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

In this setup, the model spends three full generation turns thinking—one turn for each specific focus area—before the main pipe ever sees the data.

---

## Injection Methods: Rerouting the Thoughts

You can choose where the reasoning "thought cargo" is injected into your main pipe:

*   **System Prompt**: Injected as instructions (Standard).
*   **Before User Prompt**: Injected as "Pre-context" (Best for analytical tasks).
*   **After User Prompt**: Injected as "Post-context" (Best for creative tasks).
*   **As Context**: Injected as a structured page in the **ContextWindow**.

---

## Nested Reasoning: Deep Infrastructure

TPipe supports unlimited nesting. You can have a Reasoning Pipe that has its *own* Reasoning Pipe. This allows you to build multi-layered Brains where one level of thinking informs the next.

```kotlin
val deepLogic = ReasoningBuilder().setReasoningMethod(ReasoningMethod.StructuredCot).build()
val strategy = ReasoningBuilder().setReasoningMethod(ReasoningMethod.ComprehensivePlan).build()
    .setReasoningPipe(deepLogic) // Deep logic informs the strategy

val agent = BedrockPipe()
    .setReasoningPipe(strategy) // Strategy informs the final answer
```

---

## Best Practices

*   **Match the Strategy**: Use `ChainOfDraft` for speed and cost-savings; use `StructuredCot` for mission-critical precision.
*   **Set a Budget**: Always set a `reasoningBudget` in your `TokenBudgetSettings` to ensure your Thinking doesn't crowd out your "Speaking."
*   **Trace the Thoughts**: Enable tracing to see the raw reasoning tokens. This is the only way to understand *why* an agent made a specific decision.

---

## Next Steps

Now that your pipes can think, learn how to control the direction of the flow based on those thoughts.

**→ [Pipeline Flow Control](pipeline-flow-control.md)** - Dynamic routing and conditional execution.
