# Pipeline Flow Control

TPipe provides sophisticated flow control mechanisms that allow pipes to dynamically alter pipeline execution through jumping, repeating, and termination. These features enable complex conditional logic and adaptive processing workflows.

## Table of Contents
- [Flow Control Overview](#flow-control-overview)
- [1. Pipe Jumping (Rerouting)](#1-pipe-jumping-rerouting)
- [2. Pipe Repeating (Recirculation)](#2-pipe-repeating-recirculation)
- [3. Pipeline Termination (Shut-off)](#3-pipeline-termination-shut-off)
- [Practical Patterns](#practical-patterns)
- [Monitoring Flow Control](#monitoring-flow-control)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Flow Control Overview

Linear pipeline execution (A → B → C) is often insufficient for production AI. You need flow control to handle:
- Conditional processing based on content analysis.
- Iterative refinement or multi-step reasoning.
- Graceful early termination when a task is finished.
- Error handling and automated retry logic.

All flow control is managed through the `MultimodalContent` object that travels between pipes.

---

## 1. Pipe Jumping (Rerouting)

The router allows you to skip ahead to a specific pipe or jump backward to an earlier stage in the mainline.

```kotlin
// Inside a validator or transformation function
content.jumpToPipe("error-handler-valve") // Jump to a named pipe
```

**How it works**: The pipeline checks for jump instructions after each pipe execution. If a target is found, it redirects to that specific pipe instead of continuing sequentially.

---

## 2. Pipe Repeating (Recirculation)

The iterative filter tells the pipeline to execute the **current** pipe again with its own output. This is perfect for tasks that require multiple steps of refinement.

```kotlin
content.repeat() // Execute this pipe again until a condition is met
```

**How it works**: After pipe execution, the pipeline checks the `repeatPipe` flag. If true, it re-executes the same valve with the updated content until the flag is cleared.

---

## 3. Pipeline Termination (Shut-off)

TPipe distinguishes between a successful early finish and a system failure:

### Pass Pipeline (Success Exit)
```kotlin
content.passPipeline = true  // Exit early as successful completion
```
- Stops the mainline immediately.
- Preserves the current content as the final result.
- Treats the exit as a valid completion.

### Terminate Pipeline (Error Exit)
```kotlin
content.terminate()  // Exit due to failure
```
- Stops the mainline immediately.
- Clears the content (sets to empty).
- Signals an error/failure condition to the caller.

---

## Practical Patterns

### The "Quality Assurance" Loop
Keep refining content until it meets a specific pressure (quality) standard.

```kotlin
val checker = BedrockPipe()
    .setPipeName("quality-gauge")
    .setValidatorFunction { content ->
        val score = assess(content.text)
        if (score < 80 && getAttemptCount(content) < 3) {
            incrementAttemptCount(content)
            content.jumpToPipe("generator-valve") // Send it back
            false
        } else {
            true // Pressure is good, let it flow
        }
    }
```

### The "Smart Router"
Analyze the input and route it to the correct specialized mainline.

```kotlin
router.setValidatorFunction { content ->
    when (detectType(content.text)) {
        "technical" -> content.jumpToPipe("engineer-pipe")
        "legal" -> content.jumpToPipe("attorney-pipe")
        "complete" -> content.passPipeline = true // Shortcut!
    }
    false // We are jumping or exiting, don't continue sequentially
}
```

---

## Monitoring Flow Control

TPipe's **Tracing** system captures every jump, repeat, and termination. You can visualize the complete execution path in the trace report to see exactly how your adaptive logic behaved.

```kotlin
pipeline.enableTracing()
val trace = pipeline.getTraceReport() // Shows the "Path" the data took
```

---

## Best Practices

*   **Prevent Infinite Loops**: Always include an iteration limit (e.g., using a counter in `metadata`) to ensure a pipe doesn't repeat forever.
*   **Name Your Valves**: Pipe jumping only works if your pipes have unique names set via `.setPipeName()`.
*   **Clear Jump Targets**: Use descriptive pipe names like `error-recovery-stage` so your flow logic is readable.
*   **Check Termination Status**: Use `content.shouldTerminate()` to verify if a pipe has requested a shut-off before continuing with local application logic.

---

## Next Steps

Now that you can control the direction of the flow, learn how to monitor every drop of data as it moves through your system.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
