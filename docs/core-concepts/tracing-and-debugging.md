# Tracing and Debugging - The Pressure Gauges

In a complex agentic infrastructure, you cannot simply look at a final response and know if your system is healthy. You need **Pressure Gauges** and **Flow Meters** to monitor the entire mainline. TPipe's `PipeTracer` is this essential monitoring system, providing high-resolution telemetry for every event, model call, and tool execution.

Tracing is the only way to effectively debug the non-deterministic nature of AI systems in production.

## The Purpose of Tracing

Tracing provides the transparency required for industrial-grade AI engineering:

*   **Flow Visualization**: See exactly how data transitioned from one valve to another.
*   **Prompt Auditing**: Inspect the exact compound prompt sent to the model—after all JSON schemas, tool definitions, and context entries were injected.
*   **Latency Analysis**: Monitor the time-to-first-token (TTFT) and total generation time for every stage.
*   **Resource Accounting**: Track the exact number of input, output, and reasoning tokens consumed.
*   **Tool Verification**: Audit which PCP tools were called and what data they returned to the model.

---

## 1. The PipeTracer: The Control Room

The `PipeTracer` is a thread-local singleton that manages the collection of telemetry within a specific execution scope.

### Basic Implementation
```kotlin
// Initiate a new trace for a specific operation
PipeTracer.startTrace("infrastructure-audit")

// Run your mainline
val result = myPipeline.execute(input)

// Export and analyze the logs
val history = PipeTracer.getTrace()
history.forEach { event ->
    println("[${event.timestamp}] ${event.type}: ${event.message}")
}
```

---

## 2. Telemetry Event Types

TPipe categorizes trace events into distinct streams, allowing you to filter out noise and focus on what matters:

| Stream | Description |
| :--- | :--- |
| **SYSTEM** | Internal engine events, such as session starts and thread handoffs. |
| **PIPE** | Individual valve configuration changes and model interactions. |
| **PIPELINE** | Mainline-level events, including stage transitions and pause/resume signals. |
| **PCP** | Tool Belt interactions, including script execution and API results. |
| **CONTEXT** | Memory reservoir activity, such as LoreBook hits and truncation events. |
| **ERROR** | Detailed failure reports, including stack traces and execution phase data. |

---

## 3. High-Resolution Model Telemetry

When an AI model is invoked, TPipe captures a comprehensive data packet:
*   **Compound Prompt**: The finalized instructions and data delivered to the model.
*   **Model Reasoning**: The raw "thought process" tokens from reasoning models.
*   **Network Metrics**: TTFT (Time to First Token) and total connection duration.
*   **Token Usage**: A precise breakdown of tokens spent in this specific turn.

---

## 4. Swarm Tracing: Multi-Stream Monitoring

In distributed or parallel systems (like a `Splitter` or `DistributionGrid`), multiple flows happen simultaneously. TPipe handles this using **Independent Tracing Streams**.

Each parallel flow can maintain its own isolated trace, which can then be merged back into the main supervisor's trace once the work is complete. This ensures you never lose visibility in complex, concurrent swarms.

```kotlin
// Run a parallel operation with its own isolated gauge
PipeTracer.runWithIsolatedTrace("branch-v4") {
    // Telemetry here is captured in the 'branch-v4' stream
}
```

---

## Best Practices for System Monitoring

*   **Identify Your Valves**: Always use `.setPipeName()` so your traces represent meaningful stages of your infrastructure.
*   **Monitor in Production**: Keep tracing active in production environments with a sensible retention policy to audit why agents made specific (potentially incorrect) decisions.
*   **Export as JSON**: Use `PipeTracer.exportAsJson()` to send telemetry to external observability platforms like DataDog, Honeycomb, or a custom internal dashboard.
*   **Audit the Thoughts**: For reasoning-heavy models (like DeepSeek-R1), use the `modelReasoning` trace field to understand the logic that led to a specific output.

---

## Next Steps

Now that you can monitor the healthy flow of your system, learn how to programmatically handle the inevitable bursts and leaks.

**→ [Error Handling and Propagation](error-handling.md)** - Programmatic error capture and recovery.
