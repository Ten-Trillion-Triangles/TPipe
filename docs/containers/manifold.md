# Manifold

> 💡 **Tip:** The **Manifold** orchestrates multiple specialized agents by keeping a shared conversation history, letting a manager pipeline choose the next worker, and looping until the task is explicitly complete.


## Table of Contents
- [Core Concepts](#core-concepts)
- [Implementation Plan Guidance](#implementation-plan-guidance)
- [DSL Builder](#dsl-builder)
- [Summary Pipeline](#summary-pipeline)
- [Summary to MiniBank Auto-Injection](#summary-to-minibank-auto-injection)
- [initialUserPrompt](#initialuserprompt)
- [Startup Checklist](#startup-checklist)
- [Fastest Working Setup](#fastest-working-setup)
- [Manual Setup](#manual-setup)
- [Execution Flow](#execution-flow)
- [What Manifold Automates](#what-manifold-automates)
- [Context Management](#context-management)
- [Tracing Support](#tracing-support)
- [Common Startup Failures](#common-startup-failures)
- [Best Practices](#best-practices)

Manifold provides manager-worker orchestration where a manager pipeline coordinates task execution across one or more worker pipelines. It wraps the shared task state in `ConverseHistory`, routes worker calls through TPipe's P2P layer, and keeps looping until the manager or runtime sets `passPipeline` or `terminatePipeline`.

`Manifold` instances reuse their configured manager and worker pipelines across loop iterations. Build a fresh manifold for concurrent top-level runs rather than sharing one manifold instance across simultaneous executions.

## Core Concepts

### TaskProgress

The default manager contract uses a `TaskProgress` object to decide whether the overall job is done:

```kotlin
@Serializable
data class TaskProgress(
    var taskDescription: String = "",
    var nextTaskInstructions: String = "",
    var taskProgressStatus: String = "",
    var isTaskComplete: Boolean = false
)
```

When `isTaskComplete` becomes `true`, the manifold exits its loop cleanly.

### Manager Pipeline

The manager pipeline is the controller for the whole manifold. In practice it must:

- Accept the manifold's shared `ConverseHistory`
- Contain at least one pipe capable of producing `AgentRequest` JSON
- Decide whether the task is complete or which worker should run next
- Eventually cause `passPipeline`, `terminatePipeline`, or `TaskProgress.isTaskComplete` to end the loop

The default `TPipe-Defaults` manager pipeline uses two pipes:

1. An entry pipe that reads `ConverseHistory` and emits `TaskProgress`
2. An agent-caller pipe that reads `TaskProgress` and emits `AgentRequest`

### Implementation Plan Guidance

`setImplementationPlan(plan: String?)` attaches stable implementation guidance to the manager control pipes. The value is trimmed before storage. A `null` or blank value clears the guidance. The setter returns the manifold for chaining and applies the prompt overlay immediately when the manager pipeline is available.

The manifold selects manager pipes in manager-pipeline order. The selected set contains:

- The first manager pipe
- Pipes whose names are registered through `addP2pAgentNames(...)` or `setP2pAgentNames(...)`
- Pipes whose JSON output schema matches `AgentRequest`

Each selected pipe receives the following system-prompt block:

```text
Implementation plan:
<trimmed plan text>
```

Worker pipelines and unrelated manager pipes keep their existing system prompts. The overlay is rebuilt from each pipe's raw system prompt during prompt application, so repeated refreshes keep one implementation-plan block. See [Pipe System Prompt Processing](../core-concepts/pipe-class.md#system-prompt-processing).

Configure the plan through the fluent API or the DSL:

```kotlin
val manifold = Manifold()
    .setManagerPipeline(managerPipeline)
    .setImplementationPlan("Use the declared manager contract and delegate through registered workers.")
```

```kotlin
val manifold = manifold {
    manager { /* manager configuration */ }
    worker("research-worker") {
        pipeline(researchPipeline)
    }
    implementationPlan("Use the declared manager contract and delegate through registered workers.")
}
```

`getImplementationPlan()` returns the normalized value, or `null` when guidance is disabled. Plan mutation is permitted between executions. Calling `setImplementationPlan(...)` during `execute(...)` throws `IllegalStateException`; the lifecycle guard covers the execution interval and is released when execution finishes, including exceptional exits. If prompt application fails, the previous plan is restored and the original error is rethrown. The implementation is defined in `Pipeline/Manifold.kt:1075` and `Pipeline/Manifold.kt:1440`; the DSL entry point is in `Pipeline/ManifoldDsl.kt:201`.

### Worker Agents

A worker is anything that implements `P2PInterface` and can serve as a dispatch target for the manager. The canonical worker types are:

- **`Pipeline`** — a single pipeline of pipes that performs one specialized task. This is the classic worker shape.
- **Higher-order containers** — `Junction`, `DistributionGrid`, `PumpStation`, or even a nested `Manifold`. The manager treats them as a single routing target; the container handles its own internal participant routing, voting, or fan-out.

Every worker must:

- Be added to the manifold before startup
- Be able to accept converse input from the manager
- Have prompt overflow protection configured on every pipe in every `Pipeline` worker (or on every leaf pipe across nested `Pipeline`s inside a higher-order worker). Higher-order containers that aggregate multiple pipelines must satisfy this rule transitively.
- Carry a valid `P2PDescriptor` and `P2PRequirements`, either supplied by the caller or auto-generated from the worker shape

A `Pipeline` worker can rely on `addWorker(component)` auto-generating `Transport.Tpipe` descriptor defaults. A higher-order worker must supply its own descriptor and requirements because the defaults assume Pipeline-specific semantics (`usesConverse`, `recordsInteractionContext`, `pcp` context protocol) that may not apply to arbitrary `P2PInterface` components.

## Agent Contract

Understanding the input/output contract between the manifold harness and your agents is critical for writing conforming manager and worker pipelines.

### What the Manifold Provides to the Manager

At each loop iteration, the manager pipeline receives:

- **`ConverseHistory` as JSON in `content.text`** — The accumulated conversation history including all prior manager decisions and worker responses. This is the manager's primary reasoning context.
- **`TaskProgress` extracted from latest system message** — The manifold extracts `TaskProgress` from the most recent system message in history (if present). The manager uses this to understand current state.

### What the Manager Must Provide Back

The manager pipeline must output JSON via `setJsonOutput(AgentRequest())` that contains:

```json
{
  "targetAgentName": "worker-name",
  "taskInstructions": "what to do",
  "skillHint": "optional-skill-name"
}
```

The `targetAgentName` maps to a worker that was registered via `addWorker(component: P2PInterface, ...)`. The canonical method accepts any `P2PInterface` (Pipeline, Junction, DistributionGrid, PumpStation, nested Manifold); `addWorkerPipeline(pipeline: Pipeline, ...)` is a delegating sugar kept for backward compatibility. The manifold routes the request to that worker's P2P endpoint.

### What the Manifold Provides to Workers

When a worker is invoked via P2P, it receives:

- **`MultimodalContent` with the full `ConverseHistory`** — The worker's pipe receives `content.text` containing the JSON-serialized `ConverseHistory`. The worker can inspect this to understand context.
- **`AgentRequest` metadata** — The worker's input may include metadata from the manifold's dispatch (e.g., `skillHint`).

### What Workers Must Provide Back

Workers must return `MultimodalContent` with:

- **`content.text`** — The primary work output (plain text or JSON)
- **`content.terminatePipeline = true`** — If the task is complete and the manifold should exit
- **`content.passPipeline = true`** — If the manager should pass through (rare)

Workers should NOT set `terminatePipeline` unless they want the entire manifold to stop. Worker failures should be handled via validation hooks, not by terminating the whole manifold.

### The Shared History Contract

The manifold maintains a single `workingContentObject` (a `MultimodalContent` wrapping `ConverseHistory`). The contract is:

1. **Manager reads history** — Manager's first pipe receives `ConverseHistory` as JSON in `content.text`
2. **Manager writes decision** — Manager emits `AgentRequest` JSON which the manifold parses
3. **Worker appends result** — Worker response is appended to the history via `appendAgentResponseToConverseHistory`
4. **Repeat** — Next iteration, manager sees updated history

**Important:** The manager should NOT manually modify the history structure. Let the manifold handle history updates. If you need to inject context, use the manager's system prompt or the `initialUserPrompt`.

### DSL Settings That Affect the Contract

| Setting | Effect on Contract |
|---------|-------------------|
| `maxIterations(n)` | Sets hard limit on loop iterations. Without it, the manifold relies on `TaskProgress.isTaskComplete` or `terminatePipeline`. |
| `history { }` | Configures manager history truncation. Determines how much history the manager sees each iteration. |
| `validation { }` | Attaches validator/failure/transformer hooks. These intercept the worker output before it's appended to history. |
| `summaryPipeline { }` | Adds a summarization step after each worker response. Changes what the manager sees (condensed vs full history). |
| `summaryInjection { }` | Companion to `summaryPipeline { }`. When enabled, appends each summary chunk into `workingContentObject.miniBankContext` so the next manager call sees the running summary automatically without a custom `setContextTruncationFunction(...)` hook. Off by default. |
| `killSwitch(input, output, onTripped)` | Sets token limits that halt execution. Protects against runaway workers. |
| `concurrencyMode(ISOLATED)` | Required for P2P exposure. Each request gets a fresh manifold state. |

### How `TaskProgress` Drives Termination

The manager signals completion via `TaskProgress.isTaskComplete = true`:

```kotlin
data class TaskProgress(
    var taskDescription: String = "",
    var nextTaskInstructions: String = "",
    var taskProgressStatus: String = "",
    var isTaskComplete: Boolean = false
)
```

When the manifold detects `isTaskComplete == true`, it sets `workingContentObject.passPipeline = true` and exits the loop cleanly.

### Validation Hook Contract

The validation hooks form a three-stage gate:

1. **Validator** — Runs after worker returns, before history append. Return `false` to trigger failure handler.
2. **Failure handler** — Runs when validator fails. Return `false` to terminate manifold, `true` to continue.
3. **Transformer** — Runs after validation passes. Return the (possibly modified) content to append to history.

This lets you enforce worker output quality without the manager needing to handle malformed responses.

## DSL Builder

If you want the manifold assembled, validated, and initialized in one place, prefer the Kotlin DSL:

```kotlin
import Defaults.BedrockConfiguration
import Defaults.defaults
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.manifold
import bedrockPipe.BedrockMultimodalPipe

val researchWorker = BedrockMultimodalPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-east-1")
    .setPipeName("research worker")
    .setContextWindowSize(8192)
    .autoTruncateContext()

val builtManifold = manifold {
    defaults {
        bedrock(
            BedrockConfiguration(
                region = "us-east-1",
                model = "anthropic.claude-3-haiku-20240307-v1:0"
            )
        )
    }

    worker("research-worker") {
        description("Researches and summarizes requested information.")
        skill("research", "Investigates the user's request.")
        pipeline {
            pipelineName = "research-worker-pipeline"
            add(researchWorker)
        }
    }
}
```

The DSL removes the normal `setManagerPipeline(...)`, `addWorker(component, ...)`, and `init()` ceremony from the common path. The returned manifold is fully initialized and ready for `execute(...)`.

### DSL Blocks

The `manifold { }` builder supports these top-level blocks:

| Block | Required | Description |
|-------|----------|-------------|
| `manager { }` | Yes (or `defaults { }`) | Configures the manager pipeline |
| `worker("name") { }` | Yes (at least one) | Registers a `Pipeline` worker agent |
| `workerP2P("name") { }` | Yes (at least one, mixed with `worker { }`) | Registers any `P2PInterface` worker (Junction, DistributionGrid, PumpStation, nested Manifold) |
| `defaults { }` | Alternative to `manager { }` | Uses TPipe-Defaults to build the manager |
| `maxIterations(limit)` | Optional | Sets the maximum loop iterations (default: 100, null = unlimited) |
| `implementationPlan(plan)` | Optional | Adds stable guidance to the selected manager control pipes. Blank and `null` values clear it. |
| `history { }` | Optional | Configures manager shared-history truncation |
| `initFunction { }` | Optional | Pre-execution startup checks before the manifold loop starts |
| `validation { }` | Optional | Hooks for worker output validation and transformation |
| `tracing { }` | Optional | Enables tracing for the manifold and child pipelines |
| `summaryPipeline { }` | Optional | Adds an optional summarization pipeline that runs after each worker response |
| `summaryInjection { }` | Optional | Companion block to `summaryPipeline { }`. Enables opt-in auto-injection of each iteration's summary text into `workingContentObject.miniBankContext` at a configurable key |
| `concurrencyMode(mode)` | Optional | Sets P2P concurrency mode (SHARED or ISOLATED) |
| `killSwitch(input, output, onTripped)` | Optional | Halts execution if token limits are exceeded |

Each block can only appear once (except `worker` and `workerP2P`, which can appear multiple times and may be mixed).

### implementationPlan(...)

The `implementationPlan(plan)` builder method stores guidance for the selected manager control pipes. The value is copied through builder stage promotion and applied while the built manifold receives its manager pipeline and initializes.

```kotlin
val builtManifold = manifold {
    manager { /* manager configuration */ }
    worker("research-worker") {
        pipeline(researchPipeline)
    }
    implementationPlan("Use the declared worker names and return a valid AgentRequest.")
}
```

All builder methods return `ManifoldBuilder<S>` for chaining. For example, `concurrencyMode()`, `killSwitch()`, `maxIterations()`, and `history()` can be called in sequence:

### validation { }

The `validation` block attaches optional hooks that run between manager and worker turns. These are the same hooks available via `setValidatorFunction(...)`, `setFailureFunction(...)`, and `setTransformationFunction(...)` on the Manifold API, but declared inline:

```kotlin
val builtManifold = manifold {
    defaults {
        bedrock(BedrockConfiguration(region = "us-east-1", model = "anthropic.claude-3-haiku-20240307-v1:0"))
    }

    worker("research-worker") {
        description("Researches topics.")
        pipeline(researchPipeline)
    }

    validation {
        // Runs after each worker returns. Return false to trigger the failure handler.
        validator { content, agent ->
            content.text.isNotBlank() && !content.terminatePipeline
        }

        // Runs when the validator returns false. Return false to terminate the manifold.
        failure { content, agent ->
            println("Worker ${agent.pipelineName} failed validation")
            false
        }

        // Transforms worker output before it is appended to shared history.
        transformer { content ->
            content
        }
    }
}
```

### initFunction { }

The `initFunction` block attaches an optional pre-execution startup hook that runs before the manifold loop begins. This allows developers to perform safety checks and abort startup if conditions aren't met:

```kotlin
val builtManifold = manifold {
    defaults {
        bedrock(BedrockConfiguration(region = "us-east-1", model = "anthropic.claude-3-haiku-20240307-v1:0"))
    }

    worker("research-worker") {
        description("Researches topics.")
        pipeline(researchPipeline)
    }

    initFunction {
        // Runs before the manifold loop starts. Return true to proceed, false to abort.
        initFunction { content, manifold ->
            // Check if prerequisites are met
            println("Starting manifold with initial content: ${content.text.take(100)}")
            true // Return false to abort execution
        }
    }
}
```

This is equivalent to calling `manifold.setManifoldInitFunction(...)` on the API.

### workerP2P { }

The `workerP2P("name") { }` block registers any `P2PInterface` as a worker — Junction, DistributionGrid, PumpStation, or a nested Manifold. This is the companion block to `worker("name") { }`: same stage transitions, same uniqueness rules, mixed freely in the same builder.

```kotlin
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.P2PSkills
import com.TTT.PipelineContextProtocol.Transport
import com.TTT.Pipeline.Junction

val criticAgent = Junction().apply {
    participant("critic-a") { pipeline(criticPipelineA) }
    participant("critic-b") { pipeline(criticPipelineB) }
}

val junctionDescriptor = P2PDescriptor(
    agentName = "critic-council",
    agentDescription = "Two-perspective critic council reachable as a single agent.",
    transport = P2PTransport(
        transportMethod = Transport.Tpipe,
        transportAddress = "critic-council"
    ),
    requiresAuth = false,
    usesConverse = true,
    allowsAgentDuplication = false,
    allowsCustomContext = false,
    allowsCustomAgentJson = false,
    recordsInteractionContext = true,
    recordsPromptContent = true,
    allowsExternalContext = false,
    contextProtocol = ContextProtocol.pcp,
    agentSkills = listOf(
        P2PSkills("critique", "Returns a critique under both perspectives.")
    )
)

val junctionRequirements = P2PRequirements(
    allowAgentDuplication = false,
    allowCustomContext = false,
    allowExternalConnections = false,
    requireConverseInput = true
)

val builtManifold = manifold {
    manager { /* ... */ }

    worker("research-worker") {
        pipeline(researchPipeline)
    }

    workerP2P("critic-council") {
        component = criticAgent
        description("Two-perspective critic reachable as one routing target.")
        descriptor(junctionDescriptor)
        requirements(junctionRequirements)
    }
}
```

The block body configures the worker via `WorkerP2PDsl`:

- `component = <p2pInterface>` — required. The worker to attach.
- `description("...")` — optional human-readable description shown to the manager.
- `descriptor(<P2PDescriptor>)` — required for non-Pipeline workers. The caller owns the descriptor's agent name, transport, and security posture.
- `requirements(<P2PRequirements>)` — required for non-Pipeline workers, paired with `descriptor`.
- `skill(name, description)` / `skills(list)` — optional advertised capabilities.

`workerP2P` and `worker` names must be unique against each other on the same manifold. The validator in `validateWorkers()` scans both lists so duplicate names across shapes throw `IllegalArgumentException` at build time.

The canonical API surface is `manifold.addWorker(component: P2PInterface, descriptor, requirements, agentName, agentDescription, agentSkills)`. `addWorkerPipeline(pipeline: Pipeline, ...)` remains as a delegating sugar so existing call sites compile and run unchanged. Pipeline-shaped workers can omit `descriptor`/`requirements` and rely on auto-generated `Transport.Tpipe` defaults. Higher-order workers must supply both — the auto-generation rules assume Pipeline semantics that may not apply to arbitrary `P2PInterface` components.

### Worker Accessors

After `init()`, two accessors expose attached workers:

- `manifold.getWorkerComponents(): List<P2PInterface>` — every attached worker in attach order, including `Pipeline`, `Junction`, `DistributionGrid`, `PumpStation`, and nested `Manifold`.
- `manifold.getWorkerPipelines(): List<Pipeline>` — backward-compatible sugar that filters `getWorkerComponents()` down to `Pipeline`-shaped workers. New code should prefer `getWorkerComponents()`.

Both accessors are snapshot reads; the manifold's internal worker storage is not mutated after `init()`.

### tracing { }

The `tracing` block enables tracing for the manifold and propagates the configuration to all manager and worker pipelines:

```kotlin
val builtManifold = manifold {
    defaults {
        bedrock(BedrockConfiguration(region = "us-east-1", model = "anthropic.claude-3-haiku-20240307-v1:0"))
    }

    worker("research-worker") {
        description("Researches topics.")
        pipeline(researchPipeline)
    }

    tracing {
        enabled()

        // Optional: supply a custom TraceConfig
        config(TraceConfig(
            enabled = true,
            detailLevel = TraceDetailLevel.DEBUG,
            includeMetadata = true
        ))
    }
}
```

When tracing is enabled through the DSL, all child pipelines share the manifold's trace ID so their events appear in a single correlated trace report.

### summaryPipeline { }

The `summaryPipeline` block adds an optional summarization pipeline that runs **after each worker response** and **before the next manager loop iteration**. This enables progressive summarization of the task as it unfolds, which the manager pipeline can then reason over.

The summary pipeline receives input based on the active `SummaryMode`:

- **`SummaryMode.APPEND`**: Receives only the latest event's raw content text. The manifold appends the output to a running summary string.
- **`SummaryMode.REGENERATE`**: Receives `Prior Summary:\n{runningSummary}\n\nLatest Event:\n{latestEventContent}`. The manifold replaces the running summary with the pipeline's output each iteration — classic condensation style.

```kotlin
val builtManifold = manifold {
    defaults {
        bedrock(BedrockConfiguration(region = "us-east-1", model = "anthropic.claude-3-haiku-20240307-v1:0"))
    }

    worker("research-worker") {
        description("Researches topics.")
        pipeline(researchPipeline)
    }

    summaryPipeline {
        // Optional: set append mode (default) or regenerate mode
        summaryMode(SummaryMode.REGENERATE)

        // The summary pipeline must have overflow protection configured
        pipeline {
            pipelineName = "summary-pipeline"
            add(summaryPipe.withOverflowProtection())
        }
    }
}
```

The summary pipeline is invoked inside the main execution loop, after the worker response is received and merged into `workingContentObject`, but before the termination condition is re-evaluated for the next iteration. The accumulated summary is accessible via the `initialUserPrompt` property after execution.

> **Note:** The summary pipeline must have context overflow protection configured on all its pipes, just like worker pipelines. This prevents a misbehaving summarizer from crashing the manifold.

## Summary to MiniBank Auto-Injection

The `summaryInjection { }` block is the optional, opt-in companion to `summaryPipeline { }`. When enabled, each iteration's summary text is automatically folded into `workingContentObject.miniBankContext` so the next manager call sees the running summary without requiring a developer-side `setContextTruncationFunction(...)` hook.

By default, `summaryPipeline { }` writes to a private `runningSummary` field on the manifold that the manager never reads. `summaryInjection { }` makes the summary visible to the manager's first pipe in the next iteration, in the form of a MiniBank entry that downstream pipes can pull from (either via `pullGlobalContext(...)` + `setPageKey(...)` with the configured MiniBank key, or via direct `workingContentObject.miniBankContext` access in a custom pipe).

### Behavior

- **Default-off.** Existing callers of `setSummaryPipeline(...)` see byte-identical `workingContentObject.miniBankContext` before and after this feature is enabled — it's strictly additive.
- **Stable key.** The summary lands at `"manifold.summary"` (default) or whatever key is set via `miniBankKey(...)`. The string is the top-level MiniBank entry; each iteration's summary text becomes a new `LoreBook` entry on the `ContextWindow` at that key.
- **`APPEND` vs `REGENERATE`** — mirrors `SummaryMode` semantics on the MiniBank side:
  - `SummaryMode.APPEND` — each iteration's summary chunk accumulates as a fresh `LoreBook` entry on the persistent `ContextWindow` at the configured key.
  - `SummaryMode.REGENERATE` — the `ContextWindow` at the configured key is replaced wholesale with a fresh window containing only the latest summary.
- **Synchronous in-loop.** The MiniBank write happens inside the manifold's `while` loop, after the worker's response is merged and before the termination check. There is no async / mutex / interval — it runs once per worker completion.

### DSL

```kotlin
val builtManifold = manifold {
    defaults {
        bedrock(
            BedrockConfiguration(
                region = "us-east-1",
                model = "anthropic.claude-3-haiku-20240307-v1:0"
            )
        )
    }

    worker("research-worker") {
        description("Researches and summarizes requested information.")
        pipeline(researchPipeline)
    }

    summaryPipeline {
        summaryMode(SummaryMode.APPEND)        // APPEND or REGENERATE
        pipeline {
            pipelineName = "summary-pipeline"
            add(summaryPipe.withOverflowProtection())
        }
    }

    summaryInjection {
        injectIntoMiniBank()                  // required — turns the feature on
        miniBankKey("research.summary")       // optional — defaults to "manifold.summary"
    }
}
```

The `summaryInjection { }` block supports:

| Method | Required? | Description |
|---|---|---|
| `injectIntoMiniBank()` | Opt-in flag | Calling this enables the feature. Without it, the block pre-seeds the configured MiniBank key on the manifold but does not activate injection — useful for "configure key now, enable later" workflows. |
| `miniBankKey(key)` | Optional | Override the MiniBank key. Must be non-blank; calling with `""` or whitespace throws `IllegalArgumentException`. Default key is `"manifold.summary"`. |
| *(second call)* | Forbidden | Calling `summaryInjection { }` twice on the same builder throws `IllegalArgumentException`, mirroring the `summaryPipeline { }` state-machine guard. |

### Manual Setters

For callers who build the manifold via direct setters instead of the DSL:

```kotlin
val manifold = Manifold()
    .setManagerPipeline(managerPipeline)
    .addWorkerPipeline(workerPipeline)
    .setSummaryPipeline(summaryPipeline)
    .setInjectSummaryIntoMiniBank(true)        // opt in
    .setSummaryMiniBankKey("research.summary") // optional, defaults to "manifold.summary"
```

- `setInjectSummaryIntoMiniBank(enabled)` — enables or disables the feature; returns the manifold for builder chaining.
- `setSummaryMiniBankKey(key)` — sets the MiniBank key; throws `IllegalArgumentException` if `key` is blank.

### Cost & Iteration Expectations

`summaryPipeline { }` and `summaryInjection { }` together add exactly one extra LLM call per loop iteration (the summary pipeline itself), and Manifold's iteration count should be small (~10–50) by design — the manifold coordinates a known multi-step workflow rather than driving an open-ended agent loop. The MiniBank write per iteration is constant-time. If your workload genuinely needs hundreds of iterations, the heavy container (`PumpStation`) is the more appropriate choice.

### Caveats

- **Iteration-1 visibility.** The summary pipeline runs *after* the first worker dispatch, so the first manager call does not see the summary yet. The summary becomes visible starting from iteration 2. If turn-1 visibility matters, pre-seed the summary via `manifold.setSummary*` setters before the first `execute(...)`.
- **Manager-side consumption.** The summary is written into `workingContentObject.miniBankContext`, but the manager's pipe still needs to actually pull from it. For `TPipe-Defaults` manager pipelines, configure `pullGlobalContext(...)` + `setPageKey(...)` with the configured MiniBank key. For custom manager pipes, read `workingContentObject.miniBankContext.contextMap[summaryMiniBankKey]` directly.
- **`@RuntimeState` preservation.** Both `runningSummary` and `workingContentObject.miniBankContext` are covered by `@RuntimeState`, so pause/resume preserves the accumulated summary transparently. No additional serialization wiring required.

## initialUserPrompt

The `initialUserPrompt` property stores the raw user prompt string passed to `execute()` at the moment it is called. It is public and readable after execution completes:

```kotlin
val result = manifold.execute(MultimodalContent("build me a REST API"))
println(manifold.initialUserPrompt) // "build me a REST API"
```

This lets developer-written manager pipelines reference the original task prompt — for example, by injecting it into a system prompt or using it as a seed for the manager's reasoning.

## Early Validation Rules

The DSL validates your configuration at build time and throws `IllegalArgumentException` with a descriptive message when something is wrong. This catches mistakes before any LLM calls are made.

**Manager validation:**
- A `manager { }` or `defaults { }` block is required
- The manager pipeline must contain at least one pipe
- At least one manager pipe must emit `AgentRequest` JSON output
- If multiple pipes emit `AgentRequest`, you must call `agentDispatchPipe("pipeName")` to disambiguate
- Unnamed `AgentRequest` pipes require an explicit `agentDispatchPipe(...)` declaration

**Worker validation:**
- At least one `worker { }` or `workerP2P { }` block is required
- Worker agent names must be unique across `worker { }` and `workerP2P { }` blocks on the same manifold
- Worker routing identities (from custom descriptors) must be unique
- Worker P2P transport identities must be unique
- Every pipe in every worker pipeline must have overflow protection configured (token budgeting or legacy auto truncation). For higher-order workers, this rule applies transitively to every leaf pipe across nested pipelines.
- Workers with custom P2P descriptors must use `Transport.Tpipe` for local manifold workers
- Custom local TPipe descriptors must have matching `agentName` and `transportAddress`
- Each worker pipeline must contain at least one pipe

**History validation:**
- Manager shared-history control must be configured through one of: `history { }` block, manager pipe overflow protection (auto-inferred), or explicit `setManagerTokenBudget(...)`

**P2P validation:**
- Custom `P2PDescriptor` and `P2PRequirements` must be supplied as a pair — providing one without the other is rejected

## Startup Checklist

Before calling `execute(...)`, make sure all of the following are true:

- A manager pipeline has been assigned with `setManagerPipeline(...)`
- The manager has at least one pipe that emits `AgentRequest`
- At least one worker has been added with `addWorker(component, ...)` — pipeline-shaped via `addWorkerPipeline(...)`, or any `P2PInterface` via `addWorker(component, ...)` with explicit descriptor and requirements
- The manifold has a manager-history control path:
  `setManagerTokenBudget(...)`, manager pipe token budget, `autoTruncateContext()` with context window settings, or `setContextTruncationFunction(...)`
- Every leaf pipe inside every worker pipeline has overflow protection configured
- `init()` has been called

If any of these are missing, `init()` or the first execution loop can fail.

## Fastest Working Setup

This is the shortest setup path when you want `TPipe-Defaults` to build the manager pipeline for you. You still need to add worker pipelines and call `init()`.

```kotlin
import Defaults.BedrockConfiguration
import Defaults.ManifoldDefaults
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import bedrockPipe.BedrockMultimodalPipe
import kotlinx.coroutines.runBlocking

fun buildResearchWorker(): Pipeline
{
    val workerPipe = BedrockMultimodalPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setRegion("us-east-1")
        .setPipeName("research worker")
        .setSystemPrompt("Research the request and return a concise answer.")
        .setContextWindowSize(8192)
        .autoTruncateContext()

    return Pipeline().apply {
        pipelineName = "research-worker"
        add(workerPipe)
    }
}

fun main() = runBlocking {
    val manifold = ManifoldDefaults.withBedrock(
        BedrockConfiguration(
            region = "us-east-1",
            model = "anthropic.claude-3-haiku-20240307-v1:0"
        )
    )

    manifold
        .addWorker(
            component = buildResearchWorker(),
            agentName = "research-worker",
            agentDescription = "Researches and summarizes requested information."
        )
        .init()

    val task = MultimodalContent().apply {
        addText("Investigate the issue and explain the fix.")
    }

    val result = manifold.execute(task)
    println(result.text)
}
```

### Why This Works

- `ManifoldDefaults.withBedrock(...)` creates a manager pipeline with the expected `TaskProgress` and `AgentRequest` flow
- `addWorker(component, ...)` registers the worker locally for manifold routing. Pipeline-shaped workers auto-generate `Transport.Tpipe` descriptor defaults. Higher-order `P2PInterface` workers (`Junction`, `DistributionGrid`, `PumpStation`, nested `Manifold`) require an explicit `descriptor` and `requirements`.
- The worker pipe uses legacy auto truncation, so worker startup passes overflow validation
- `init()` discovers local workers and injects the worker list into the manager's agent-calling pipe

## Manual Setup

Use the manual path when you want full control over prompts, pipe layout, or provider choices.

```kotlin
import com.TTT.P2P.P2PSkills
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.TaskProgress
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.P2P.AgentRequest
import com.TTT.Context.ConverseHistory

fun buildManagerPipeline(taskAnalyzer: Pipe, agentCaller: Pipe): Pipeline
{
    taskAnalyzer
        .setPipeName("task analyzer")
        .setJsonInput(ConverseHistory())
        .setJsonOutput(TaskProgress())

    agentCaller
        .setPipeName("agent dispatcher")
        .setJsonInput(TaskProgress())
        .setJsonOutput(AgentRequest())

    return Pipeline().apply {
        pipelineName = "custom-manager"
        add(taskAnalyzer)
        add(agentCaller)
    }
}

fun buildWorkerPipeline(workerPipe: Pipe): Pipeline
{
    workerPipe
        .setPipeName("implementation worker")
        .setTokenBudget(
            TokenBudgetSettings(
                contextWindowSize = 8192,
                userPromptSize = 4096,
                maxTokens = 1024
            )
        )

    return Pipeline().apply {
        pipelineName = "implementation-worker"
        add(workerPipe)
    }
}

suspend fun buildManifold(taskAnalyzer: Pipe, agentCaller: Pipe, workerPipe: Pipe): Manifold
{
    val manifold = Manifold()
    val managerPipeline = buildManagerPipeline(taskAnalyzer, agentCaller)
    val workerPipeline = buildWorkerPipeline(workerPipe)

    manifold
        .setManagerPipeline(managerPipeline)
        .setImplementationPlan("Use the declared worker names and return a valid AgentRequest.")
        .setManagerTokenBudget(
            TokenBudgetSettings(
                contextWindowSize = 12000,
                userPromptSize = 6000,
                maxTokens = 1200
            )
        )
        .addWorkerPipeline(
            workerPipeline,
            agentName = "implementation-worker",
            agentDescription = "Executes the manager's requested implementation step.",
            agentSkills = listOf(
                P2PSkills("implementation", "Performs implementation tasks from the manager.")
            )
        )

    // Required when your custom agent-calling pipe does not use the default name "Agent caller pipe".
    manifold.addP2pAgentNames("agent dispatcher")

    manifold.init()
    return manifold
}
```

### Manual Path Rules

- At least one manager pipe must emit `AgentRequest`, or `setManagerPipeline(...)` throws
- If you use a custom manager pipe name for agent dispatch, call `addP2pAgentNames(...)`
- Use `setImplementationPlan(...)` or `implementationPlan(...)` to keep manager control guidance present across prompt refreshes
- Do not skip worker overflow protection
- Do not skip `init()`

## Execution Flow

The runtime loop works like this:

1. `execute(...)` ensures the manager has access to P2P agent descriptors
2. Non-converse input is wrapped into a fresh `ConverseHistory`
3. The manifold applies manager-history truncation if configured
4. The manager pipeline runs against the shared working content
5. If the manager marks `TaskProgress.isTaskComplete`, the loop exits
6. Otherwise the manifold extracts `AgentRequest`, routes it to a worker, and appends the worker response back into `ConverseHistory`
7. The loop repeats until `passPipeline` or `terminatePipeline` is set

```kotlin
suspend fun execute(content: MultimodalContent): MultimodalContent {
    hasP2P(managerPipeline)

    val initialHistory = extractJson<ConverseHistory>(content.text)
        ?: ConverseHistory().apply {
            add(ConverseRole.user, content)
        }

    workingContentObject.text = serialize(initialHistory)

    while(!workingContentObject.terminatePipeline && !workingContentObject.passPipeline) {
        applyBuiltInManagerBudgetControl()

        val managerResult = managerPipeline.execute(workingContentObject)
        val currentHistory = extractJson<ConverseHistory>(workingContentObject.text)
        val latestSystemMessage = currentHistory?.history
            ?.lastOrNull { it.role == ConverseRole.system }
            ?.content
            ?.text
        val taskProgress = latestSystemMessage?.let { extractJson<TaskProgress>(it) }
        if(taskProgress?.isTaskComplete == true) {
            workingContentObject.passPipeline = true
            break
        }

        val agentRequest = extractJson<AgentRequest>(managerResult.text) ?: break
        val response = P2PRegistry.sendP2pRequest(agentRequest)
        appendAgentResponseToConverseHistory(response.output)
    }

    return workingContentObject
}
```

## What Manifold Automates

Manifold is not just a loop. It also owns several setup and runtime chores that you would otherwise need to write by hand.

### P2P Registration Defaults

When you call `setManagerPipeline(...)`, `addWorkerPipeline(...)`, or `addWorker(component, ...)` without custom descriptors and requirements, manifold creates secure local-only P2P settings for that worker and registers it with `P2PRegistry`. Pipeline-shaped workers go through the auto-generation path: `Transport.Tpipe`, `usesConverse = true`, `recordsInteractionContext = true`, `contextProtocol = pcp`. Higher-order `P2PInterface` workers (`Junction`, `DistributionGrid`, `PumpStation`, nested `Manifold`) must supply their own descriptor and requirements because the auto-generation rules assume Pipeline semantics that may not apply to the container's participant routing, voting, or fan-out behavior.

### Local Agent Discovery

During `init()`, the manifold collects local worker descriptors from `P2PRegistry.listLocalAgents(this)`, removes the manager from that list, converts the descriptors into `AgentDescriptor` objects, and stores them for manager use.

### Agent List Injection

During `init()`, manifold injects the discovered worker list into each manager pipe named in `agentPipeNames`. If no named pipe is found, it falls back to the last manager pipe and re-applies that pipe's system prompt.

This is why the default manager setup works without manual agent list wiring.

### Shared Converse History

The manifold keeps a single `workingContentObject` that acts as the shared task state. Manager decisions and worker outputs are appended to that history so the next manager turn can reason over the full task progression.

### Manager History Budget Control

If built-in manager budget control is enabled, manifold truncates the shared converse history before each manager run. This protects the manager-facing history only. It does not summarize worker-local context or perform global swarm memory compression.

### Tracing Propagation

If tracing is enabled on the manifold, `init()` propagates the tracing configuration to manager and worker pipelines and gives them the manifold trace ID so their events can be correlated.

## Context Management

### Built-In Manager History Control

Enable automatic converse-history truncation on the manager-facing shared history:

```kotlin
manifold
    .autoTruncateContext()
    .setContextWindowSize(8192)
```

For tighter control, prefer an explicit manager token budget:

```kotlin
manifold.setManagerTokenBudget(
    TokenBudgetSettings(
        contextWindowSize = 12000,
        userPromptSize = 6000,
        maxTokens = 1200
    )
)
```

### Custom Truncation Function

Provide custom manager-history logic when you need to own truncation yourself:

```kotlin
manifold.setContextTruncationFunction { content ->
    println("Custom manager-history truncation for ${content.text.length} characters")
}
```

### Worker Overflow Protection

Every worker pipeline must declare overflow protection before `init()`:

```kotlin
workerPipe
    .setContextWindowSize(8192)
    .autoTruncateContext()
```

or

```kotlin
workerPipe.setTokenBudget(
    TokenBudgetSettings(
        contextWindowSize = 8192,
        userPromptSize = 4096,
        maxTokens = 1024
    )
)
```

### Validation And Transformation Hooks

You can inspect or alter outputs between manager and worker turns:

```kotlin
manifold
    .setValidatorFunction { content, agent ->
        content.text.isNotBlank() && !content.terminatePipeline
    }
    .setTransformationFunction { content ->
        content
    }
```

## Loop Limit Safety

Manifold includes a secondary safety system that halts the loop if iteration count exceeds a configured limit. This prevents runaway token consumption and infinite loops if the manager or workers malfunction.

### API

```kotlin
// Set limit programmatically
manifold.setMaxLoopIterations(100)  // null = unlimited

// Check current configuration
manifold.getMaxLoopIterations()  // Returns Int? (null = unlimited)
manifold.hasLoopLimit()         // Returns true if a limit is configured

// Via DSL
val builtManifold = manifold {
    maxIterations(50)  // Limit to 50 iterations
    manager { ... }
    worker("test-worker") { ... }
}
```

### Behavior

When the loop iteration counter reaches the configured limit, the manifold:

1. Emits a `MANIFOLD_LOOP_LIMIT_EXCEEDED` trace event at `TracePhase.ERROR` with metadata
2. Throws `ManifoldLoopLimitExceededException`

```kotlin
val manifold = Manifold()
    .setManagerPipeline(managerPipeline)
    .addWorkerPipeline(workerPipeline)
    .setMaxLoopIterations(10)
    .autoTruncateContext()

try {
    val result = manifold.execute(task)
} catch (e: ManifoldLoopLimitExceededException) {
    println("Loop hit limit: ${e.iterationsReached}/${e.maxIterations}")
}
```

### Default Behavior

- **Default limit: 100 iterations** — the manifold will loop at most 100 times before throwing
- Set `setMaxLoopIterations(null)` for unlimited execution (relies solely on `KillSwitch` or manager behavior for termination)

### Relationship to KillSwitch

The loop limit and `KillSwitch` are independent safety systems:

| System | What it limits | Trigger |
|--------|----------------|---------|
| `KillSwitch` | Token consumption (input/output) | Exceeded token limits |
| Loop limit | Iteration count | Exceeded loop count |

Both can be configured simultaneously. The loop limit fires first if iterations are exhausted before token limits are hit.

## Tracing Support

Manifold emits orchestration-aware tracing events and propagates tracing to child pipelines:

```kotlin
manifold.enableTracing(
    TraceConfig(
        enabled = true,
        includeMetadata = true,
        detailLevel = TraceDetailLevel.NORMAL
    )
)
```

Common event families include:

- `MANIFOLD_START`, `MANIFOLD_END`, `MANIFOLD_SUCCESS`, `MANIFOLD_FAILURE`
- `MANIFOLD_LOOP_ITERATION`, `MANIFOLD_TERMINATION_CHECK`, `MANIFOLD_LOOP_LIMIT_EXCEEDED`
- `MANAGER_TASK_ANALYSIS`, `MANAGER_DECISION`
- `AGENT_DISPATCH`, `AGENT_RESPONSE`, `P2P_REQUEST_FAILURE`
- `CONVERSE_HISTORY_UPDATE`
- `VALIDATION_START`, `VALIDATION_FAILURE`, `TRANSFORMATION_START`

## Common Startup Failures

### `One or more manager or worker pipelines are empty. Cannot start the manifold.`

Cause:
- The manager has no pipes, or no worker pipelines were added

Fix:
- Build the manager pipeline first
- Add at least one worker pipeline before calling `init()`

### `No pipe in the manager pipeline can make agent calls.`

Cause:
- No manager pipe emits `AgentRequest`

Fix:
- Ensure at least one manager pipe is configured with `setJsonOutput(AgentRequest())`

### `No method of managing manager shared history was found.`

Cause:
- No manager budget-control path was configured

Fix:
- Configure `setManagerTokenBudget(...)`, manager pipe token budgeting, `autoTruncateContext()` plus context window settings, or `setContextTruncationFunction(...)`

### `Worker pipelines require token budgeting or auto truncation before Manifold execution`

Cause:
- At least one worker pipeline has no overflow protection

Fix:
- Add token budgeting or legacy auto truncation to every pipe in every worker pipeline

### Manager loops forever

Cause:
- The manager never emits a completion state or valid next agent handoff

Fix:
- Make sure your manager prompt clearly defines task completion and agent dispatch expectations
- If you use a custom manager pipeline, validate both the `TaskProgress` and `AgentRequest` outputs during testing

## Best Practices

- Start with `TPipe-Defaults` unless you need a custom manager contract
- Treat `init()` as mandatory manifold startup, not optional prewarming
- Keep the manager focused on orchestration and delegate specialized work to workers
- Give every worker explicit overflow protection before adding it to the manifold
- Use `addP2pAgentNames(...)` only when custom manager pipe names break the default `"Agent caller pipe"` convention
- Enable tracing while developing custom manager prompts or agent-routing behavior
- Build a fresh manifold per concurrent top-level task
- Use `summaryInjection { }` after `summaryPipeline { }` to make the running summary visible to the next manager call via `workingContentObject.miniBankContext` (see [Summary to MiniBank Auto-Injection](#summary-to-minibank-auto-injection))

## P2P Concurrency

Manifold is stateful — it maintains working content, loop counters, and agent interaction maps during execution. When exposed via P2P, register with `P2PConcurrencyMode.ISOLATED` so each inbound request gets a fresh clone. See [P2P Registry and Routing](../advanced-concepts/p2p/p2p-registry-and-routing.md#concurrency-modes) for details.

---

**Previous:** [← Splitter](splitter.md) | **Next:** [DistributionGrid →](distributiongrid.md)
## Next Steps

- [Connector - Pipeline Branching](connector.md) - Move to branching between pipelines.
