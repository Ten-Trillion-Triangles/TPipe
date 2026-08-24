# Meta-Page-Keys State Sharing — Concept Guide

A TPipe pattern for passing scratch state across execution boundaries via `MetadataBank`, parallel to the existing `Pipe.readFromGlobalContext` execution-time pattern.

## Table of Contents

- [What This Pattern Solves](#what-this-pattern-solves)
- [The Pattern Family in TPipe](#the-pattern-family-in-tpipe)
- [Why a New Pattern](#why-a-new-pattern)
- [Architectural Placement](#architectural-placement)
- [When to Use](#when-to-use)
- [When Not to Use](#when-not-to-use)
- [Comparison to Existing Patterns](#comparison-to-existing-patterns)
- [Lifecycle: From Setter to Auto-Pull](#lifecycle-from-setter-to-auto-pull)
- [Cross-Boundary Scenarios](#cross-boundary-scenarios)
- [Common Mistakes](#common-mistakes)
- [API Surface Reference](#api-surface-reference)

## What This Pattern Solves

TPipe developers routinely need to pass scratch state across execution boundaries — between pipes, between a manager and its workers, between a dispatch agent and a path on a pump station. Before `MetadataBank` shipped, the only options were:

1. Pass state through `MultimodalContent.metadata` (the data carrier surface).
2. Pass state through `pipeMetadata` and rely on `readFromParentPipeContext` (parent-pipe-only).
3. Hand-roll a global `object` with thread-safe state (custom infrastructure per use case).

The meta-page-keys pattern adds a fourth option: a globally addressable, page-keyed, in-memory scratchpad with a single glued-string parameter as the address. A developer arms a pipe or pump station with one string; the runtime populates the metadata bag at execution time from the named pages.

The pattern trades the manual plumbing of options 1-3 for a one-line configuration and a runtime auto-pull. It is not a replacement for any existing pattern — it is an additional tool.

## The Pattern Family in TPipe

TPipe has a family of `readFrom<Source>` patterns on `Pipe`. Each one mirrors the same shape:

| Pattern | Configuration field | Runtime trigger | Pulls |
|---|---|---|---|
| `readFromGlobalContext` | `setReadFromGlobalContext(true)` | Inside `Pipe.execute*()`'s `readFromGlobalContext` block | `contextWindow` from `ContextBank` |
| `readFromPipelineContext` | `setReadFromPipelineContext(true)` | Inside `Pipe.execute*()`'s `readFromPipelineContext` block | `contextWindow` and `miniBank` from the parent pipeline |
| `readFromParentPipeContext` | `setReadFromParentPipeContext(true)` | Inside `Pipe.execute*()`'s `readFromParentPipeContext` block | `contextWindow` and `miniBank` from the parent pipe |
| `readFromPumpStationContext` | `setReadFromPumpStationContext(true)` | Inside `Pipe.execute*()`'s `readFromPumpStationContext` block | `contextWindow` and `miniBank` from the nearest pump station parent |
| **Meta-page-keys** (this pattern) | `setMetaPageKeys("page1, page2")` | Inside `Pipe.execute*()`'s post-`readFromGlobalContext` and `readFromPumpStationContext` blocks | `pipeMetadata` and `pumpStation.metadata` from `MetadataBank` |

Every member of the family follows the same discipline: a configuration setter records a parameter, the runtime checks it at execute-time, and if the parameter is set, the runtime pulls from the corresponding source. The developer does not need to call the pull method explicitly under normal flow.

The meta-page-keys pattern is the **first member of this family that pulls from `MetadataBank`** rather than from `ContextBank`. The other members target the LLM context path (the `contextWindow` that flows into the prompt); this member targets the metadata path (the developer-facing scratch state).

## Why a New Pattern

`MetadataBank` exists because the existing patterns do not cover all the state-passing shapes that TPipe developers need.

The existing `readFromGlobalContext` pattern pulls the LLM context window — the content that becomes the system prompt. That content is shaped for the model; it is not a developer scratchpad. Developers who want to stash a tool-output intermediate, a worker progress marker, or a cross-pipe coordination flag cannot put it in the LLM context window without polluting the prompt.

The existing `pipeMetadata` field on `Pipe` is reachable through `readFromParentPipeContext`, but only from a directly parented pipe. A worker pipe dispatched by a Manifold has the manager as parent but cannot easily reach a sibling worker's metadata. Cross-coordination state has to flow up through the parent and back down, which couples the worker to the manager's coordination shape.

`MetadataBank` decouples this. A worker pipe writes to a named page; another worker pipe reads from the same named page. The bank is the rendezvous. Neither pipe has to know about the other's structure; they only have to agree on page names.

The bulk-pull glued-string parameter keeps the configuration surface compact. A pipe's metadata sources are described in one short string, not a builder DSL or a runtime method call sequence.

## Architectural Placement

```
+------------------+         +-------------------+         +-----------------+
|   Pipe A         |         |   MetadataBank    |         |   Pipe B        |
|                  |  writes |                   |  reads  |                 |
| pipeMetadata     +-------->|  page1: {...}     +-------->+ pipeMetadata    |
| (dev scratch)    |         |  page2: {...}     |         | (auto-populated)|
+------------------+         |  page3: {...}     |         +-----------------+
                              |                   |
+------------------+         |                   |         +-----------------+
|  PumpStation     |  writes |                   |  reads  |  PumpStation    |
|                  +-------->|                   +-------->+                 |
| metadata         |         |                   |         | metadata        |
| (Any?-keyed)     |         |                   |         | (Any?-keyed)    |
+------------------+         +-------------------+         +-----------------+
                              ^
+------------------+         |                   |         +-----------------+
|  Pipe C          |         |                   |         |  ...            |
| (auto-pulls      +---------+                   +---------+  (auto-pulls   |
|   at execute)    |                               |         |   at execute)   |
+------------------+                               +---------+
```

The bank is a process-singleton. Writers and readers do not need to be aware of each other. The pull happens at the runtime point when the reader is exercised; the writer's commit happens when the writer calls `setMeta` or `emplace`.

## When to Use

Use the meta-page-keys pattern when:

- **Two pipes that are not in a parent-child relationship need to share state.** A worker pipe writing to a named page; another worker pipe reading from the same page.
- **A pump station and the pipes it dispatches need to share state.** The station writes workflow-level markers; the dispatched pipe reads them at execute-time.
- **The shared state is developer-only scratch state** (intermediate values, flags, coordination markers). It is not LLM context and should not flow into the model prompt.
- **The page keys can be described as a small glued-string list.** The pattern fits when the set of pages is known at configuration time, not when it is computed dynamically.
- **The dev wants one-string configuration.** `setMetaPageKeys("apex.flow_state, workflow.global_state")` is shorter than a builder DSL.

## When Not to Use

Do not use the meta-page-keys pattern when:

- **The state is LLM context.** That is `readFromGlobalContext` and the context-window family. Polluting the metadata bag with prompt content confuses the role separation.
- **The state is short-lived within a single execution.** Use `MultimodalContent.metadata` or local variables — the bank outlives the JVM, so putting one-shot intermediate state in it leaks memory.
- **The state has a schema or type contract.** The bank is `Map<Any, Any>` with no validation. Strongly typed state belongs on the relevant class's fields.
- **The page keys are computed at runtime.** The pattern takes a static glued-string. Dynamic key sets need direct `MetadataBank.pullMetaPageKeysInto` calls with a runtime-computed key list.
- **The reader and writer need cross-process sharing.** The bank is process-local. Cross-process state is `ContextBank` with `StorageMode.REMOTE`.
- **The state needs disk persistence.** The bank does not persist. Use `ContextBank` with `StorageMode.MEMORY_AND_DISK` or `DISK_ONLY`.

## Comparison to Existing Patterns

| Need | Pattern | Why |
|---|---|---|
| Pass prompt content to the LLM | `readFromGlobalContext` | Targets the LLM `contextWindow` from `ContextBank`. |
| Pass state between a parent pipe and its child pipe | `readFromParentPipeContext` | Direct parent-child relationship; the parent pipe's `contextWindow` and `miniBank`. |
| Pass state between a pipe and its enclosing pipeline | `readFromPipelineContext` | The pipeline's `contextWindow` and `miniBank`. |
| Pass state between a pipe and its enclosing pump station | `readFromPumpStationContext` | The pump station's `contextWindow` and `miniBank`. |
| Pass developer scratch state between arbitrary pipes | **Meta-page-keys (this pattern)** | `MetadataBank` page-keyed scratchpad, no relationship requirement. |
| Pass intermediate state in a single execution | `MultimodalContent.metadata` or local variables | Data-carrier surface or local scope; no bank involvement. |

## Lifecycle: From Setter to Auto-Pull

The end-to-end lifecycle of a meta-page-keys pull:

1. **Configuration.** A developer calls `pipe.setMetaPageKeys("page1, page2")` or `pumpStation.setMetaPageKeys("page1, page2")` at build time. The setter records the glued string on the instance.
2. **Population.** A writer — anywhere in the JVM — calls `MetadataBank.setMeta("page1", ...)` or `MetadataBank.emplace("page1", ...)`. The page becomes visible to all readers.
3. **Runtime trigger.** `Pipe.execute*()` fires. Inside the runtime, after the existing `readFromGlobalContext` block, the runtime evaluates `pipe.hasMetaPageKeys()`. Inside `readFromPumpStationContext`, the runtime walks up to the nearest pump station parent and evaluates `pumpStation.hasMetaPageKeys()`.
4. **Pull.** If the boolean is true, the runtime invokes `pipe.pullMetaPageKeysIntoPipeMetadata()` or `pumpStation.pullMetaPageKeysIntoPumpStationMetadata()`. The bank primitive parses the glued string, reads each page, and `putAll`-merges into the destination bag.
5. **Use.** The destination bag is now populated. The dev can read `pipe.pipeMetadata["key"]` or `pumpStation.metadata["key"]` in subsequent code paths within the same execution.

The dev does not need to call the pull method explicitly. Calling it manually still works (the method is public) but is unnecessary under normal flow.

## Cross-Boundary Scenarios

The pattern is designed for cross-boundary coordination, not for single-pipe use. Three scenarios account for most real-world deployments.

### Scenario 1: Manager to Worker Coordination

The manager pipe writes workflow state to a named page. A worker pipe reads it at execute-time.

```kotlin
import com.TTT.Context.MetadataBank
import com.TTT.Pipe.Pipe

// Manager-side, in the manager's pipe:
MetadataBank.emplace("workflow.coordination", mapOf<Any, Any>(
    "ticketId" to "TICKET-1234",
    "phase" to "research"
))

// Worker-side configuration:
val researchWorker = ResearchPipe()
    .setMetaPageKeys("workflow.coordination")

// Inside researchWorker.execute(), the worker reads its own metadata:
// val ticket = researchWorker.pipeMetadata["ticketId"]  // "TICKET-1234"
```

The manager and worker do not need to share a parent. They agree on the page name `workflow.coordination` and the bank is the rendezvous.

### Scenario 2: Pump Station Carrying Global State

The pump station writes a global-state page once. Every pipe dispatched from the station reads it at execute-time via the `readFromPumpStationContext` block.

```kotlin
import com.TTT.Context.MetadataBank
import com.TTT.Pipeline.PumpStation

// Set up at workflow start:
MetadataBank.setMeta("workflow.global_state", mapOf<Any, Any>(
    "tenant" to "tenant-acme",
    "tier" to "production"
))

// Station config:
val station = PumpStation()
    .setDispatchAgent(workflowPipeline)
    .setMetaPageKeys("workflow.global_state")

// Every pipe in workflowPipeline can read tenant and tier via the station's metadata.
// Pipes do not need setMetaPageKeys themselves — the runtime auto-pulls from the
// station when readFromPumpStationContext fires.
```

The station's auto-pull fires once per `Pipe.execute*()` of a dispatched pipe. Each dispatched pipe sees the same global state because the bank is process-singleton.

### Scenario 3: Multi-Page Bulk Pull

A pipe pulls from multiple pages in one configuration call. Pages are merged in order, with last-write-wins on collision.

```kotlin
import com.TTT.Context.MetadataBank
import com.TTT.Pipe.Pipe

MetadataBank.setMeta("apex.flow_state", mapOf<Any, Any>(
    "lastTool" to "search",
    "step" to 3
))
MetadataBank.setMeta("workflow.global_state", mapOf<Any, Any>(
    "ticketId" to "TICKET-1234",
    "step" to 1  // collision with apex.flow_state.step
))

val tool = MyToolPipe().setMetaPageKeys("apex.flow_state, workflow.global_state")

// Inside tool.execute(), tool.pipeMetadata contains:
//   "lastTool" -> "search"   (from apex.flow_state)
//   "ticketId" -> "TICKET-1234"  (from workflow.global_state)
//   "step"     -> 1          (workflow.global_state wins, parsed last)
```

The bulk-pull order is the order of the parsed key list, which is the order in the glued string. Developers who need a specific collision winner should put the winning page last.

## Common Mistakes

**Mistake 1 — Treating the bank as LLM context.** The bank's contents do not flow into the prompt. Developers who stash tool-output in the bank expecting the model to see it will be surprised; that goes in the `contextWindow` via `ContextBank` pull patterns.

**Mistake 2 — Hand-calling the pull method when the boolean signal suffices.** The runtime auto-pulls. Calling `pipe.pullMetaPageKeysIntoPipeMetadata()` manually works but is unnecessary; the runtime will fire it anyway at execute-time.

**Mistake 3 — Using the bank for one-shot intermediate state.** The bank outlives the JVM. Putting single-execution intermediate values in the bank leaks memory. Use `MultimodalContent.metadata` or local variables for transient state.

**Mistake 4 — Relying on the active-page pointer for runtime reads.** The pointer can go stale if a page is deleted. Use `getMetaSuspend` for runtime reads; use `getActiveMetaSuspend` only when you specifically want the most recently promoted page.

**Mistake 5 — Setting `metaPageKeys` on `MultimodalContent` or `ContextWindow`.** These are data carriers. Their `metadata` and `metaData` fields are populated by other system controls, not by the bank. The surface is not exposed on them.

**Mistake 6 — Computing page keys dynamically.** The pattern takes a static glued-string. If the key list is computed at runtime, call `MetadataBank.pullMetaPageKeysInto` directly instead.

**Mistake 7 — Expecting the bank to persist across JVM restarts.** The bank evaporates with the JVM. Use `ContextBank` with a non-memory `StorageMode` for persistent state.

## API Surface Reference

The full API surface, including every public method signature, parameter, return type, and runtime trigger location, is documented in [MetadataBank and Meta-Page-Keys Pull — API Reference](metadata-bank.md).