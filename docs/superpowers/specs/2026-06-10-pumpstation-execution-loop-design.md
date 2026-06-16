# PumpStation Execution Loop Design

**Date:** 2026-06-10
**Status:** Approved (Sections 1-5)
**Scope:** Implementation of the `executeLocal()` harness loop in `PumpStation.kt`

## Context

`PumpStation` is the agentic-loop container in TPipe — the harness that drives a judge/dispatch/path cycle over multiple turns. The class has all the configuration scaffolding in place (agents, DITL hooks, events, loop guards, memory modes, pause/resume, snapshot/restore) but the runtime loop body that orchestrates these is unwritten. `executeLocal()` currently does a partial health check then returns the input as-is (PumpStation.kt:1444-1445 has the TODO marker).

This design specifies exactly what the loop must do, in what order, with what data flowing at each phase boundary, and what scaffolding is missing to make it work.

## Design Philosophy

**PumpStation is "quick and dirty" — auto-inject what the developer doesn't supply.** Unlike `Manifold` (which has "magic contracts" the developer must remember and inject into their agents), PumpStation:

- Auto-injects default system prompts when the developer doesn't supply custom ones
- Reads standard `MultimodalContent` flags (`terminatePipeline`, `passPipeline`, `interuptPipeline`) for loop control — no magic contracts
- Auto-invokes builder functions per turn so fresh, thread-safe agents are created when needed
- Provides sensible defaults for every config field; the developer overrides only what they need

**The pipe class's prompt injection system is the primary mechanism for prompt composition.** `enableHarnessMode()` auto-injects the path descriptor protocol. `setSystemPrompt()` + `applySystemPrompt()` handle the layered injection. The pump station just orchestrates the pipe's existing capabilities.

**`MultimodalContent` flags are the canonical control pattern TPipe developers are taught early.** The loop leans on these flags as much as possible.

**Two-tier history management:** `turnHistory` is the curated, compacted, agent-facing history. `rawTurnHistory` is the full, append-only event log used by the goal agent, DITL hooks, and developers.

## Section 1: Architecture Overview

### Main Loop Structure

```
runHarnessLoop()
├── runPreInitPhase(content)         ← ONCE at start
├── while (turnIndex < maxTurns) {
│   ├── checkPauseGuards(BeforeJudge)
│   ├── runTurn()                     ← returns Continue | Halt(reason)
│   └── turnIndex++
}
└── runFinalizationPhase()           ← ONCE at end
```

### `runTurn()` Body

```kotlin
private suspend fun runTurn(): TurnResult {
    refreshAgentInstances()      // R.1
    refreshPipelinesPrompts()    // R.2
    refreshSettingsPropagation() // R.3

    runHealthCheckPhase()
    detectAndHandleContextBlowout(PumpStationPhase.HealthCheck)

    val judgeVerdict = runJudgePhase()
    detectAndHandleContextBlowout(PumpStationPhase.Judge)
    if (judgeVerdict.shouldHalt) return TurnResult.Halt(judgeVerdict.reason)
    if (judgeVerdict.isComplete) return runExitFlow()

    val pathRequest = runDispatchPhase() ?: return TurnResult.Continue
    detectAndHandleContextBlowout(PumpStationPhase.Dispatch)
    if (pathRequest.pathName.isBlank()) return TurnResult.Continue

    if (!checkPauseGuards(PumpStationPausePhase.BeforePathExecution)) {
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    runPathFlow(pathRequest)
    detectAndHandleContextBlowout(PumpStationPhase.PathExecution)

    runForegroundAgentsPhase()
    detectAndHandleContextBlowout(PumpStationPhase.ForegroundAgents)

    runBackgroundAgentsPhase()
    runMemoryUpdatePhase()
    runCompactionPhase()

    return TurnResult.Continue
}
```

### Per-Turn Refresh (R.1, R.2, R.3)

**Why per-turn, not just PreInit:** Lazy-loaded paths, reserve path reveals, todo list updates, builder function results, and memory agent outputs all change during a turn. The dispatch pipe's `applySystemPrompt()` reads the current state — so the loop must call it every turn to refresh.

**R.1 — Refresh Agent Instances:**
```kotlin
private fun refreshAgentInstances() {
    judgeAgentBuilderFunction?.let { fn ->
        judgeAgent = fn(this)
        judgeAgent?.setParentInterface(this)
        judgeAgent?.P2PInit()
    }
    dispatchAgentBuilderFunction?.let { fn ->
        dispatchAgent = fn(this)
        dispatchAgent?.setParentInterface(this)
        dispatchAgent?.P2PInit()
    }
}
```

**R.2 — Refresh Pipeline Prompts:**
```kotlin
private fun refreshPipelinesPrompts() {
    applyPromptsToPipeline(judgeAgent, buildJudgeSystemPrompt(), buildJudgeFooter())
    applyPromptsToPipeline(dispatchAgent, buildDispatchSystemPrompt(), buildDispatchFooter())
    if (goalAgent is Pipeline) applyPromptsToPipeline(goalAgent as Pipeline, buildGoalSystemPrompt(), null)
}

private fun applyPromptsToPipeline(agent: Pipeline?, customPrompt: String?, customFooter: String?) {
    if (agent == null) return
    for (pipe in agent.getPipes()) {
        pipe.setSystemPrompt(customPrompt ?: defaultPromptFor(agent))
        pipe.setFooterPrompt(customFooter ?: defaultFooterFor(agent))
        if (agent == dispatchAgent) pipe.enableHarnessMode()
        pipe.applySystemPrompt()  // refreshes layered injections
    }
}
```

**R.3 — Refresh Settings:** calls existing `propagateSettingsToAllAgents()`.

### Stage 1: Pre-Initialization

```kotlin
private suspend fun runPreInitPhase(content: MultimodalContent) {
    taskState.originalInput = content
    taskState.latestContent = content
    taskState.status = PumpStationStatus.Running
    taskState.phase = PumpStationPhase.PreInit

    refreshAgentInstances()      // initial builder-function invocation
    refreshPipelinesPrompts()    // initial prompt application
    refreshSettingsPropagation() // initial budget/pipe settings

    if (preInitAgent != null) taskState.latestContent = preInitAgent!!.executeLocal(content)
    if (preInitFunction != null) taskState.latestContent = preInitFunction!!.invoke(content, this)

    emit(HarnessStarted(runId, turnIndex = 0, originalInput = content))
    taskState.phase = PumpStationPhase.Judge
}
```

### Stage 3: Exit Flow

```kotlin
private suspend fun runExitFlow(): TurnResult {
    if (!checkPauseGuards(PumpStationPausePhase.BeforeGoalValidation)) {
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    if (goalAgent == null) return TurnResult.Halt(PumpStationExitReason.JudgeComplete)

    val agent = goalAgentBuilderFunction?.invoke(this) ?: goalAgent!!
    agent.setParentInterface(this)
    agent.P2PInit()
    refreshPipelinesPrompts()

    emit(GoalValidationStarted(...))
    val goalContent = buildGoalContent()
    val result = agent.executeLocal(goalContent)
    emit(GoalValidationCompleted(...))

    if (result.terminatePipeline) {
        turnHistory.add(ConverseData(role = ConverseRole.assistant, content = result))
        taskState.goalFailCount++
        if (taskState.goalFailCount > maxGoalFailAttempts) {
            return TurnResult.Halt(PumpStationExitReason.GoalValidationFailed)
        }
        return TurnResult.Continue  // loop again with goal's feedback in history
    }
    return TurnResult.Halt(PumpStationExitReason.JudgeComplete)
}
```

### Stage 4: Finalization

```kotlin
private suspend fun runFinalizationPhase(): MultimodalContent {
    drainBackgroundEventQueue()
    backgroundJobs.forEach { it.join() }
    backgroundJobs.clear()
    // Final memory update
    if (contextFillRatio() > compactionThreshold) runCompactionPhase()

    if (taskState.status == PumpStationStatus.Failed || taskState.exitReason in failureReasons) {
        emit(HarnessFailed(error = taskState.lastError ?: UnknownPath,
                            errorMessage = taskState.lastError?.name,
                            exitReason = taskState.exitReason ?: Error))
    } else {
        emit(HarnessCompleted(exitReason = taskState.exitReason ?: JudgeComplete,
                               finalOutput = taskState.latestContent))
    }
    return taskState.latestContent ?: MultimodalContent()
}
```

## Section 2: Component Breakdown (Phase Methods)

### `runJudgePhase(): JudgeVerdict`

```kotlin
private suspend fun runJudgePhase(): JudgeVerdict {
    taskState.phase = PumpStationPhase.Judge
    emit(JudgeStarted(...))

    if (preInvokeFunction?.invoke(contextWindow, miniBank, this) == false) {
        return JudgeVerdict(shouldHalt = true, reason = PumpStationExitReason.InterventionTerminated)
    }

    val baseInput = buildTurnContent()
    val input = preValidationJudgeFunction?.invoke(baseInput, miniBank, this)
        ?.let { baseInput.copy(miniBankContext = it) } ?: baseInput

    val result = judgeAgent!!.executeLocal(input)
    val postResult = postJudgeFunction?.invoke(result, this) ?: result
    val verdict = parseJudgeVerdict(postResult).withFlagCheck(postResult)

    taskState.latestContent = postResult
    emit(JudgeCompleted(verdict.isComplete, verdict.shouldTerminate))
    return verdict
}
```

### `runDispatchPhase(): PathRequest?`

```kotlin
private suspend fun runDispatchPhase(): PathRequest? {
    taskState.phase = PumpStationPhase.Dispatch
    emit(DispatchStarted(...))

    val baseInput = taskState.latestContent ?: buildTurnContent()
    val input = preValidationDispatchFunction?.invoke(baseInput, contextWindow, miniBank, this)
        ?.let { baseInput.copy(miniBankContext = it) } ?: baseInput

    var result = dispatchAgent!!.executeLocal(input)
    var repairAttempts = 0

    while (repairAttempts < failurePolicy.maxDispatchRepairAttempts) {
        if (checkMultimodalFlags(result, "Dispatch").shouldHalt) {
            taskState.lastError = PumpStationError.P2PRequestInvalid
            return null
        }
        val pathRequest = parseDispatchOutput(result)
        if (pathRequest != null) {
            emit(DispatchCompleted(selectedPathName = pathRequest.pathName, pathRequest = pathRequest))
            return pathRequest
        }
        if (!failurePolicy.repairInvalidDispatchJson) break
        repairAttempts++
        result = dispatchAgent!!.executeLocal(buildRepairPrompt(result))
    }

    emit(DispatchCompleted(null, null))
    emit(PathFailed(...reason = PumpStationError.DispatchJsonRepairFailed))
    if (failurePolicy.stopHarnessOnInvalidPathRequest) {
        taskState.lastError = PumpStationError.DispatchJsonRepairFailed
    }
    return null
}
```

### `runPathFlow(request: PathRequest)`

```kotlin
private suspend fun runPathFlow(request: PathRequest) {
    val path = resolvePath(request.pathName) ?: run {
        emit(PathFailed(...error = PumpStationError.UnknownPath, errorMessage = "Path not found"))
        taskState.latestContent = buildLlmErrorMessage(
            PumpStationError.UnknownPath,
            mapOf("pathName" to request.pathName, "availablePaths" to getVisiblePathNames())
        )
        return
    }
    val input = buildPathInput(path, request)
    invokePath(path, input)
}
```

### `runHealthCheckPhase()` (conditional)

Fires if `healthAgent` is set AND (`turnsSinceLast >= healthAgentTurnInterval` OR `errorRatio >= healthAgentErrorRatioThreshold`).

### `runForegroundAgentsPhase()` (conditional)

For each `HarnessAgentSlot` with `concurrency == Blocking`: fire if `turnIndex % foregroundTurnInterval == 0`.

### `runBackgroundAgentsPhase()` (conditional, async)

For each `HarnessAgentSlot` with `concurrency == Async`: queue via `launch` if `turnIndex % backgroundTurnInterval == 0`.

### `runMemoryUpdatePhase()` (conditional)

Queue memory agents at interval; **block** if `contextFillRatio() > compactionThreshold`.

### `runCompactionPhase()` (conditional)

Fires per `compactionStrategy` (Whole / Chunked / Hybrid). Triggers `preCompactionFunction` → strategy → `postCompactionFunction`.

### `detectAndHandleContextBlowout(afterPhase: PumpStationPhase): Boolean`

The emergency brake. Called at every phase boundary where a child agent returned content. If total context size exceeds `blowoutThreshold`:
1. Stash oversized content (if `failurePolicy.stashOversizedOutputs`)
2. Replace `taskState.latestContent` with stash placeholder
3. Run `preCompactionFunction` → `runCompactionPhase()` → `postCompactionFunction`
4. Fire `onContextTruncated` callback
5. Return `true` (blowout was handled)

### `checkPauseGuards(phase: PumpStationPausePhase): Boolean`

Called at 11 named phase boundaries. Returns `false` if kill switch tripped, exit reason set, or pause requested at this phase. Suspends via `Channel<Unit>` until `resume()` is called.

### Phase Method Summary Table

| Method | Phase | Conditional? | Emits Started/Completed? |
|---|---|---|---|
| `runPreInitPhase` | PreInit | No (once) | No / No (emits HarnessStarted) |
| `refreshAgentInstances` | (R.1) | No | No |
| `refreshPipelinesPrompts` | (R.2) | No | No |
| `refreshSettingsPropagation` | (R.3) | No | No |
| `runHealthCheckPhase` | HealthCheck | Yes (interval) | Yes / Yes |
| `runJudgePhase` | Judge | No | Yes / Yes |
| `runDispatchPhase` | Dispatch | No | Yes / Yes |
| `runPathFlow` | PathExecution | No (after dispatch) | (delegated to invokePath) |
| `runForegroundAgentsPhase` | ForegroundAgents | Yes (interval) | Yes / Yes (per agent) |
| `runBackgroundAgentsPhase` | BackgroundAgents | Yes (interval, async) | (agents emit) |
| `runMemoryUpdatePhase` | MemoryUpdate | Yes (interval) | Yes / Yes |
| `runCompactionPhase` | Compaction | Yes (strategy) | Yes / Yes |
| `runExitFlow` | GoalValidation | Yes (judge said complete) | Yes / Yes |
| `runFinalizationPhase` | Exit | No (once) | No / No (emits HarnessCompleted/Failed) |
| `detectAndHandleContextBlowout` | (per-phase) | Yes (over threshold) | (StashCreated, onContextTruncated callback) |
| `checkPauseGuards` | (per-phase) | No (always checked) | HarnessSuspended / HarnessResumed |

## Section 3: Data Flow

### Prompt Layering (the corrected model)

`personality`, `systemTask`, `userGuidelines`, `entryUserPrompt` go into the **system prompt** — set via `pipe.setSystemPrompt(prompt)` where `prompt` is the composed system prompt string. The pipe's `applySystemPrompt()` then layers:

1. `rawSystemPrompt` (set by `setSystemPrompt()`)
2. JSON input/output requirements (if not native JSON)
3. PCP merged mode
4. PCP-only mode
5. P2P agent descriptors
6. **Path descriptor protocol** (if `enableHarnessMode()` is called — PumpStation dispatch integration)
7. Context instructions
8. **Todo list** (if `injectTodoList` is set on the pipe)
9. `footerPrompt`
10. Semantic decompression prelude

The PumpStation's contribution: set `rawSystemPrompt` to the composed system prompt, call `enableHarnessMode()` on the dispatch pipe, optionally enable `injectTodoList`, set the `footerPrompt`.

### `buildTurnContent()` (user-message content)

```kotlin
private fun buildTurnContent(): MultimodalContent {
    return MultimodalContent(
        text = buildUserMessageForTurn(),  // turnSummary + role-specific question
        binaryContent = taskState.latestContent?.binaryContent ?: mutableListOf(),
        context = ContextWindow(
            loreBookKeys = contextWindow.loreBookKeys.toMutableMap(),
            contextElements = contextWindow.contextElements.toMutableList(),
            converseHistory = turnHistory,  // curated, structured
            version = contextWindow.version
        ),
        miniBankContext = miniBank,
        tools = taskState.latestContent?.tools ?: PcPRequest(),
        metadata = mutableMapOf<Any, Any>(
            "taskState" to taskState, "phase" to taskState.phase,
            "turnIndex" to taskState.turnIndex, "runId" to taskState.runId,
            "isInitialTurn" to (taskState.turnIndex == 0),
            "visiblePaths" to getVisiblePathNames()
        )
    )
}
```

`buildUserMessageForTurn()` returns just the user-facing text:
- Judge: `turnSummary` + "Is the task complete?"
- Dispatch: `turnSummary` + "Select the next path."
- HealthCheck: serialized `HealthContext` JSON
- Goal: `turnSummary` + judge verdict + path call log + "Verify the work was done."
- Path: `pathRequest.inputData`
- Foreground/Background: `turnSummary` + role-specific question

### Two-Tier History

| | `turnHistory` (Optimized) | `rawTurnHistory` (Full State) |
|---|---|---|
| Audience | Judge, Dispatch, agents | Goal agent, DITL hooks, developers |
| Size bound | `maxTurnHistorySize` | `maxRawTurnHistorySize` (or unbounded) |
| Compaction | Yes | No (append-only) |
| LLM-facing | Yes (via `context.converseHistory`) | No (only via `buildGoalContent` metadata) |

**Invariant:** `rawTurnHistory` is a strict superset of `turnHistory`. Both are updated in lockstep in `invokePath()`.

### Event Queue

`backgroundEventQueue: Channel<PumpStationEvent>(UNLIMITED)` is the single source of truth. All phases emit events via `emit(event)` helper. Drained at finalization.

### Background Job Lifecycle

`backgroundJobs: MutableList<Job>` tracks async work. Spawned by `runBackgroundAgentsPhase` and `runMemoryUpdatePhase`. Joined at compaction, finalization. Failures are isolated (caught, logged, don't halt the loop).

### Stash Lifecycle

`stash: MutableMap<String, ConverseData>` and `stashManifest: MutableList<StashEntry>`. Populated by `detectAndHandleContextBlowout()`. Read by paths (via new `getStashContent(stashId)` on `PathObject`) and the goal agent (via `buildGoalContent()` metadata).

### Kill Switch & Pause

- Kill switch propagated via `setParentInterface(this)` to all child agents
- Checked at every `checkPauseGuards()` call
- `pauseAt(phases...)` → `resume()` via `Channel<Unit>` rendezvous
- `forceHalt(reason)` for emergency exit from paused state

### Builder Function Lifecycle

Invoked at the start of every turn via `refreshAgentInstances()`. For paths, invoked per-path-call (already implemented in `PathObject.execute()`).

### Loop Control State Machine

```
NotStarted → P2PInitInternal() → Running + PreInit
Running + PreInit → runPreInitPhase() → Running + Judge
Running + Judge → runJudgePhase()
  ├── isComplete → Running + GoalValidation → Running + Exit
  │     ├── goalPass: Completed
  │     └── goalFail: back to Running + Judge (recursion)
  └── !isComplete → Running + Dispatch → ... → back to Judge
Suspended (during pause), Terminated (kill switch), Failed (max turns / error)
```

## Section 4: Error Handling

### Three-Tier Error Model

| Tier | Loop behavior | Example |
|---|---|---|
| Warning | Continue, emit warning event | Background job error |
| Recoverable | Continue, attempt recovery | Dispatch JSON parse failure, path not found, context blowout |
| Halt | Exit, emit `HarnessFailed` | Kill switch, max turns, `terminatePipeline` |

### Per-Phase Error Handling

- **Judge:** catch exceptions, treat unparseable as `isComplete=false`, halt on `terminatePipeline`, treat `passPipeline` as `isComplete=true`
- **Dispatch:** catch exceptions, apply repair flow (up to `maxDispatchRepairAttempts`), halt on `terminatePipeline` or unparseable + `stopHarnessOnInvalidPathRequest`
- **Path:** `invokePath()` already has try/catch (PumpStation.kt:1804-1820). Errors emit `PathFailed`, set `taskState.lastError`, return input. Now also replaces `taskState.latestContent` with LLM-targeted error message.
- **Memory:** background job failures are isolated, not fatal
- **Compaction:** failures caught, `taskState.lastError = MemoryBlowout`
- **Goal:** caught as terminatePipeline, increments `goalFailCount`, halts if exceeded

### Dispatch JSON Repair Flow

```
parseDispatchOutput(content) fails
  ├── failurePolicy.repairInvalidDispatchJson == false: skip repair
  ├── maxDispatchRepairAttempts attempts to re-prompt dispatch with repair instructions
  ├── All attempts fail:
  │     ├── stopHarnessOnInvalidPathRequest: halt
  │     └── else: skip this turn (return null)
```

The repair prompt is a fresh `MultimodalContent` asking the dispatch to fix its output. It includes the schema and the previous malformed output.

### Context Blowout Recovery

```
contextFillRatio() + contentSize > blowoutThreshold
  ├── stashOversizedOutputs: stash to stash[stashId], replace latestContent with placeholder, emit StashCreated
  ├── preCompactionFunction(content, overflowTurn, history, this)  ← DITL can intervene
  ├── runCompactionPhase()  ← unconditional
  ├── postCompactionFunction(content, newHistory, this)
  └── onContextTruncated(true, remainingFreeSpace)  ← developer callback
```

Recovery exhaustion halts with `HarnessFailed(MemoryBlowout)` after `maxBlowoutRecoveries` (new config, default 3) attempts.

### LLM-Targeted Error Messages

When the harness replaces `taskState.latestContent` with an error message, the message must be LLM-targeted natural language with:
- What the LLM did
- Why it's wrong
- What to do instead
- Concrete corrected example
- Available paths (if applicable)

Standard format:
```
[Harness Notice] Your previous action had an issue: {what}.
What you did: {concrete action}
Why it's a problem: {explanation}
What to do instead: {concrete correction}
Example of a correct call:
{JSON example}
```

Helper: `buildLlmErrorMessage(error: PumpStationError, details: Map<String, Any>): String` with sub-builders per error type.

### Kill Switch Cascade

The kill switch is the only forcible halt outside normal completion. Checked at every `checkPauseGuards()`. Trips on:
- Harness itself (after blowout recovery exhaustion)
- Path execution (via `station.killSwitch?.trip()`)
- DITL hook (if developer accesses `harness.killSwitch`)
- External observer (via `tripKillSwitch()`)

### Pause-Then-Halt Pattern

`pauseAt(phases...)` → suspends at named boundaries. `resume()` wakes up. `forceHalt(reason)` is the emergency exit. Developer is responsible for resuming; harness will hang otherwise (by design).

### New Configuration Fields

- `memoryUpdateTimeoutMs: Long = 30_000`
- `maxBlowoutRecoveries: Int = 3`
- `maxRepairPromptTokens: Int = 500`
- `maxGoalFailAttempts: Int = 3` (already in v5 design)

## Section 5: Testing

### Strategy

- **Unit tests** for each phase method with mock agents
- **Integration tests** for the full loop with real LLM mocks
- **Capture-and-assert** tests for event emission
- **Hook-call-recording** tests for DITL hook firing
- **Fault-injection** tests for error scenarios
- **Snapshot tests** for prompt composition

### Test Fixtures (`PumpStationTestFixtures.kt`)

- `MockP2PAgent` — records calls, returns scripted content
- `MockPipeline` — wraps MockP2PAgent
- `judgeScriptedResponse()`, `dispatchScriptedResponse()`, `testPath()` — builders
- `setEventObserver(consumer)` — new method for testability

### Test Files (new)

```
src/test/kotlin/Pipeline/
├── PumpStationTestFixtures.kt
├── runHarnessLoopTest.kt
├── runJudgePhaseTest.kt
├── runDispatchPhaseTest.kt
├── runPathFlowTest.kt
├── runMemoryUpdatePhaseTest.kt
├── runCompactionPhaseTest.kt
├── runExitFlowTest.kt
├── detectAndHandleContextBlowoutTest.kt
├── refreshPipelinesPromptsTest.kt
├── runHealthCheckPhaseTest.kt
├── buildLlmErrorMessageTest.kt
├── PumpStationEndToEndTest.kt
├── PumpStationPauseResumeTest.kt
├── PumpStationSnapshotTest.kt
├── PumpStationDslTest.kt
└── (existing tests extended)
```

### Coverage Targets

| Area | Target |
|---|---|
| Phase methods | 100% |
| Helper methods (build*, parse*) | 90% |
| DITL hook firing | 100% |
| Error recovery | 100% |
| Event emission | 100% |
| Flag-based control | 100% |
| Loop guard interactions | 100% |
| Memory management | 80% |
| Pause/Resume | 100% |
| Snapshot/Restore | 90% |

Estimated: ~25 new test files, ~150-200 new test cases.

## Missing Scaffolding Summary

### New Data Classes
1. `JudgeVerdict(isComplete, shouldTerminate, shouldHalt, reason)` — typed parser output
2. `TurnResult` sealed class — `Continue | Halt(reason)`
3. `MemorySnapshot` — captured in-progress state of memory agents (used by `saveSnapshot()` to record lorebook and summary mid-flight values, so a rollback can restore without losing work)
4. `DispatchOutput(pathRequest, repairAttempts, parseError)`
5. `HarnessAgentSlot(agent, concurrency, builderFunction)` — replaces `additionalHarnessAgents: MutableList<P2PInterface>`
6. `FlagCheckResult(shouldHalt, shouldPass, shouldInterrupt, haltReason)` — for `checkMultimodalFlags()`

### New Configuration Fields
7. `maxGoalFailAttempts: Int = 3`
8. `maxRawTurnHistorySize: Int? = null`
9. `blowoutThreshold: Double` (default `compactionThreshold + 0.1`)
10. `memoryUpdateTimeoutMs: Long = 30_000`
11. `maxBlowoutRecoveries: Int = 3`
12. `maxRepairPromptTokens: Int = 500`

### Default Prompts (auto-injected when developer doesn't supply)
13. `DEFAULT_JUDGE_PROMPT` — judge role framing
14. `DEFAULT_DISPATCH_PROMPT` — dispatch role framing
15. `DEFAULT_GOAL_PROMPT` — goal validation framing
16. Default judge footer / dispatch footer

### Modified Configuration Fields
17. `additionalHarnessAgents: MutableList<P2PInterface>` → `MutableList<HarnessAgentSlot>`
18. `additionalHarnessAgentBuilderFuncList` → same wrapper type

### Bug Fixes
19. `getPaths()` (PumpStation.kt:1257-1261) — should serialize `getVisiblePathDescriptorsInternal()` not `pathList`

### New Event Types
20. `HealthCheckStarted` (only `HealthCheckCompleted` exists currently)

### New Phase Methods (the loop body)
21. `runHarnessLoop()`, `runPreInitPhase()`, `runHealthCheckPhase()`, `runJudgePhase()`, `runDispatchPhase()`, `runPathFlow()`, `runForegroundAgentsPhase()`, `runBackgroundAgentsPhase()`, `runMemoryUpdatePhase()`, `runCompactionPhase()`, `runExitFlow()`, `runFinalizationPhase()`, `checkPauseGuards()`, `withDitlWrap()`, `refreshAgentInstances()`, `refreshPipelinesPrompts()`, `refreshSettingsPropagation()`, `applyPromptsToPipeline()`, `checkMultimodalFlags()`

### New Helper Methods
22. `buildTurnContent()`, `buildGoalContent()`, `buildPathInput()`, `buildJudgeSystemPrompt()`, `buildDispatchSystemPrompt()`, `buildGoalSystemPrompt()`, `buildJudgeFooter()`, `buildDispatchFooter()`, `defaultPromptFor(pipe)`, `defaultFooterFor(pipe)`, `buildUserMessageForTurn()`, `parseJudgeVerdict()`, `parseDispatchOutput()`, `parseHealthReport()`, `resolvePath()`, `contextFillRatio()`, `computeErrorRatio()`, `queueBackgroundMemoryAgents()`, `awaitBackgroundMemoryAgents()`, `drainBackgroundEventQueue()`, `pruneTurnHistory()`, `pruneRawTurnHistory()`, `summarizePoppedEntries()`, `compactWhole()`, `compactChunked()`, `compactHybrid()`, `detectAndHandleContextBlowout()`, `buildLlmErrorMessage()`, `buildInvalidPathRequestMessage()`, `buildUnknownPathMessage()`, `buildRepairFailedMessage()`, `buildPathExecutionExceptionMessage()`, `estimateContentSize()`, `estimateHistorySize()`, `computeBlowoutThreshold()`, `computeRemainingFreeSpace()`, `buildStashPlaceholder()`, `generateStashId()`, `buildHealthContext()`, `buildRepairPrompt()`, `tripKillSwitch()`, `forceHalt()`, `setEventObserver()`, `emitCompleted()`, `emitFailed()`, `awaitResumeSignal()`

### New Methods on PathObject
23. `getStashContent(stashId: String): ConverseData?`

### TaskState Modifications
24. `taskState.goalFailCount: Int = 0` (new field)
25. `taskState.latestContent: MultimodalContent?` already exists; expand how it's set

## Implementation Order (suggested)

When implementation begins (via `writing-plans` skill), the recommended order:

1. **Data classes first** (lowest risk, no dependencies)
2. **Configuration fields** (extend the class)
3. **Helper methods** (pure functions of state)
4. **Per-phase methods** (each independently testable)
5. **Per-turn refresh** (R.1, R.2, R.3)
6. **Main loop** (integrates all the above)
7. **Finalization** (last)
8. **Test infrastructure** (alongside each step)
9. **Bug fixes** (e.g., `getPaths()`)
10. **DSL extensions** (if needed for new fields)

## Verification

When implementation is complete, verify by:
- `./gradlew test` — all unit and integration tests pass
- Manual end-to-end test: build a PumpStation with a real LLM (Bedrock or Ollama), supply a multi-turn task, observe:
  - Judge/Dispatch cycle
  - Path execution via `enableHarnessMode()`
  - Memory management (if agents configured)
  - Goal validation (if goal agent configured)
  - Pause/resume at phase boundaries
  - Snapshot/restore
  - Kill switch behavior
  - Dispatch JSON repair on malformed output
  - Context blowout detection with stash
  - LLM-targeted error messages on path failure
