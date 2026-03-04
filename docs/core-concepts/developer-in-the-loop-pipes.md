# Developer-in-the-Loop (DITL) Pipes

TPipe provides specialized pipes that use AI models instead of manual code functions for validation, transformation, and error handling. These pipes enable AI-powered processing chains where complex logic—difficult to express in code—can be handled by models optimized for those specific tasks.

## Table of Contents
- [The Three Smart Inspectors](#the-three-smart-inspectors)
- [1. The Validator Pipe](#1-the-validator-pipe)
- [2. The Transformation Pipe](#2-the-transformation-pipe)
- [3. The Branch Pipe](#3-the-branch-pipe)
- [The Execution Order](#the-execution-order)
- [Combined Patterns](#combined-patterns)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## The Three Smart Inspectors

TPipe provides three slots where you can plug in an AI model to monitor or modify your mainline:

### 1. The Validator Pipe
The Quality Inspector. It receives the output of your main pipe and analyzes it for correctness, tone, or factual accuracy.

> [!IMPORTANT]
> **Observation Only**: The Validator Pipe's text output is discarded. Only its **Termination Signal** (`shouldTerminate()`) is respected. It is meant to analyze the content, not modify it. The original content then flows to your code-based `validatorFunction` for the final decision.

### 2. The Transformation Pipe
The Refinement Valve. After a model generates text and it passes validation, the Transformation Pipe can rewrite, reformat, or enhance it before it reaches the final output.

### 3. The Branch Pipe
The Reroute Line. If the main pipe's output fails validation, the flow is automatically diverted to the Branch Pipe, which can attempt to "repair" the content or provide a fallback.

---

## Building a Smart Inspection Line

```kotlin
// 1. Create a specialized inspector
val styleInspector = BedrockPipe()
    .setSystemPrompt("You are a prose editor. Enhance the input for better flow.")

// 2. Create a specialized corrector
val repairValve = BedrockPipe()
    .setSystemPrompt("The previous attempt failed. Fix the JSON errors and try again.")

// 3. Attach them to your main valve
val mainPipe = BedrockPipe()
    .setSystemPrompt("Generate a project summary.")
    .setTransformationPipe(styleInspector) // AI refines output
    .setBranchPipe(repairValve)           // AI handles failures
```

---

## The Execution Order

When you use a mix of DITL Pipes and DITL Functions (code), they follow a strict sequence:

1.  **Main Pipe**: Primary AI processing.
2.  **Validator Pipe**: AI-based validation (analyzes original content).
3.  **Validator Function**: Code-based validation (final say).
4.  **Transformation Pipe**: AI-based transformation (if validation passed).
5.  **Transformation Function**: Code-based transformation.
6.  **Branch Pipe**: AI-based failure recovery (if validation failed).
7.  **OnFailure Function**: Code-based failure recovery.

---

## Combined Patterns

### Conditional Pipe Selection
You can use a code-based validator to decide which AI validator to trigger.

```kotlin
val adaptivePipe = BedrockPipe()
    .setValidatorFunction { content ->
        val complexity = assess(content.text)
        if (complexity > 0.8) {
            setValidatorPipe(expertAIValidator)
            true // Delegate to AI validator
        } else {
            basicValidation(content.text)
        }
    }
```

---

## Best Practices

*   **Model Specialization**: Use a fast, cheap model (like **Haiku**) for the main generation and a powerful, smart model (like **Sonnet** or **Opus**) for the Validator Pipe.
*   **Context Continuity**: Ensure your inspector pipes call `pullPipelineContext()` so they have the same background information as the main pipe.
*   **Name Your Inspectors**: Always use `.setPipeName()` on your DITL pipes. This makes it much easier to see in the **Telemetry (Tracing)** which specific inspector caused a termination or a branch.
*   **Fail Fast**: If the "scrubbing" stage (DITL) fails, use the termination signal to stop the flow immediately before it reaches expensive downstream pipes.

---

## Next Steps

Now that your pipes can think and inspect themselves, learn about the advanced reasoning strategies that can be applied to any valve.

**→ [Reasoning Pipes](reasoning-pipes.md)** - Chain-of-thought reasoning capabilities.
