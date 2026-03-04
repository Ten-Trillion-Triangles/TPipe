# Pipeline Flow Control - Routing the Mainline

In a complex municipal infrastructure, water doesn't always flow in a straight line. Sometimes you need to **Reroute** it to a treatment plant, **Recirculate** it through a filter, or **Shut off** the valve entirely if a leak is detected. **Pipeline Flow Control** gives you these deterministic Logic Gates for your AI workflows.

By using flow control, you can transform a simple sequence of pipes into an adaptive, intelligent system that reacts to the data it processes.

## The Three Core Controls

TPipe provides three primary ways to alter the flow of a pipeline:

### 1. Pipe Jumping (Rerouting)
The "Router." This allows you to skip ahead to a specific pipe or jump backward to an earlier stage in the mainline.

```kotlin
// Inside a validator or transformation function
content.jumpToPipe("error-handler-valve") // Jump to a named pipe
```

### 2. Pipe Repeating (Recirculation)
The "Iterative Filter." This tells the pipeline to execute the *current* pipe again with its own output. This is perfect for tasks that require multiple steps of refinement or "thinking."

```kotlin
content.repeat() // Execute this pipe again until a condition is met
```

### 3. Termination (Shut-off)
TPipe distinguishes between a Successful Finish and a "System Failure."

*   **`passPipeline = true`**: The "Success Exit." Stops the flow immediately and returns the current result as a success. Use this for Shortcuts when a task is finished early.
*   **`terminate()`**: The "Emergency Stop." Stops the flow and clears the content, signaling a failure. Use this when the input is invalid or a critical error is detected.

---

## Practical Flow Patterns

### The "Quality Assurance" Loop
You can use a combination of jumping and repeating to create a loop that keeps refining content until it meets a specific pressure (quality) standard.

```kotlin
val checker = BedrockPipe()
    .setPipeName("quality-gauge")
    .setValidatorFunction { content ->
        val score = assess(content.text)
        if (score < 80 && attempts < 3) {
            content.jumpToPipe("generator-valve") // Send it back for more work
            false
        } else {
            true // Pressure is good, let it flow
        }
    }
```

### The "Smart Router"
Use an initial pipe to analyze the "chemical composition" of the input and route it to the correct specialized mainline.

```kotlin
router.setValidatorFunction { content ->
    when (detectType(content.text)) {
        "technical" -> content.jumpToPipe("engineer-pipe")
        "legal" -> content.jumpToPipe("attorney-pipe")
        "complete" -> content.passPipeline = true // No work needed!
    }
    false // We are jumping or exiting, don't continue sequentially
}
```

---

## Best Practices for Flow Control

*   **Prevent Infinite Recirculation**: Always include a "Turn Counter" in your metadata to ensure a pipe doesn't repeat forever.
*   **Name Your Valves**: Pipe jumping only works if your pipes have clear, unique names set via `.setPipeName()`.
*   **Use the Right Exit**: Only use `terminate()` for genuine failures. If the agent successfully finished the job early, use `passPipeline`.
*   **Monitor the Gauges**: Enable **Tracing** to see a visual map of how the data jumped, repeated, and exited during execution.

---

## Next Steps

Now that you can control the direction of the flow, learn how to monitor every drop of data as it moves through your system.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
