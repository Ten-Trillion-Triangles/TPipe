# Developer-in-the-Loop (DITL) - Manual Control

In an industrial plumbing system, you often need a **Manual Shut-off Valve**—a point where an engineer can step in, inspect the flow, and make adjustments before data reaches its destination. In TPipe, this is the **Developer-in-the-Loop (DITL)** pattern.

DITL functions and pipes provide deterministic checkpoints. They allow you, the developer, to programmatically validate, transform, or reject the data flowing through any valve in your infrastructure.

## Why DITL is Essential

AI models are powerful but fundamentally non-deterministic. They can hallucinate, break structured formatting, or generate unsafe code. DITL provides the **Deterministic Controls** needed for production systems:

*   **Schema Enforcement**: Verify that the model's output matches your exact JSON specifications.
*   **Data Scrubbing**: Automatically strip out sensitive information (PII) before it is logged or stored.
*   **Business Logic Validation**: Run code to check if the model's suggestion is physically or logically possible.
*   **Human Intervention**: Pause the mainline and wait for a human supervisor to approve the next stage.

---

## 1. DITL Functions: The Scrubbing Stage

DITL functions are the simplest way to intervene. You attach a Kotlin lambda to a Pipe. This lambda receives the model's output and can return a modified version, signal a failure, or trigger a reroute.

```kotlin
val auditor = BedrockPipe()
    .setDITLFunction { pipe, originalOutput ->
        // Scrub the output for forbidden terms
        val scrubbed = originalOutput.text.replace("password", "[REDACTED]")

        // Return the modified content to the mainline
        originalOutput.copy(text = scrubbed)
    }
```

### Complex Logic
DITL functions can also execute complex logic, such as calling external APIs or databases to verify the model's work.

```kotlin
pipe.setDITLFunction { pipe, result ->
    if (!verifyInDatabase(result.text)) {
        throw PipelineException("Model produced a non-existent ID.")
    }
    result
}
```

---

## 2. DITL Pipes: The AI Inspector

Sometimes, you need another AI to check the work of the first AI. A **DITL Pipe** is a specialized valve that acts as a **Secondary Inspector**. It takes the output of a primary pipe and uses a different prompt or a more capable model to analyze it.

### The Automated Auditor
TPipe allows you to plug an "Inspector" pipe directly into another valve.

```kotlin
val generator = BedrockPipe().setPipeName("Generator")

val inspector = BedrockPipe()
    .setPipeName("Inspector")
    .setSystemPrompt("You are a strict quality controller. Analyze the input for factual accuracy.")

val mainline = Pipeline()
    .add(generator)
    .add(inspector) // This pipe acts as the AI-in-the-loop
```

---

## 3. Flow Control: The Pressure Valve

DITL often requires pausing the entire mainline to wait for external input. TPipe's pipeline supports declarative **Pause Points**.

```kotlin
val mainline = Pipeline()
    .add(stepOne)
    .addPausePoint("awaiting-engineer-check") // The valve shuts here
    .add(stepTwo)

val result = mainline.execute(input)

if (result.isPaused) {
    // The flow has stopped. You can now surface the result to a UI.
    // Once the engineer approves, resume the flow:
    mainline.resume("Manual approval received.")
}
```

---

## Best Practices

*   **Prefer Determinism**: For formatting and syntax checks (like JSON), always use a code-based DITL Function for 100% accuracy.
*   **Fail Fast**: If a DITL check fails, use the `terminate()` command to stop the flow immediately before it reaches expensive downstream pipes.
*   **Trace the Intervention**: Use TPipe's tracing tools to see exactly how your DITL functions modified the stream. This is critical for auditing why an agent's final output differed from its raw generation.

---

## Next Steps

Now that you can manually control the flow, learn how to automate the inspection using specialized AI-powered pipes.

**→ [Developer-in-the-Loop Pipes](developer-in-the-loop-pipes.md)** - Deep dive into AI-powered validation.
