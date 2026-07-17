
## Path Execution Contract

A path's `setExecutionFunction` (or its internal agent or bound PCP function) returns a `MultimodalContent`. There is **no required JSON shape** on the path's output.

The harness reads the result's flags and metadata:

- `passPipeline = true` → enter goal validation (or exit with `PassSignal` if no goal agent is configured)
- `terminatePipeline = true` → halt with `TerminateSignal` directly. **No goal gate.**
- `interuptPipeline = true` → interrupt the current turn; the path's result flows through but the loop continues
- otherwise → result is appended to `turnHistory` and the loop continues

The result's `text` and `metadata` are surfaced in the `PathCompleted` event for the visualizer. The full `MultimodalContent` is stored in `taskState.lastPathResult` and is the value returned to the caller from `executeLocal` (via `runFinalizationPhase`).


## Goal Contract

The goal agent validates work done when the judge says the task is complete or a path returns `passPipeline = true`.

### Default Prompt

`DEFAULT_GOAL_PROMPT` in `Pipeline/PumpStationDefaults.kt`:

```
You are the goal validator in an agentic harness. Your job is to perform a deep verification
that the work done by the harness actually satisfies the original task.

The original task is: {entryUserPrompt}
The full event log (every path call, every result, every error) is provided.
The judge's verdict is also provided.

If the work is acceptable, do nothing special — the loop will exit.
If the work is NOT acceptable, return content with:
  - terminatePipeline = true  (this will resume the loop with your feedback)
  - text = your critique (this will be appended to conversation history)
```

### Required Output

No JSON. The goal agent returns prose `MultimodalContent`. The harness reads `terminatePipeline`:

- `terminatePipeline = false` (default) → goal passed → exit with `JudgeComplete`
- `terminatePipeline = true` → goal failed → append the result to `turnHistory`, increment `goalFailCount`, continue the loop

The goal content is built by `buildGoalContent` (`Pipeline/PumpStationHelpers.kt:727`), which overrides `context.converseHistory` with `rawTurnHistory` (the full event log) and adds `metadata.judgeVerdict = "isComplete=true"` plus `metadata.rawHistorySize`.

### Failure Path

If `taskState.goalFailCount > maxGoalFailAttempts` (default 3), the harness halts with `PumpStationExitReason.GoalValidationFailed`.


## Path-Safety Contract

The path-safety agent gates path calls for `PathRiskLevel.Medium` and `PathRiskLevel.High`.

### Default Prompt

`DEFAULT_PATH_SAFETY_PROMPT` in `Pipeline/PumpStationDefaults.kt`:

```
You are the path-safety gate in an agentic harness. Your job is to decide whether
the selected path is safe to execute.

{entryUserPrompt}

The path's name, description, schema, and risk level are provided in the
conversation history below. Use this information to assess the path's risk.

Respond with JSON matching this schema:
{
  "safe": boolean,
  "reason": string
}

safe: true if the path is safe to execute, false if it should be rejected.
reason: brief explanation of your decision.

You may also set these MultimodalContent flags on your response to drive the
harness directly (these always take precedence over the JSON contract):
  - terminatePipeline = true   (reject the path)
  - passPipeline = true        (approve the path)
```

### Required JSON

```json
{
  "safe": false,
  "reason": "Path would write to filesystem without sandboxing."
}
```

### Data Class (Implicit)

The path-safety contract does not have a dedicated data class — the parser extracts the `Boolean` directly.

### Parser

`parsePathSafetyVerdict` lives at `Pipeline/PumpStationHelpers.kt:558`:

```kotlin
internal fun parsePathSafetyVerdict(text: String): Boolean? {
    if (text.isBlank()) return null
    val trimmed = text.trim()
    return try {
        val element = Json.parseToJsonElement(trimmed)
        val obj = element as? JsonObject ?: return null
        val safeField = obj["safe"] ?: return null
        val safePrim = safeField as? JsonPrimitive ?: return null
        if (safePrim.isString) return null
        safePrim.booleanOrNull
    } catch (e: Exception) {
        null
    }
}
```

The parser is **strict** on the `safe` field:

- Must be a JSON boolean literal (`true` or `false`)
- Strings like `"true"` or `"false"`, numbers, and `null` are rejected → caller falls back to flag check
- Missing `safe` returns `null`
- Non-JSON text returns `null`

The text is trimmed but otherwise parsed as-is. No markdown fence stripping is performed.

### Flag-Based Fallback

When the parser returns `null` (or `pathSafetyJsonContractEnabled` is `false`), the harness falls back to:

- `terminatePipeline = true` → reject the path, return original input unchanged
- `passPipeline = true` → approve the path, run it
- neither set → reject (defensive default)

When a path is rejected, no `PathStarted` / `PathCompleted` event is emitted. The original input flows through as the turn's result. The rejection reason (the JSON `reason` field if the JSON contract is in force, or "path rejected by safety" otherwise) is appended to `turnHistory`, where the dispatch agent on the next turn can read it.

The parser (`Pipeline/PumpStationHelpers.kt`) extracts the `reason` alongside `safe`. `PumpStation.kt` rewrites the rejection notice via `buildLlmErrorMessage` to include the path name, the rejection source, and the reason text. The reason also rides into `PathSafetyCompleted.reason` and into the `PUMP_STATION_PATH_SAFETY_COMPLETED` trace event metadata.

### Disabling the JSON Contract

Set `setPathSafetyJsonContractEnabled(false)` (or `pathSafetyJsonContractEnabled = false` in the DSL). With the contract disabled, the harness relies entirely on the flag check.

### Alternative: `pathSafetyFunction`

Instead of an agent, you can bind a pure function:

```kotlin
station.setPathSafetyFunction { path, schema, station ->
    path.pathName != "dangerous-shell"  // approve everything except one path
}
```

The function takes priority over the agent when set.


## Health Contract

The health agent fires before the judge when the configured turn interval or error ratio is exceeded. It is proactive (not reactive like the intervention agent).

### Default Prompt

`DEFAULT_HEALTH_PROMPT` in `Pipeline/PumpStationDefaults.kt`:

```
You are the health monitor in an agentic harness. Your job is to assess the
harness's current health and report any issues.

{entryUserPrompt}

The harness's current state, recent event log, and token usage are provided
in the conversation history below.

Respond with a HealthReport JSON object containing:
  - status: "healthy" | "degraded" | "unhealthy"
  - issues: list of detected issues (empty if healthy)
  - recommendations: list of suggested fixes (empty if healthy)
```

### Required JSON (HealthReport Envelope)

```json
{
  "status": "Healthy",
  "warnings": ["Lorebook has not been updated in 5 turns"],
  "suggestions": ["Consider lowering backgroundTurnInterval"],
  "metrics": {"contextFillRatio": 0.62, "errorRatio": 0.05},
  "suggestedNextPath": null,
  "terminateHarness": false
}
```

`status` accepts any case-insensitive form of `"healthy"`, `"degraded"`, `"unhealthy"`, or `"unknown"`. The parser normalizes via the `HealthStatus` enum.

### Data Classes

`HealthContext` and `HealthReport` live in `Pipeline/PumpStationModels.kt:162`:

```kotlin
@Serializable
data class HealthContext(
    val runId: String,
    val turnIndex: Int,
    val harnessStatus: PumpStationStatus,
    val lastError: String?,
    val consecutivePathCount: Int,
    val lastSelectedPathName: String?,
    val pathCallCounts: Map<String, Int>,
    val visiblePathNames: List<String>,
    val reservePathNames: List<String>,
    val contextFillPercent: Double,
    val turnHistorySummary: List<String>,
    val recentErrors: List<String>
)

@Serializable
data class HealthReport(
    val status: HealthStatus = HealthStatus.Unknown,
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val metrics: Map<String, Double> = emptyMap(),
    val suggestedNextPath: String? = null,
    val terminateHarness: Boolean = false
)
```

`HealthStatus` is the enum in `Pipeline/PumpStationModels.kt:145`:

```kotlin
enum class HealthStatus {
    Healthy,
    Degraded,
    Critical,
    Unknown
}
```

### Parsers

`buildHealthContext` (`Pipeline/PumpStationHelpers.kt:530`) serializes the harness's current state into the `HealthContext` JSON for the agent to consume. `parseHealthReport(content)` (`Pipeline/PumpStationHelpers.kt:1554`) is a thin wrapper around `deserialize<HealthReport>(content.text)`. On any exception or null result, it returns `HealthReport()` (all defaults, `Unknown`).

### Resulting Harness Behavior

- `terminateHarness: true` → set `taskState.latestContent.terminatePipeline = true`, which propagates to the next phase boundary and halts the loop
- otherwise → continue normally

The health agent runs based on `healthAgentTurnInterval` (turns since last check) or `healthAgentErrorRatioThreshold` (running error ratio). Both are nullable; setting either enables the health phase. Concurrency mode (`Blocking` or `Async`) determines whether the judge waits for the health verdict.


## Lorebook Contract

The lorebook agent updates `contextWindow.loreBookKeys` with new entries, updates, and removals based on recent turns.

### Default Prompt

`DEFAULT_LOREBOOK_PROMPT` in `Pipeline/PumpStationDefaults.kt`:

```
You are the lorebook curator in an agentic harness. Your job is to manage
the lorebook entries that should be active for the current task.

{entryUserPrompt}

The current lorebook, the harness's recent context, and the path being
processed are provided in the conversation history below.

Respond with a LorebookAgentOutput JSON object containing:
  - entriesToAdd: list of new lorebook entries to add
  - entriesToUpdate: list of existing lorebook entries to update (matched by id)
  - entriesToRemove: list of lorebook entry ids to remove
```

### Required JSON (LorebookAgentOutput Envelope)

```json
{
  "compactedThroughTurn": 7,
  "updates": [
    {
      "key": "algorithm-name",
      "value": "Raft consensus algorithm details...",
      "weight": 80,
      "linkedKeys": ["distributed-systems"],
      "aliasKeys": ["raft"],
      "requiredKeys": [],
      "operation": "Merge"
    }
  ],
  "deletions": ["obsolete-key-1", "obsolete-key-2"]
}
```

`compactedThroughTurn` is the cursor advance — entries up to and including this turn are considered folded into the lorebook.

### Data Classes

`LorebookAgentInput`, `LorebookAgentOutput`, `LorebookUpdate`, and `LorebookOperation` are in `Pipeline/LorebookAgentModels.kt`. The envelope is constructed in `buildLorebookAgentInput` (`Pipeline/PumpStationLoop.kt:1372`):

```kotlin
@Serializable
data class LorebookAgentInput(
    val turnsSinceLastUpdate: List<ConverseData>,
    val lastLorebookUpdateTurnIndex: Int,
    val currentLorebook: List<LoreBook>,
    val taskContext: LorebookTaskContext,
    val harnessGeneration: Long
)

@Serializable
data class LorebookAgentOutput(
    val compactedThroughTurn: Int,
    val updates: List<LorebookUpdate>,
    val deletions: List<String>
)
```

`LorebookUpdate` carries `key`, `value`, `weight`, `linkedKeys`, `aliasKeys`, `requiredKeys`, and an `operation` (either `LorebookOperation.Merge` or `LorebookOperation.Replace`).

### Parsers and Application

The harness tries the typed envelope first (`applyTypedLorebookUpdates` at `Pipeline/PumpStationLoop.kt:1416`). If that fails, it falls back to the legacy free-form JSON path (`applyLorebookUpdates` at `Pipeline/PumpStationLoop.kt:1479`) which accepts `LoreBook` objects or arrays thereof.

### Cursor Pre-Emption

The lorebook agent runs under `lorebookMutex` to keep updates chronological. The cursor (`LorebookCursor.lastUpdatedTurnIndex`) is advanced on success. Outputs whose `compactedThroughTurn` is not strictly greater than the current cursor are discarded silently — concurrent updates that have already covered the work are dropped without re-running the LLM.

```kotlin
@Serializable
data class LorebookCursor(
    val lastUpdatedTurnIndex: Int = -1,
    val lastUpdateTimestamp: Long = 0L,
    val lastUpdateGeneration: Long = 0L
)
```


## Summary Contract

The summary agent produces the `turnSummary` string injected at the top of judge and dispatch user messages on subsequent turns.

### Required Output

No JSON. The summary agent returns prose `MultimodalContent`. The harness replaces `turnSummary` wholesale with the result's `text`.

### Parser and Application

`updateSummary` lives at `Pipeline/PumpStationLoop.kt:1504`:

```kotlin
internal suspend fun PumpStation.updateSummary() {
    if (summaryAgentInternal == null) return
    summaryMutex.withLock {
        val content = taskState.latestContent ?: MultimodalContent()
        val summaryResult = summaryAgentInternal!!.executeLocal(content)
        val flags = checkMultimodalFlags(summaryResult, "Summary")
        if (flags.shouldHalt) {
            taskState.lastError = PumpStationError.P2PRequestInvalid
            return
        }
        if (flags.shouldPass) return
        turnSummary = summaryResult.text
    }
}
```

The summary agent runs under `summaryMutex` for chronological ordering. If the agent signals `terminatePipeline = true`, the harness records `lastError = P2PRequestInvalid` and skips the update. If the agent signals `passPipeline = true`, the harness skips the update without error.


## Path Descriptor Contract (Inbound)

The dispatch agent does **not** produce the path descriptor — it consumes one. The harness builds a `PathDescriptionList` from the registered paths and serializes it into the dispatch agent's system prompt.

### Data Class

`PathDescriptionList` lives in `Pipeline/PumpStation.kt:189`:

```kotlin
@Serializable
data class PathDescriptionList(
    var paths: MutableList<PathDescriptionData> = mutableListOf()
)

@Serializable
data class PathDescriptionData(
    val name: String,
    val description: String,
    val inputSchema: String,
    val pcpSchema: PcpContext?,
    val hasInternalAgent: Boolean,
    val hasExecutionFunction: Boolean,
    val isRunsInBackground: Boolean,
    val agentTypeName: String? = null
)
```

### How the Dispatch Pipe Sees It

`PumpStation.getPaths(): String` returns `serialize(getVisiblePathDescriptorsInternal(), false)`. The dispatch pipe's `enableHarnessMode()` flag auto-injects this serialized descriptor list into the pipe's system prompt. The dispatch agent emits a `PathRequest` referencing one of the names in this list.

### Reserve Path Filtering

`getVisiblePathDescriptorsInternal` (`Pipeline/PumpStation.kt:1739`) builds the visible list each turn:

1. All paths in `pathList` are included
2. Reserve paths in `reservePaths` are filtered through `revealWhen` predicate; revealed paths are included, hidden ones are not
3. Once a reserve path is revealed, it stays visible (sticky)

The list is rebuilt per turn so changes to `taskState`, `pathCallCounts`, and `externalContext` are reflected immediately.

### Dispatch Hint

`PathObject.dispatchHint: String` is a soft advisory injected as `"Hint: <text>"` into the path's description when surfaced to the dispatch agent. It does not affect execution — only the LLM-facing description. Useful for nudging the model toward a path when the situation is ambiguous.


## Flag-Based Contracts (Outbound)

The universal control surface. The harness checks `MultimodalContent` flags on every agent result, regardless of whether the JSON contract is enabled.

### The Four Loop-Control Flags

| Flag                    | Effect                                                                                  |
|-------------------------|-----------------------------------------------------------------------------------------|
| `terminatePipeline: true` | Halt the harness immediately. No goal gate.                                          |
| `passPipeline: true`      | Pass-through: enter goal validation (or exit with `PassSignal` if no goal agent).     |
| `interuptPipeline: true`  | Interrupt the current turn; result flows through but the loop continues.              |
| `nothing set`             | Normal — result is appended to history and the loop continues.                         |

### Where Flags Are Read

`checkMultimodalFlags(content, source): FlagCheckResult` in `Pipeline/PumpStationHelpers.kt:459`:

```kotlin
internal fun checkMultimodalFlags(content: MultimodalContent, source: String): FlagCheckResult {
    return FlagCheckResult(
        shouldHalt = content.terminatePipeline,
        shouldPass = content.passPipeline,
        shouldInterrupt = content.interuptPipeline,
        haltReason = content.metadata["haltReason"] as? String ?: "halted by $source"
    )
}
```

The `source` parameter is the agent name (Judge, Dispatch, Memory, Summary, Lorebook) and is used in the default `haltReason` string. The harness reads the result at every phase boundary:

- Judge: `runJudgePhase` (`PumpStationLoop.kt:265`) — `parsed.withFlagCheck(postResult)`
- Dispatch: `runDispatchPhase` (`PumpStationLoop.kt:299`) — early halt if `terminatePipeline`
- Memory: `runMemoryUpdatePhase` (`PumpStationLoop.kt:1043`) — halt on memory agents' flags
- Summary / Lorebook: inside `updateSummary` and `updateLorebook`

### Data Class

`FlagCheckResult` lives in `Pipeline/PumpStationModels.kt:868`:

```kotlin
@Serializable
data class FlagCheckResult(
    val shouldHalt: Boolean = false,
    val shouldPass: Boolean = false,
    val shouldInterrupt: Boolean = false,
    val haltReason: String? = null
)
```


## Strict vs Lenient Parsing

The harness uses a strict parser when the contract is non-negotiable (path-safety `safe` must be a boolean literal) and a lenient parser when the contract is advisory (judge `isComplete` defaults to `false` if missing).

| Parser                       | Strictness                                                       |
|------------------------------|------------------------------------------------------------------|
| `parseJudgeVerdict`          | Lenient. Missing fields default to `false`. Returns `empty()` on parse failure. |
| `parseDispatchOutput`        | Strict. Returns `null` on parse failure or empty `pathName`.     |
| `parseDispatchOutputMulti`   | Strict. Returns `null` on parse failure or empty `paths` array. Used when `pathExecutionShape = PathExecutionShape.MultiPath`; the empty-array case is treated as a parse failure and triggers the repair loop. |
| `parsePathSafetyVerdict`     | Strict. `safe` must be a JSON boolean literal. Returns `null` otherwise. |
| `parseHealthReport`          | Lenient. Returns `HealthReport()` (Unknown) on parse failure.    |
| `extractJson<LorebookAgentOutput>` | Lenient. Falls back to legacy `applyLorebookUpdates` on failure. |
| `extractJson<LoreBook>`      | Lenient. Silently no-ops on failure.                              |

When a strict parser rejects an agent's output, the harness falls back to a flag-based check (`terminatePipeline` / `passPipeline`) on the source `MultimodalContent`. When a lenient parser rejects output, the harness either returns a default verdict or skips the update.


## Repair on Parse Failure

`buildRepairPrompt(badOutput: MultimodalContent)` (`Pipeline/PumpStationLoop.kt:381`) constructs a follow-up prompt asking the dispatch agent to fix its malformed JSON:

```
[Harness Notice] Your previous dispatch output was not parseable as a PathRequest JSON.
Previous output: <truncated bad output>

Please retry with a valid PathRequest JSON object. The schema is:
{
  "pathName": "the exact path name from the visible list",
  "pathSchema": "free-form input schema string",
  "pathSelectionRationale": "1-2 sentence explanation of why this path was picked (optional)"
}
```

The repair loop runs up to `failurePolicy.maxDispatchRepairAttempts` times (default 1). After the budget is exhausted:

- `failurePolicy.stopHarnessOnInvalidPathRequest = false` (default): the turn continues without a path call. The natural-language error message is injected into `turnHistory` for the next turn.
- `failurePolicy.stopHarnessOnInvalidPathRequest = true`: the harness records `lastError = DispatchJsonRepairFailed`. `runFinalizationPhase` emits `HarnessFailed`.

### Multi-Path Repair Prompt

`buildMultiPathRepairPrompt(badOutput: MultimodalContent)` (`Pipeline/PumpStationLoop.kt:648`) constructs the follow-up prompt for `PathExecutionShape.MultiPath` mode when the dispatch LLM's output fails to parse as `PathRequestList`:

```
[Harness Notice] Your previous dispatch output was not parseable as a PathRequestList JSON.
Previous output: <truncated bad output>

Please retry with a valid PathRequestList JSON object. The schema is:
{
  "paths": [
    {"pathName": "...", "pathSchema": "...", "pathSelectionRationale": "..."}
  ],
  "batchRationale": "..."
}
```

The multi-path repair loop has the same configuration as the single-path loop: up to `failurePolicy.maxDispatchRepairAttempts` iterations, with `failurePolicy.stopHarnessOnInvalidPathRequest` controlling the post-exhaustion behavior. A `PathBatchFailed` event with `errorMessage` and `repairAttempts` rides at the end of the failed batch, distinct from per-path `PathFailed` events.

Only the dispatch contract supports repair. Other contracts either fall back to defaults (judge, health) or fall back to flag-based logic (path-safety, lorebook, summary).

### Dispatch Rationale Reminder (Soft Nudge)

When `failurePolicy.requirePathSelectionRationale = true` (the default) and the dispatch LLM returns `pathSelectionRationale = null` or blank, `applyRationaleNudgeIfNeeded` (`Pipeline/PumpStationLoop.kt:2830`) appends a one-shot reminder to the next dispatch user prompt.

The reminder is appended at most once per run. To disable the nudge, set `failurePolicy.requirePathSelectionRationale = false` (or `setRequirePathSelectionRationale(false)` in the DSL).


## Testing the Contracts

The parsers are `internal` and not part of the public API, but the **data classes are public** and can be tested directly.

### Parse Judge Verdict in a Test

```kotlin
import com.TTT.Pipeline.JudgeVerdict
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val rawText = """{"isComplete": true, "shouldTerminate": false, "reason": "done"}"""
val obj = Json.parseToJsonElement(rawText) as JsonObject
val verdict = JudgeVerdict(
    isComplete = obj["isComplete"]?.jsonPrimitive?.boolean ?: false,
    shouldTerminate = obj["shouldTerminate"]?.jsonPrimitive?.boolean ?: false
)
assertEquals(true, verdict.isComplete)
```

### Round-Trip a PathRequest

```kotlin
import com.TTT.Pipeline.PathRequest
import com.TTT.Util.serialize
import com.TTT.Util.deserialize

val req = PathRequest(
    pathName = "research",
    pathSchema = """{"q":"hi"}""",
    pathSelectionRationale = "Picked research because the user asked for history of X."
)
val json = serialize(req, false)
val parsed = deserialize<PathRequest>(json)
assertEquals("research", parsed.pathName)
assertEquals("""{"q":"hi"}""", parsed.pathSchema)
assertEquals("Picked research because the user asked for history of X.", parsed.pathSelectionRationale)
```

`pathSelectionRationale` is nullable: old checkpoints that don't emit it round-trip as `null`. See [Dispatch Contract: `pathSelectionRationale`](../containers/pumpstation.md#dispatch-contract-pathselectionrationale).

### Round-Trip a PathRequestList

```kotlin
import com.TTT.Pipeline.PathRequest
import com.TTT.Pipeline.PathRequestList
import com.TTT.Util.serialize
import com.TTT.Util.deserialize

val list = PathRequestList(
    paths = listOf(
        PathRequest(pathName = "research", pathSchema = """{"q":"hi"}""", pathSelectionRationale = "first"),
        PathRequest(pathName = "summarize", pathSchema = "{}", pathSelectionRationale = "second")
    ),
    batchRationale = "Independent reads; parallelize."
)
val json = serialize(list)
val parsed = deserialize<PathRequestList>(json) ?: error("deserialize failed")
assertEquals(2, parsed.paths.size)
assertEquals("research", parsed.paths[0].pathName)
assertEquals("Independent reads; parallelize.", parsed.batchRationale)
```

The multi-path contract is the dispatch LLM's contract when `pathExecutionShape = PathExecutionShape.MultiPath`. The harness calls `parseDispatchOutputMulti` (rather than `parseDispatchOutput`) against the LLM output, then `runDispatchPhaseMulti` fans the parsed list out via `launchAsyncPath`. An empty `paths` array is treated as a parse failure and triggers the repair loop.

### Validate a HealthReport

```kotlin
import com.TTT.Pipeline.HealthReport
import com.TTT.Pipeline.HealthStatus
import com.TTT.Util.deserialize

val rawText = """{"status": "Healthy", "warnings": [], "suggestions": [], "metrics": {}}"""
val report = deserialize<HealthReport>(rawText) ?: HealthReport()
assertEquals(HealthStatus.Healthy, report.status)
assertEquals(false, report.terminateHarness)
```

### Use a Scripted Pipe in Live Tests

For end-to-end tests, use the harness's test fixtures to drive specific outputs:

```kotlin
import com.TTT.Pipeline.ScriptedTestPipe

val judgePipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
val judge = Pipeline().apply { add(judgePipe) }
station.setJudgeAgent(judge)
```

`ScriptedTestPipe` lives in `src/test/kotlin/Pipeline/PumpStationTestFixtures.kt` and is the standard way to drive deterministic outputs in unit tests. The pattern is to script each agent's response, run the harness, and assert on `taskState.exitReason` / `taskState.lastError`.

### Verify Path-Safety Strictness

```kotlin
// The path-safety parser rejects string "true"
val lenient = kotlinx.serialization.json.JsonPrimitive("true").booleanOrNull  // true
// But the harness's parsePathSafetyVerdict rejects it explicitly:
internal fun parsePathSafetyVerdict(text: String): Boolean? {
    // ... if (safePrim.isString) return null ...
    // returns null on string "true", forcing flag-based fallback
}
```

If you write a custom path-safety agent, test it against a strict-boolean JSON response, not a string. The harness's strictness is by design.

### Where to Find the Tests

- `src/test/kotlin/Pipeline/PumpStationEndToEndTest.kt` — happy-path 3-turn and max-turns scenarios
- `src/test/kotlin/Pipeline/PumpStationDslNewFieldsTest.kt` — DSL field defaults and overrides
- `src/test/kotlin/Pipeline/PumpStationPathInitTest.kt` — `PathObject.init()` validation
- `src/test/kotlin/Pipeline/PumpStationPathObjectBindingTest.kt` — PCP function binding
- `src/test/kotlin/Pipeline/PumpStationSetGetTest.kt` — agent and DITL setter round-trips
- `src/test/kotlin/Pipeline/PumpStationLiveLLMTest.kt` — real LLM end-to-end (gated on `TPIPE_LIVE_LLM_TEST=true`)
- `src/test/kotlin/Pipeline/PumpStationMiniMaxLiveTest.kt` — live tests with stub keys for offline CI

---

**Next:** [PumpStation API Reference →](../api/pumpstation.md)
