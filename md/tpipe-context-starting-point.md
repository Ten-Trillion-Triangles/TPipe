# TPipe Context Starting Point

Purpose: preserve the current mental model of TPipe before compaction. This is a compact, source-backed orientation doc for the codebase as it exists now.

## Source Map

- Product overview and terminology: [`README.md`](../README.md)
- Pipe implementation: [`src/main/kotlin/Pipe/Pipe.kt`](../src/main/kotlin/Pipe/Pipe.kt)
- Pipeline orchestration: [`src/main/kotlin/Pipeline/Pipeline.kt`](../src/main/kotlin/Pipeline/Pipeline.kt)
- Global memory bank: [`src/main/kotlin/Context/ContextBank.kt`](../src/main/kotlin/Context/ContextBank.kt)
- Local memory window: [`src/main/kotlin/Context/ContextWindow.kt`](../src/main/kotlin/Context/ContextWindow.kt)
- Multi-page memory container: [`src/main/kotlin/Context/MiniBank.kt`](../src/main/kotlin/Context/MiniBank.kt)
- Container classes: [`src/main/kotlin/Pipeline/`](../src/main/kotlin/Pipeline/)
- Core concept docs: [`docs/core-concepts/`](../docs/core-concepts/)
- Container docs: [`docs/containers/`](../docs/containers/)
- API docs: [`docs/api/`](../docs/api/)

## Big Picture

TPipe is an agent operating environment built around deterministic control of AI workflows. The main runtime ideas are:

- `Pipe` is the atomic execution unit, responsible for preparing prompts, pulling context, running model/provider logic, and handling validation/tracing/hooks.
- `Pipeline` is a mutable sequential orchestrator that chains multiple pipes together.
- `ContextWindow` is the local memory reservoir for one pipe or pipeline.
- `ContextBank` is the global shared memory store used across pipes, pipelines, and remote memory modes.
- `MiniBank` is a page-keyed bundle of multiple `ContextWindow` objects for multi-context scenarios.
- Containers such as `Connector`, `Splitter`, `MultiConnector`, and `Manifold` add higher-level routing and orchestration patterns on top of pipelines.

The repo consistently treats `Pipe` and `Pipeline` as mutable orchestration objects. The docs and source both imply a best practice of creating fresh instances for concurrent top-level runs instead of sharing one instance across simultaneous executions.

## Core Concepts

### Pipe

`Pipe` is the fundamental abstraction for a single model call or tool-assisted execution step.

From the source:

- It implements `P2PInterface` and `ProviderInterface`.
- It owns prompt configuration, context pulling, truncation, validation hooks, tracing, token tracking, and container delegation.
- It can pull from local context, pipeline context, parent pipe context, or the global `ContextBank`.
- It can inject a `ContextWindow` or `MiniBank` into the outgoing prompt automatically.
- It can forward execution to another container through `containerPtr`.

Important behaviors:

- Context loading happens before execution and can come from pipeline state, parent pipe state, or global memory.
- Pre-validation hooks can mutate either a `ContextWindow` or a `MiniBank` before prompt assembly.
- Token budgeting and truncation are layered on top of prompt construction and can override legacy truncation behavior.
- If multiple page keys are in use, the pipe serializes and injects `MiniBank` rather than a single `ContextWindow`.
- Pipe execution is traced with phase-based events when tracing is enabled.

Source-backed details live mainly in [`Pipe.kt`](../src/main/kotlin/Pipe/Pipe.kt).

### Pipeline

`Pipeline` chains multiple pipes in order and manages shared execution state.

From the source:

- It is mutable and stores a list of pipes.
- It tracks shared `ContextWindow`, `MiniBank`, token totals, pause/resume state, tracing, and P2P metadata.
- It can bind to global context and write results back into `ContextBank`.
- It supports conversation-history wrapping, pipe-level and pipeline-level callbacks, pause points, and retry/timeout configuration.

Important execution behavior:

- Pipes run sequentially.
- A pipe can jump or terminate flow via content metadata and pipeline state.
- At completion, the pipeline can write the resulting context back to the global bank.
- If `updateGlobalContext` is enabled, the pipeline persists either a single `ContextWindow` or a `MiniBank` page set depending on the configured page keys.
- The pipeline also acts as a `P2PInterface`, exposing its pipes and handling remote requests.

Source-backed details live mainly in [`Pipeline.kt`](../src/main/kotlin/Pipeline/Pipeline.kt).

## Memory System

### ContextWindow

`ContextWindow` is the local memory structure that a pipe or pipeline carries around.

It stores:

- `loreBookKeys`: keyed lore entries used for weighted, rule-driven context selection
- `contextElements`: raw string context
- `converseHistory`: structured conversation history
- `version`: used for synchronization and remote memory consistency
- transient metadata for runtime-only system state

The key behaviors are:

- It can find matching lorebook keys by scanning user text and aliases.
- It can respect key dependencies and linked keys.
- It can select lorebook content by weight and token budget.
- It can merge with another `ContextWindow` with configurable overwrite/append/history rules.
- It acts as the primary local memory unit that gets injected into prompts.

Source-backed details live mainly in [`ContextWindow.kt`](../src/main/kotlin/Context/ContextWindow.kt).

### ContextBank

`ContextBank` is the singleton global memory system.

The current implementation is more than a simple map:

- It stores named `ContextWindow` pages in a `ConcurrentHashMap`.
- It stores named `TodoList` pages separately.
- It maintains a currently active banked context window.
- It provides mutexes for bank access, swaps, todo operations, and cache policy enforcement.
- It supports retrieval and write-back callbacks.
- It supports remote memory delegation through `MemoryClient`.
- It supports multiple storage modes, including memory-only, memory-and-disk, disk-only, disk-with-cache, and remote.

Important behaviors:

- `getContextFromBankSuspend` is the main safe retrieval path.
- `emplaceSuspend` and `emplaceWithMutex` are the preferred write paths for coroutine and concurrent code.
- Retrieval can return a deep copy by default for safety.
- Page locks via `ContextLock` can suppress visibility for locked pages.
- Disk-backed data can be loaded on demand and cached depending on storage mode.
- Cache eviction uses configured policies and tracks access metadata.

Source-backed details live mainly in [`ContextBank.kt`](../src/main/kotlin/Context/ContextBank.kt).

### MiniBank

`MiniBank` is the multi-page memory wrapper.

It is intentionally small:

- a `MutableMap<String, ContextWindow>` keyed by page key
- merge logic that delegates into each contained `ContextWindow`
- `isEmpty()` and `clear()` helpers

The role of `MiniBank` is to preserve separation between distinct context domains while still allowing a pipe or pipeline to inject them together. It is used whenever multiple page keys are active at once.

Source-backed details live in [`MiniBank.kt`](../src/main/kotlin/Context/MiniBank.kt).

## How The Memory Pieces Fit Together

The runtime memory flow is:

1. A pipe or pipeline starts with local state in `ContextWindow` and possibly `MiniBank`.
2. If global memory is enabled, the runtime pulls data from `ContextBank`.
3. If one page key is active, the system behaves like a single `ContextWindow`.
4. If multiple page keys are active, the system upgrades to a `MiniBank`.
5. During execution, context can be pre-validated, truncated, merged, and re-injected.
6. After execution, pipelines may write updated context back to `ContextBank`.

Key detail: `ContextBank` is the shared source of truth, but `ContextWindow` and `MiniBank` are the working copies that get composed into prompts and later written back.

## Container Classes

### Connector

`Connector` is a conditional router from one input key to one of many pipelines.

Observed behavior from source:

- It stores a map of path keys to pipelines.
- `execute(path, content)` routes to the selected pipeline.
- Invalid paths do not throw by default; they mark the content as terminated.
- It exposes child pipelines through `getPipelinesFromInterface()`.
- It supports a default path for P2P requests.
- It can enable tracing and forward trace collection to child pipelines.

Good fit:

- branch by document type
- route by activation key
- pick one of several downstream pipelines

### Splitter

`Splitter` fans one content input out to many pipelines in parallel and gathers results.

Observed behavior from source:

- It holds activation keys that pair one input content object with one or more pipelines.
- It executes pipelines concurrently and stores outputs in a `MultimodalCollection`.
- It supports callbacks for per-pipeline completion and all-pipelines completion.
- It supports tracing and child trace correlation.

Good fit:

- parallel analysis
- multi-model consensus collection
- concurrent specialized processing

### MultiConnector

`MultiConnector` composes multiple connectors for more complex routing behavior.

Observed behavior from docs/source:

- It supports sequential, parallel, and fallback execution modes.
- It requires explicit routing paths.
- It exposes child pipelines through P2P.
- It is a higher-order routing layer above `Connector`.

Good fit:

- nested routing chains
- fallback processing
- multiple branching layers

### Manifold

`Manifold` is the manager-worker orchestration container.

Observed behavior from source and docs:

- It coordinates a manager pipeline plus one or more worker pipelines.
- It tracks shared task progress using `TaskProgress`.
- It uses conversation history and P2P agent descriptors to drive worker selection.
- It supports validation, failure, and transformation hooks.
- It supports tracing and pause/resume behavior.
- It can be built manually or through the DSL in `ManifoldDsl.kt`.
- It is TPipe's agent harness and the best source of truth for later Junction design.

Good fit:

- multi-agent task delegation
- manager-driven iterative workflows
- human-in-the-loop control

### DistributionGrid

`DistributionGrid` is currently only partially implemented.

Current source status:

- the data classes `DistributionGridTask` and `DistributionGridJudgement` exist
- `setEntryPipeline()` validates that one pipe emits the required JSON schema
- the execution loop, agent routing, judge logic, and token management are not implemented yet

Treat this as a structural placeholder, not a finished orchestration runtime.

Keep these DistributionGrid records handy:

- [`md/distributiongrid-design.md`](./distributiongrid-design.md) for the long-lived design intent and guardrails
- [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) for the living implementation state and evidence
- [`md/distributiongrid-plan.md`](./distributiongrid-plan.md) for the single current task and exact short-horizon progress

### Junction

`Junction` is now a real harness.

It started as a discussion/voting container, but it now supports both the original discussion flow and the planned workflow recipes that chain plan, vote, act, verify, adjust, and output phases.

Keep these Junction records handy:

- [`md/junction-harness-requirements.md`](./junction-harness-requirements.md) for the discussion-harness contract
- [`md/junction-harness-implementation-tracker.md`](./junction-harness-implementation-tracker.md) for the completed discussion rollout
- [`md/junction-workflow-extension-requirements.md`](./junction-workflow-extension-requirements.md) for the workflow extension contract
- [`md/junction-workflow-extension-tracker.md`](./junction-workflow-extension-tracker.md) for the workflow extension status

## P2P And Tracing

### P2P

Most major runtime classes implement `P2PInterface`.

What that means in practice:

- objects can advertise descriptors, transport, and requirements
- pipelines can be exposed as addressable units
- remote requests are turned back into local `MultimodalContent` execution
- containers can surface their child pipelines for discovery

### Tracing

Tracing is a first-class cross-cutting concern.

What the source shows:

- `Pipe`, `Pipeline`, `Connector`, `Splitter`, and `Manifold` all carry tracing state
- trace IDs are generated per instance
- trace config is propagated to children in container classes
- trace events capture phase, metadata, content snapshots, and errors

Tracing is not just debug logging. It is part of the operational model for understanding orchestration, context flow, and failures.

## PCP And P2P

TPipe uses two different protocol layers that are easy to confuse:

- `PCP` is the tool-calling layer inside a pipe
- `P2P` is the agent/pipeline routing layer across runtime containers

The short version:

- PCP answers "what tools may this LLM call?"
- P2P answers "what agent or pipeline should receive this request?"

### PCP

PCP, or Pipe Context Protocol, is TPipe's unified function-calling system.

What it does:

- attaches a declared tool context to a `Pipe`
- serializes allowed actions into the system prompt
- parses model replies for tool requests
- validates and dispatches tool calls through transport-specific executors

The main runtime pieces are:

- `PcpContext`: declared tool availability and security settings
- `PcPRequest`: the tool-call payload produced by the model
- `PcpResponseParser`: extracts and validates PCP calls from an LLM response
- `PcpExecutionDispatcher`: routes validated requests to the correct executor

Supported transport families are:

- `Stdio` for shell and CLI execution
- `Http` for API requests
- `Python` for script execution
- `Kotlin` for JVM-side scripting
- `JavaScript` for Node.js execution
- `Tpipe` for native TPipe function calls

Important behavior:

- a pipe enables PCP by attaching a `PcpContext`
- `pipe.execute(...)` and `pipe.generateText(...)` serialize PCP instructions into the prompt
- `Pipe.processPcpResponse(...)` is the handoff point that parses the raw model output and executes any requested tool calls
- the dispatcher enforces the configured context and security rules before executing anything

### Merged PCP + JSON

PCP can coexist with structured JSON output.

The merged mode is active when:

- PCP tools are configured
- JSON output is also configured through the JSON prompt-injection path

In merged mode:

- JSON output is required
- PCP tool calls are optional
- the model may return both in one response

This matters because it removes the older ambiguity between "return only JSON" and "return tool calls".

### P2P

P2P, or Pipe-to-Pipe, is the routing and discovery layer for TPipe agents and containers.

What it does:

- advertises a pipeline or container as an addressable agent
- carries descriptor, transport, and requirement metadata
- validates whether a request may enter the agent
- routes the request to local in-process execution or to a remote transport

The main runtime pieces are:

- `P2PInterface`: shared contract for P2P-capable objects
- `P2PDescriptor`: public capability description
- `P2PRequirements`: private or stricter request validation rules
- `P2PTransport`: transport target and address
- `P2PRequest`: full request payload for inter-agent calls
- `AgentRequest`: simplified LLM-facing request form
- `P2PRegistry`: global registry, template store, and dispatcher

Important behavior:

- `P2PInterface` is what lets a pipeline or container participate in agent routing
- `executeP2PRequest(...)` is the agent-facing execution path
- `executeLocal(...)` bypasses the P2P layer and runs the object directly
- `P2PRegistry.register(...)` stores the agent and its validation rules under a transport key
- `P2PRegistry.loadAgents(...)` loads external descriptors for LLM-facing discovery

### P2P Request Model

The full `P2PRequest` can carry:

- a transport target and return address
- multimodal prompt content
- auth data
- optional external context
- custom context descriptions
- an embedded PCP request
- optional custom input and output schemas

The simplified `AgentRequest` is the LLM-friendly version.

Its job is to:

- name the target agent
- carry the human-readable prompt and content
- optionally carry PCP data
- expand into a full `P2PRequest` using a registry template when needed

### P2P Validation Rules

`P2PRequirements` controls whether a request is allowed to reach an agent.

Important gates include:

- converse-format input requirements
- duplication requirements
- custom context permission
- custom JSON permission
- external-connection permission
- accepted content types
- token and binary-size budgets
- optional auth callback

The practical effect is that descriptor data is public-facing, while requirements are the stricter runtime gate.

### PCP vs P2P

Use PCP when the model needs to invoke tools inside one pipe.

Use P2P when one pipeline or container needs to expose itself as a callable agent to another pipeline, container, or external caller.

That distinction matters because:

- PCP is about tool execution within a pipe lifecycle
- P2P is about discovery, routing, and agent-level execution
- PCP responses are parsed from model output
- P2P requests are routed through registry and transport metadata

## Manifold Harness

Manifold is TPipe's agent harness: a manager pipeline coordinates worker pipelines through shared conversation state, P2P registration, and a repeat-until-complete orchestration loop.

### Core Harness Model

The runtime revolves around three pieces:

- `managerPipeline`: decides what happens next and emits `TaskProgress` or `AgentRequest`
- `workerPipelines`: specialized pipelines that perform the delegated work
- `workingContentObject`: the shared multimodal payload that carries conversation history and task state

Supporting state includes:

- `currentTaskProgress`: task status snapshot
- `agentPaths`: LLM-facing `AgentDescriptor` list built from local workers
- `agentPipeNames`: manager pipe names that should receive the worker agent list
- tracing state, loop counters, and pause/resume state

### Setup Lifecycle

The harness is normally assembled in this order:

1. `setManagerPipeline(...)` assigns the manager pipeline and its P2P registration metadata
2. `addWorkerPipeline(...)` adds worker pipelines and registers each one locally
3. `init()` validates that the manager can actually emit `AgentRequest` JSON
4. `init()` collects local worker descriptors and injects them into the manager pipe(s) that handle agent dispatch
5. `init()` validates worker overflow protection and manager-history handling
6. `init()` propagates tracing and binds the manifold as the container object for manager and workers

Important setup rules:

- manager and worker pipelines are reused across loop iterations
- the manager defaults to secure local-only P2P settings unless custom descriptor/requirements are supplied
- worker defaults also remain local-only and converse-based
- every worker must have overflow protection before the manifold can start
- the manager must have at least one pipe capable of producing `AgentRequest`

### Execution Loop

`execute(content)` is the harness runtime.

The flow is:

1. require the manager pipeline to have P2P support
2. initialize tracing and bootstrap `ConverseHistory` if needed
3. apply manager-history truncation or custom context truncation
4. execute the manager pipeline
5. stop early if `TaskProgress.isTaskComplete` says the task is done
6. validate manager output if a validator is configured
7. parse the manager result into `AgentRequest`
8. strip accidental PCP tool calls from the manager request
9. update shared conversation history with the manager decision
10. dispatch the worker request through `P2PRegistry.sendP2pRequest(...)`
11. validate and optionally transform the worker response
12. merge the worker response back into the shared content and repeat

The loop exits when:

- the manager sets `passPipeline`
- the content sets `terminatePipeline`
- task progress reports completion
- a validation or P2P failure cannot be recovered

### Control Hooks

Manifold exposes a few key control points that matter for harness behavior:

- `setManagerTokenBudget(...)` and `autoTruncateContext()` control manager shared-history size
- `setContextTruncationFunction(...)` lets the caller replace built-in history truncation
- `setValidatorFunction(...)` runs after manager or worker turns and can reject bad output
- `setFailureFunction(...)` is the recovery branch when validation fails
- `setTransformationFunction(...)` runs after successful worker output and can rewrite content
- `pause()` and `resume()` suspend and release the loop for human intervention

The default DITL-style workflow is intentionally manager-centric:

- validation is about checking the worker/manifold output
- failure handling is a last-chance recovery path
- transformation is the place to reshape result content before it is written back into history

### P2P Registration Model

Manifold uses P2P metadata to make the manager and workers addressable inside the registry.

What happens in practice:

- the manager pipeline gets a `P2PDescriptor`, `P2PTransport`, and `P2PRequirements`
- each worker pipeline gets its own descriptor, transport, and requirements
- `P2PRegistry.register(...)` stores each pipeline under its transport key
- `P2PRegistry.listLocalAgents(this)` is used during `init()` to build the worker agent list for the manager
- the manager pipe that performs dispatch receives the worker `AgentDescriptor` list and an updated system prompt

The important security model is:

- manager and workers are local to the manifold by default
- external access is denied unless a descriptor or requirement explicitly opens it
- the default manager setup is converse-aware and restricted to text content

### DSL Surface

The `ManifoldDsl` builder is the public ergonomics layer on top of the harness.

It provides:

- `manager { }` for the manager pipeline
- `worker("name") { }` for each worker
- `history { }` for shared-history truncation policy
- `validation { }` for validator/failure/transformation hooks
- `tracing { }` for trace enablement and config

The DSL is not a separate runtime. It validates configuration early, applies it through the public `Manifold` API, and returns a startup-ready manifold after calling `init()`.

### Why This Matters For Junction

Manifold is the reference point for future harness-style orchestration.

It shows how TPipe already handles:

- multi-agent registration
- shared task state
- structured manager decisions
- worker delegation through P2P
- failure recovery and human intervention
- loop termination and pause/resume control

That makes it the conceptual bridge to `Junction`, which will need to coordinate multiple participants without losing the same harness-level guarantees.

## Developer-In-The-Loop

DITL in TPipe means developer-controlled intervention points inside pipe execution. There are two forms:

- code-based DITL functions on `Pipe`
- AI-powered DITL pipes that act as validator, transformer, or recovery steps

The code path is the one the runtime actually executes first. The docs for AI-powered DITL pipes describe higher-level alternatives that plug into the same lifecycle.

### Code-Based DITL Order

The execution order in `Pipe` is:

1. `preInitFunction`
2. context loading from global, pipeline, parent, or local state
3. `preValidationFunction` or `preValidationMiniBankFunction`
4. `preInvokeFunction`
5. optional reasoning pipe execution and reasoning injection
6. AI generation
7. `postGenerateFunction`
8. validator pipe, if configured
9. `validatorFunction`
10. transformation pipe, if configured
11. `transformationFunction`
12. failure handling and exception handling if validation or execution fails

### What Each Hook Is For

- `preInitFunction`: first chance to sanitize or reject input before context is touched
- `preValidationFunction`: adjust a single `ContextWindow` after it is loaded
- `preValidationMiniBankFunction`: same idea, but for multi-page `MiniBank` context
- `preInvokeFunction`: final skip/short-circuit check before the LLM call
- `postGenerateFunction`: earliest safe place to inspect raw model output
- `validatorFunction`: code-based accept/reject gate after generation
- `transformationFunction`: code-based output shaping after validation
- `exceptionFunction`: captures exceptions from the generation path
- `setOnFailure(...)`: handles validation failure when no branch pipe is used

### AI-Powered DITL Pipes

The AI-based versions follow the same conceptual slots:

- validator pipe runs before code validation and only its termination state matters
- transformation pipe runs after successful validation and overrides the code transform path
- branch pipe runs on validation failure and overrides `onFailure`

Important detail: validator pipe output is discarded except for its termination flag. The original generated content continues into the rest of the pipeline.

### Retry And Safety Rules

Timeout retry re-executes the pipe from the beginning, so pre-execution DITL functions must be read-only or idempotent. In practice that means:

- avoid writing to `ContextBank` in `preInitFunction`, `preValidationFunction`, or `preValidationMiniBankFunction`
- prefer writes in `postGenerateFunction` or transformation stages
- treat retries as a replay of the full pre-execution path, not a partial resume

### Pipeline-Level DITL

`Pipeline.setPreValidationFunction(...)` runs before any pipe executes. It is separate from pipe-level DITL and is used to prepare shared pipeline context or reject an input before the first stage begins.

## Pipeline Deep Dive

`Pipeline` is the mutable orchestrator that moves content through a sequence of pipes. Its job is to manage flow, shared context, tracing, pause/resume, and the final writeback path.

### Pipeline State

The important pipeline-owned state is:

- `pipes`: the ordered execution list
- `context`: the pipeline-local `ContextWindow`
- `miniBank`: the pipeline-local `MiniBank`
- `content`: the last processed `MultimodalContent`
- `pipelineTokenUsage`: aggregated token tracking when enabled
- `lastError` and `lastFailedPipe`: failure capture for the most recent run
- `pipelineContainer`: optional parent container reference
- `pipelineName`: human-readable identifier for tracing and debugging

This state belongs to the pipeline, not to any one pipe.

### Execution Flow

Pipeline execution is sequential and stateful:

1. optional pipeline pre-validation runs before any pipe
2. the input content becomes the working content
3. token tracking is reset for the run
4. the pipeline iterates through pipes using `getNextPipe(...)`
5. each pipe executes and may mutate content, context, or flow flags
6. pipe completion callbacks run after each stage
7. repeat loops re-run the same pipe when `repeatPipe` is set
8. the pipeline exits early if content requests termination or pass-through
9. after completion, global context is written back if enabled

### Flow Control

Flow control is driven by the `MultimodalContent` object:

- `jumpToPipe(...)` redirects execution to a named pipe or next pipe
- `repeat()` re-executes the current pipe
- `terminate()` ends the pipeline as a failure
- `passPipeline = true` ends the pipeline successfully

`getNextPipe(...)` resolves either the current index or a jump target, so jump behavior is a first-class part of the execution loop rather than a side channel.

### Pause And Resume

The pipeline supports declarative human-in-the-loop pauses:

- `pauseBeforePipes()`
- `pauseAfterPipes()`
- `pauseBeforeJumps()`
- `pauseAfterRepeats()`
- `pauseOnCompletion()`
- `pauseWhen(...)`

Runtime pause control uses `pause()` and `resume()`, with callbacks for pause/resume transitions. Pausing is orchestration control, not DITL, and is separate from validation or transformation hooks.

### Tracing, Errors, And Tokens

Pipeline tracing records orchestration events, pipe completion, pauses, resumes, and failures. The key runtime outputs are:

- `getTraceReport(...)` for the full trace export
- `getFailureAnalysis()` for trace-derived diagnostics
- `getTokenCount()` and aggregated token usage for performance visibility
- `hasError()`, `getErrorMessage()`, and `getFailedPipeName()` for failure state

When child pipes track tokens comprehensively, the pipeline aggregates those values into its own totals.

### Context Writeback

Pipeline context flows in and out through the shared bank:

- `useGlobalContext(page)` enables global writeback on exit
- `setContextWindow(...)` and `setMiniBank(...)` seed local pipeline context safely
- if `updateGlobalContext` is enabled, the pipeline writes back either a single `ContextWindow` or page-keyed `MiniBank` data
- page keys control whether the pipeline writes one shared page or multiple named pages

This makes the pipeline the boundary between temporary execution state and persistent shared memory.

## Current State Notes

- The repository is in a strong “working core + some stubbed advanced containers” state.
- `Pipe`, `Pipeline`, `ContextBank`, `ContextWindow`, `MiniBank`, `Connector`, `Splitter`, `MultiConnector`, and `Manifold` are real implementation surfaces.
- `DistributionGrid` is still a placeholder, but `Junction` is now a full runtime harness with discussion and workflow recipes.
- The docs are broad and sometimes describe aspirational behavior, so source files should be treated as the final word for anything operationally important.

## Practical Takeaways

- Use `Pipe` for one execution step and `Pipeline` for sequential orchestration.
- Use `ContextWindow` for local context and `ContextBank` for global/shared memory.
- Use `MiniBank` when you need multiple page keys preserved independently.
- Use `Connector` for one-key branching, `Splitter` for fan-out, `MultiConnector` for nested routing, and `Manifold` for manager-worker loops.
- Do not rely on `DistributionGrid` as production-ready execution container yet.
- Use `Junction` when you need a P2P-aware harness that can handle discussion-only or plan/vote/act/verify/adjust/output recipes.
