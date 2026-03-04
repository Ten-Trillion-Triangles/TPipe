# Developer-in-the-Loop (DITL) Pipes - The Smart Inspectors

In an advanced industrial infrastructure, you don't just rely on manual code-based valves; you use **Smart Inspectors**—specialized AI models that watch the flow of other models. **DITL Pipes** allow you to use an AI model instead of a Kotlin function for validation, transformation, and error recovery.

This enables "AI-powered processing chains" where subjective or complex logic (like "Does this sound professional?") is handled by a model optimized for that specific task.

## The Three Smart Inspectors

TPipe provides three slots where you can "plug in" an AI model to monitor your mainline:

### 1. The Validator Pipe
The "Quality Inspector." It receives the output of your main pipe and analyzes it.

> [!IMPORTANT]
> **Observation only**: The Validator Pipe's text output is discarded. Only its "Termination Signal" is respected. It's meant to *analyze* the content, not modify it. The original content then flows to your `validatorFunction` for the final "Yes/No" decision.

### 2. The Transformation Pipe
The "Refinement Valve." After a model generates text and it passes validation, the Transformation Pipe can rewrite, reformat, or enhance it before it reaches the final output.

### 3. The Branch Pipe
The "Reroute Line." If the main pipe's output fails validation, the flow is automatically diverted to the Branch Pipe, which can attempt to "repair" the content or provide a fallback.

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
    .setTransformationPipe(styleInspector) // Automatically refines output
    .setBranchPipe(repairValve)           // Automatically handles failures
```

---

## The Execution Order

When you use a mix of DITL Pipes and DITL Functions (code), they follow a strict Plumbing Sequence:

1.  **Main Pipe** (The Source)
2.  **Validator Pipe** (AI Inspector)
3.  **Validator Function** (Code Inspector - The final say)
4.  **Transformation Pipe** (AI Refinement)
5.  **Transformation Function** (Code Refinement)
6.  **Branch Pipe** (AI Reroute - only if validation fails)
7.  **OnFailure Function** (Code Reroute - only if validation fails)

---

## Best Practices

*   **Model Specialization**: Use a fast, cheap model (like **Haiku**) for the main generation and a powerful, smart model (like **Sonnet** or **Opus**) for the Validator Pipe.
*   **Share the Reservoir**: Ensure your inspector pipes call `pullPipelineContext()` so they have the same background information as the main pipe.
*   **Name Your Inspectors**: Always use `.setPipeName()` on your DITL pipes. This makes it much easier to see in the **Pressure Gauges (Tracing)** which specific inspector caused a termination or a branch.

---

## Next Steps

Now that your pipes can think and inspect themselves, learn about the advanced reasoning strategies that can be applied to any valve.

**→ [Reasoning Pipes](reasoning-pipes.md)** - Chain-of-thought reasoning capabilities.
