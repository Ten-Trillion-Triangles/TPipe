# Debug Package API

The Debug package is the Telemetry Suite of the TPipe ecosystem. It provides comprehensive tracing, monitoring, and high-resolution failure analysis for pipeline execution. By capturing every event—from initial model calls to complex agent handoffs—the Debug package ensures that even the most complex multi-agent swarms remain observable and maintainable.

## Table of Contents
- [TraceConfig: Configuring the Gauges](#traceconfig-configuring-the-gauges)
- [TraceEvent: The Telemetry Drop](#traceevent-the-telemetry-drop)
- [PipeTracer: The Monitoring Center](#pipetracer-the-monitoring-center)
- [FailureAnalysis: The Diagnostic Kit](#failureanalysis-the-diagnostic-kit)
- [Key Operational Behaviors](#key-operational-behaviors)

---

## TraceConfig: Configuring the Gauges

The `TraceConfig` object defines exactly how much telemetry should be captured and where it should be sent.

*   **`detailLevel`**: Controls the density of the flow. `MINIMAL` captures only failures, while `DEBUG` records the complete internal state of every component.
*   **`outputFormat`**: Determines how reports are generated. Supports `HTML` for interactive web-based visualization, `JSON` for machine consumption, and `CONSOLE` for real-time development feedback.
*   **`includeContext`**: If true, TPipe takes a snapshot of the entire **ContextWindow** at every step, allowing you to see exactly what the model "Knew" when it made a specific decision.

---

## TraceEvent: The Telemetry Drop

A single `TraceEvent` represents one specific moment in the execution flow.

```kotlin
data class TraceEvent(
    val timestamp: Long,
    val pipeName: String,
    val eventType: TraceEventType, // e.g., API_CALL_START, VALIDATION_FAILURE
    val phase: TracePhase,          // e.g., ENTER, EXECUTE, EXIT
    val contextSnapshot: ContextWindow? // The memory reservoir state
)
```

---

## PipeTracer: The Monitoring Center

`PipeTracer` is the central engine that collects and manages trace data across the system.

*   **`startTrace(id)`**: Begins recording telemetry for a specific pipeline or session.
*   **`exportTrace(id, format)`**: Generates a finalized report. The **HTML** export is particularly powerful, including interactive Mermaid.js flowcharts that map the entire agentic architecture.
*   **Multi-Stream Support**: Tracing can broadcast to multiple IDs simultaneously, which is essential for capturing events in parallel **Splitter** lines or decentralized **DistributionGrids**.

---

## FailureAnalysis: The Diagnostic Kit

When a mainline bursts, `FailureAnalysis` provides an automated report to help you find and fix the leak.

*   **`lastSuccessfulPipe`**: Identifies exactly how far the data flowed before the failure.
*   **`failureReason`**: Provides a human-readable explanation derived from the exception or the validation rejection.
*   **`suggestedFixes`**: Uses an internal logic engine to suggest remedies (e.g., "Check AWS credentials" for `API_CALL_FAILURE` or "Relax validation schema" for `VALIDATION_FAILURE`).

---

## Key Operational Behaviors

### 1. High-Resolution Observability
TPipe doesn't just log text; it logs the **Compound Specification**. You can see exactly how JSON schemas and tool definitions were assembled into the final prompt, making it easy to identify instructions that were ignored or misinterpreted by the model.

### 2. Orchestration Awareness
The tracing system understands the difference between a simple pipeline and a complex orchestrator like a **Manifold**. It visualizes manager-worker handoffs as distinct "Connection Events," allowing you to audit the efficiency of your agent team.

### 3. Performance-Focused
Tracing is designed for minimal overhead. When disabled, the telemetry probes are bypassed entirely, ensuring no impact on production throughput. When enabled at `NORMAL` level, the impact is typically less than 1% of total generation time.

### 4. Interactive Reporting
The HTML reports are not static logs. They are dynamic tools that allow developers to expand and collapse different branches of an execution, inspect raw JSON payloads, and visualize the timing of every stage in the infrastructure.
