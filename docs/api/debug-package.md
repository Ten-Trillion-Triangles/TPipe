# Debug Package API

## Table of Contents
- [Overview](#overview)
- [TraceConfig](#traceconfig)
- [TraceEvent](#traceevent)
- [PipeTracer](#pipetracer)
- [TraceVisualizer](#tracevisualizer)
- [FailureAnalysis](#failureanalysis)
- [Enums](#enums)
  - [TraceFormat](#traceformat)
  - [TraceDetailLevel](#tracedetaillevel)
  - [TracePhase](#tracephase)
  - [TraceEventType](#traceeventtype)

## Overview

The Debug package provides comprehensive tracing, monitoring, and analysis capabilities for TPipe pipeline execution, enabling detailed debugging and performance analysis.

## TraceConfig

Configuration object for tracing behavior.

```kotlin
data class TraceConfig(
    val enabled: Boolean = false,
    val maxHistory: Int = 1000,
    val outputFormat: TraceFormat = TraceFormat.CONSOLE,
    val detailLevel: TraceDetailLevel = TraceDetailLevel.NORMAL,
    val autoExport: Boolean = false,
    val exportPath: String = "~/.TPipe-Debug/traces/",
    val includeContext: Boolean = true,
    val includeMetadata: Boolean = true,
    val mergeSplitterTraces: Boolean = true
)
```

### Public Properties

**`enabled`** - Enables or disables tracing system
**`maxHistory`** - Maximum number of trace events to retain per pipeline
**`outputFormat`** - Default format for trace exports (JSON, HTML, MARKDOWN, CONSOLE)
**`detailLevel`** - Level of detail to capture (MINIMAL, NORMAL, VERBOSE, DEBUG)
**`autoExport`** - Automatically export traces after pipeline completion
**`exportPath`** - Directory path for automatic trace exports
**`includeContext`** - Include context snapshots in trace events
**`includeMetadata`** - Include metadata in trace events
**`mergeSplitterTraces`** - If true, Splitter child pipelines broadcast events to the Splitter's trace. If false, they trace independently.

---

## TraceEvent

Individual trace event representing a single execution step.

```kotlin
data class TraceEvent(
    val timestamp: Long,
    val pipeId: String,
    val pipeName: String,
    val eventType: TraceEventType,
    val phase: TracePhase,
    val content: MultimodalContent?,
    val contextSnapshot: ContextWindow?,
    val metadata: Map<String, Any> = emptyMap(),
    val error: Throwable? = null
)
```

### Public Properties

**`timestamp`** - Event occurrence time in milliseconds
**`pipeId`** - Unique identifier of the pipe generating the event
**`pipeName`** - Human-readable name of the pipe
**`eventType`** - Type of event (START, SUCCESS, FAILURE, etc.)
**`phase`** - Execution phase (ENTER, EXECUTE, EXIT, etc.)
**`content`** - Multimodal content at time of event (if includeContext enabled)
**`contextSnapshot`** - Context window state at time of event (if includeContext enabled)
**`metadata`** - Additional event-specific data
**`error`** - Exception information for failure events

---

## PipeTracer

Singleton object managing trace collection and export.

```kotlin
object PipeTracer
```

### Public Functions

#### `enable()`
Enables the tracing system globally.

#### `disable()`
Disables the tracing system globally.

#### `startTrace(pipelineId: String)`
Initializes trace collection for a specific pipeline.

**Behavior:** Creates new trace collection for the pipeline ID. Multiple pipelines can be traced simultaneously with different IDs.

#### `addEvent(pipelineId: String, event: TraceEvent)`
Adds a trace event to the specified pipeline's trace.

**Behavior:** Appends event to pipeline trace and maintains maxHistory limit by removing oldest events when exceeded.

#### `getTrace(pipelineId: String): List<TraceEvent>`
Retrieves complete trace for a pipeline.

**Behavior:** Returns chronological list of all trace events for the pipeline. Returns empty list if pipeline not found.

#### `clearTrace(pipelineId: String)`
Removes all trace data for a pipeline.

#### `exportTrace(pipelineId: String, format: TraceFormat): String`
Exports pipeline trace in specified format.

**Behavior:** 
- **JSON**: Serialized trace events
- **HTML**: Interactive visualization with flow charts and tables
- **MARKDOWN**: Formatted table with execution summary
- **CONSOLE**: Plain text output for terminal display

#### `getFailureAnalysis(pipelineId: String): FailureAnalysis`
Analyzes trace for failures and generates diagnostic report.

**Behavior:** 
- Identifies failure points and last successful operations
- Extracts error messages and context at failure
- Generates suggested fixes based on failure type
- Creates execution path summary for debugging

---

## TraceVisualizer

Generates visual representations of trace data.

```kotlin
class TraceVisualizer
```

### Public Functions

#### `generateFlowChart(trace: List<TraceEvent>): String`
Creates ASCII flow chart representation of execution.

**Behavior:** 
- Detects Manifold vs standard pipeline traces
- Uses emoji symbols for different event types
- Shows execution progression with visual indicators
- Highlights success/failure states

#### `generateTimeline(trace: List<TraceEvent>): String`
Creates chronological timeline of events.

**Behavior:** 
- Shows elapsed time from start for each event
- Includes error messages for failure events
- Displays execution phases and transitions

#### `generateConsoleOutput(trace: List<TraceEvent>): String`
Formats trace for console display.

**Behavior:** 
- Color-coded status indicators
- Includes metadata and error information
- Optimized for terminal readability

#### `generateHtmlReport(trace: List<TraceEvent>): String`
Creates comprehensive HTML report with interactive visualizations.

**Behavior:** 
- **Manifold traces**: Specialized orchestration visualization with agent interaction tables
- **Standard traces**: Pipeline flow graphs with detailed execution tables
- **Interactive elements**: Mermaid.js flow diagrams, responsive tables
- **Styling**: Professional CSS with success/failure color coding

---

## FailureAnalysis

Diagnostic information for failed pipeline executions.

```kotlin
data class FailureAnalysis(
    val lastSuccessfulPipe: String?,
    val failurePoint: TraceEvent?,
    val failureReason: String,
    val contextAtFailure: ContextWindow?,
    val suggestedFixes: List<String>,
    val executionPath: List<String>
)
```

### Public Properties

**`lastSuccessfulPipe`** - Name of last pipe that completed successfully
**`failurePoint`** - TraceEvent where failure occurred
**`failureReason`** - Human-readable failure description
**`contextAtFailure`** - Context state when failure occurred
**`suggestedFixes`** - List of recommended remediation actions
**`executionPath`** - Complete execution path showing pipe progression

---

## Enums

### TraceFormat

Output formats for trace exports.

```kotlin
enum class TraceFormat {
    JSON,       // Serialized trace data
    HTML,       // Interactive web report
    MARKDOWN,   // Formatted documentation
    CONSOLE     // Terminal-friendly output
}
```

### TraceDetailLevel

Levels of trace detail capture.

```kotlin
enum class TraceDetailLevel {
    MINIMAL,    // Only failures and major events
    NORMAL,     // Standard tracing
    VERBOSE,    // All events including metadata  
    DEBUG       // Everything including internal state
}
```

### TracePhase

Execution phases for trace events.

```kotlin
enum class TracePhase {
    ENTER,      // Entering pipe/operation
    EXECUTE,    // During execution
    EXIT,       // Exiting pipe/operation
    ERROR       // Error occurred
}
```

### TraceEventType

Types of trace events (partial list of key events).

```kotlin
enum class TraceEventType {
    // Pipe Events
    PIPE_START, PIPE_SUCCESS, PIPE_FAILURE,
    API_CALL_START, API_CALL_SUCCESS, API_CALL_FAILURE,
    VALIDATION_SUCCESS, VALIDATION_FAILURE,
    TRANSFORMATION_SUCCESS, TRANSFORMATION_FAILURE,
    
    // Manifold Events  
    MANIFOLD_START, MANIFOLD_END, MANIFOLD_SUCCESS, MANIFOLD_FAILURE,
    MANAGER_DECISION, MANAGER_TASK_ANALYSIS, MANAGER_AGENT_SELECTION,
    AGENT_DISPATCH, AGENT_RESPONSE, AGENT_REQUEST_VALIDATION,
    
    // Junction Events
    JUNCTION_START, JUNCTION_END, JUNCTION_SUCCESS, JUNCTION_FAILURE,
    JUNCTION_PAUSE, JUNCTION_RESUME,
    JUNCTION_ROUND_START, JUNCTION_ROUND_END,
    JUNCTION_VOTE_TALLY, JUNCTION_CONSENSUS_CHECK,
    JUNCTION_PARTICIPANT_DISPATCH, JUNCTION_PARTICIPANT_RESPONSE,
    JUNCTION_WORKFLOW_START, JUNCTION_WORKFLOW_END,
    JUNCTION_WORKFLOW_SUCCESS, JUNCTION_WORKFLOW_FAILURE,
    JUNCTION_PHASE_START, JUNCTION_PHASE_END,
    JUNCTION_HANDOFF,

    // P2P Events
    P2P_REQUEST_START, P2P_REQUEST_SUCCESS, P2P_REQUEST_FAILURE,
    
    // Context Events
    TASK_PROGRESS_UPDATE, CONVERSE_HISTORY_UPDATE,
    
    // Pipeline Events
    PIPELINE_TERMINATION
}
```

## Key Behaviors

### Automatic Trace Management
PipeTracer automatically manages trace lifecycle including memory limits, pipeline isolation, and cleanup. Each pipeline maintains independent trace history.

### Intelligent Failure Analysis
FailureAnalysis provides context-aware diagnostics with specific suggestions based on failure types (API failures, validation errors, transformation issues).

### Multi-Format Export
TraceVisualizer supports multiple output formats optimized for different use cases - JSON for programmatic analysis, HTML for interactive debugging, console for development workflows.

### Manifold- and Junction-Aware Visualization
Special handling for Manifold orchestration traces and Junction discussion traces with agent interaction analysis, task progression tracking, vote aggregation, and manager-worker or moderator-participant communication visualization.

### Performance Optimization
Tracing system designed for minimal performance impact with configurable detail levels and efficient event storage. Can be completely disabled for production use.
