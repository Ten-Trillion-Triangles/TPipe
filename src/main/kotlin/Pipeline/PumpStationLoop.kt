package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Context.LoreBook
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
import com.TTT.P2P.KillSwitchException
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Util.deserialize
import com.TTT.Util.extractJson
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.RemoteTraceConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Util.serialize
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PumpStation loop body. Houses runHarnessLoop, runTurn, runPreInitPhase,
 * runFinalizationPhase, and the per-phase methods (runJudgePhase, etc.).
 *
 * This file is split out from PumpStation.kt to keep each file focused.
 * All methods here operate on the PumpStation instance via internal access.
 */

private val resumeSignal = Channel<Unit>(Channel.RENDEZVOUS)

/**
 * Returns the list of path descriptors currently visible to the dispatch agent.
 */
internal fun PumpStation.getVisibleDescriptors(): List<PathDescriptionData> =
    getVisiblePathDescriptorsForDispatch().paths

/**
 * R.1: Refresh agent instances per turn. Invokes builder functions to get
 * fresh, thread-safe agent instances for this turn. If no builder is set,
 * the existing instance is kept.
 */
internal suspend fun PumpStation.refreshAgentInstances()
{
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
    goalAgentBuilderFunction?.let { fn ->
        goalAgent = fn(this)
        goalAgent?.setParentInterface(this)
        goalAgent?.P2PInit()
    }
}

/**
 * R.2: Refresh system prompts on all relevant pipelines. Custom prompts are
 * used if set; otherwise auto-injected defaults are applied. The dispatch
 * pipe always has enableHarnessMode() called so path descriptors are re-injected.
 */
internal fun PumpStation.refreshPipelinesPrompts()
{
    applyPromptsToPipeline(judgeAgent, buildJudgeSystemPrompt(), buildJudgeFooter())
    applyPromptsToPipeline(dispatchAgent, buildDispatchSystemPrompt(), buildDispatchFooter())
    if (goalAgent is Pipeline)
{
        applyPromptsToPipeline(goalAgent as Pipeline, buildGoalSystemPrompt(), null)
    }
}

internal fun PumpStation.applyPromptsToPipeline(
    agent: Pipeline?,
    customPrompt: String?,
    customFooter: String?
)
{
    if (agent == null) return
    val pipes = agent.getPipes()
    for (pipe in pipes)
    {
        val prompt = customPrompt ?: defaultPromptFor(agent)
        val footer = customFooter ?: defaultFooterFor(agent)
        pipe.setSystemPrompt(prompt)
        pipe.setFooterPrompt(footer)
        if (agent == dispatchAgent) pipe.enableHarnessMode()
        pipe.applySystemPrompt()
    }
}

internal fun PumpStation.defaultPromptFor(agent: P2PInterface): String
{
    return when (agent)
{
        judgeAgent -> DEFAULT_JUDGE_PROMPT
        dispatchAgent -> DEFAULT_DISPATCH_PROMPT
        goalAgent -> DEFAULT_GOAL_PROMPT
        else -> "You are an agent in an agentic harness."
    }
}

internal fun PumpStation.defaultFooterFor(agent: P2PInterface): String
{
    return when (agent)
{
        judgeAgent -> DEFAULT_JUDGE_FOOTER
        dispatchAgent -> DEFAULT_DISPATCH_FOOTER
        else -> ""
    }
}

/**
 * Check pause guards at a phase boundary. Returns true to continue, false to halt.
 * Suspends if a pause is requested at this phase until resume() is called.
 */
internal suspend fun PumpStation.checkPauseGuards(phase: PumpStationPausePhase): Boolean
{
    // KillSwitch check
    if (killSwitch != null && taskState.exitReason == PumpStationExitReason.KillSwitchTripped)
{
        return false
    }
    if (taskState.exitReason != null) return false
    if (taskState.isPaused && phase in taskState.pausedAt)
{
        emitEventInternal(HarnessSuspended(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            pausedAt = taskState.pausedAt,
            reason = taskState.pauseReason
        ))
        awaitResumeSignal()
    }
    return true
}

private suspend fun PumpStation.awaitResumeSignal()
{
    resumeSignal.receive()
}

/**
 * Wakes up a suspended checkPauseGuards call. Called by PumpStation.resume()
 * after the bookkeeping (clearing isPaused, pausedAt, pauseReason) and after
 * emitting HarnessResumed.
 */
internal fun PumpStation.notifyResume()
{
    resumeSignal.trySend(Unit)
}

/**
 * Drain the steering service for [phase] and append each returned entry to
 * turnHistory. Called at every phase boundary in the harness loop, BEFORE
 * the phase-specific work begins. The drain is non-blocking and does not
 * halt the loop.
 *
 * Persistent overlays (when set) fire on every occurrence; one-shot
 * instructions fire once and are then discarded. Each injected entry
 * carries a canonical `metadata["steering"]` envelope stamped by
 * [PumpStation.drainSteeringForPhase].
 *
 * @param phase The PumpStationPausePhase boundary to inject at
 */
internal suspend fun PumpStation.injectSteeringForPhase(phase: PumpStationPausePhase)
{
    val entries = drainSteeringForPhase(phase)
    entries.forEach { entry ->
        turnHistory.add(ConverseData(role = ConverseRole.harness, content = entry))
    }
}

/**
 * Poll the interrupt service for [phase]. If a pending entry exists, throw
 * [PumpStationInterruptException] carrying the entry's content and [snapshot]
 * (the [PumpStationInterruptSnapshot] taken at the most recent BeforeJudge of
 * the current turn). If no entry is pending, this is a no-op.
 *
 * The exception is caught at the top of [runHarnessLoop] around the [runTurn]
 * invocation. The catch handler restores the snapshot, appends the entry to
 * turnHistory with the canonical `metadata["interrupt"]` envelope, and
 * re-invokes [runTurn] without incrementing `taskState.turnIndex`.
 *
 * Combination semantics: if the service has more than one entry queued, the
 * first is thrown as the interrupt; the rest are forwarded to the steering
 * service as one-shot steering instructions. If the steering service is not
 * configured for the phase (or throws), the overflow entries are silently
 * dropped and an [InterruptOverflowDropped] event is emitted for observability
 * (operator-confirmed requirement, 2026-07-24).
 */
internal suspend fun PumpStation.injectInterruptForPhase(
    phase: PumpStationPausePhase,
    snapshot: PumpStationInterruptSnapshot
)
{
    val first = interruptService.drainForPhase(phase) ?: return

    // Stamp the canonical interrupt envelope onto the entry before it goes
    // out to the catch handler. The envelope uses the `interrupt` key so the
    // judge LLM and trace visualizer can branch on producer type.
    val now = System.currentTimeMillis()
    val envelope: Map<String, Any> = mapOf(
        "phase" to phase.name,
        "wasRewound" to true,
        "injectionId" to UUID.randomUUID().toString(),
        "timestamp" to now
    )
    val mergedMetadata: MutableMap<Any, Any> = mutableMapOf()
    first.metadata.forEach { (k, v) -> mergedMetadata[k] = v }
    mergedMetadata["interrupt"] = envelope
    val stamped = first.copy()
    stamped.metadata = mergedMetadata

    // Forward any overflow entries to steering. Best-effort: if the steering
    // service has no one-shot channel and no persistent overlay for the phase,
    // the entries are silently dropped AND an InterruptOverflowDropped event
    // is emitted for observability (operator-confirmed requirement).
    val overflow = interruptService.drainAllForPhase(phase)
    var droppedCount = 0
    var firstDroppedText: String? = null
    overflow.forEach { extra ->
        try
        {
            steeringService.enqueueOneShot(phase, extra)
        }
        catch (_: Throwable)
        {
            droppedCount++
            if (firstDroppedText == null) firstDroppedText = extra.text.take(200)
        }
    }
    if (droppedCount > 0)
    {
        emitEventInternal(InterruptOverflowDropped(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            boundaryPhase = phase,
            droppedCount = droppedCount,
            firstDroppedText = firstDroppedText
        ))
    }

    throw PumpStationInterruptException(stamped, snapshot)
}

/**
 * Generic DITL wrap helper. Runs the preHook, then operation, then postHook.
 * If preHook is null, runs operation directly. If postHook is null, returns operation result.
 */
internal suspend fun <T> withDitlWrap(
    preHook: (suspend () -> T?)?,
    operation: suspend () -> T,
    postHook: (suspend (T) -> T)?
): T
{
    if (preHook != null)
{
        val preResult = preHook()
        if (preResult != null)
{
            val result = operation()
            return postHook?.invoke(result) ?: result
        }
    }
    val result = operation()
    return postHook?.invoke(result) ?: result
}

/**
 * Run the agent pipeline and return its result. Falls back to executeLocal
 * for non-Pipeline P2PInterface implementations. This is the standard way
 * the harness invokes judge/dispatch/goal agents.
 *
 * Wires the agent pipeline as a child of the harness when the agent has no
 * parent of its own, so the dispatch pipe's applySystemPrompt() can walk the
 * P2PInterface ownership tree and reach the nearest PumpStation via
 * [Pipe.getNearestPumpStationParent]. Without this, the path-injection block
 * at Pipe.kt:2319-2341 silently no-ops. The condition is conditional on no
 * existing parent: an agent the developer explicitly nested inside another
 * container keeps its original wiring.
 */
internal suspend fun PumpStation.runAgent(agent: Pipeline?, input: MultimodalContent): MultimodalContent
{
    if (agent == null) return input
    if (agent.getParentP2PInterface() == null)
    {
        agent.setParentInterface(this)
    }
    return agent.execute(input)
}

/**
 * Run the judge agent, parse the verdict, check flags.
 * Returns JudgeVerdict indicating what to do next.
 */
internal suspend fun PumpStation.runJudgePhase(): JudgeVerdict
{
    // Drain any async path or async harness agent results that completed
    // since the previous drain. The judge LLM will see them merged into
    // [turnHistory] for this turn's reasoning.
    drainPendingAsyncResults()
    taskState.phase = PumpStationPhase.Judge

    // FlagTriggered mode: honor the one-shot requestJudgeNextTurn flag. When the flag is false the
    // judge phase is skipped (no LLM call, no JudgeStarted/JudgeCompleted) and a JudgeSkipped event
    // is emitted in their place so the trace/visualizer can show what happened. The flag is
    // automatically cleared after the judge consumes it on the next run.
    if (judgeRunModeInternal == PumpStationJudgeRunMode.FlagTriggered)
    {
        if (!taskState.requestJudgeNextTurn)
        {
            emitEventInternal(JudgeSkipped(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                reason = "no_flag_set",
                judgeRunMode = PumpStationJudgeRunMode.FlagTriggered
            ))
            return JudgeVerdict.empty()
        }
        // Flag is set: clear it (one-shot) and fall through to the normal judge flow.
        taskState.requestJudgeNextTurn = false
    }
    else if (skipJudgeOnFirstTurnInternal && taskState.turnIndex == 0)
    {
        // First-turn guard: skip the judge on turn 0 to prevent the live-judge failure mode
        // where a judge LLM sees the pre-dispatch state and hallucinates isComplete=true
        // before any path has run. The harness continues into dispatch and at least one
        // path execution; the judge gets a real verdict vote on turn 1+.
        //
        // Only fires in PumpStationJudgeRunMode.Always — the FlagTriggered branch above
        // already short-circuits and keeps its canonical "no_flag_set" reason. The
        // "first_turn" reason is reserved for the Always-mode guard.
        emitEventInternal(JudgeSkipped(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            reason = "first_turn",
            judgeRunMode = PumpStationJudgeRunMode.Always
        ))
        return JudgeVerdict.empty()
    }

    emitEventInternal(JudgeStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex
    ))

    // Pre-invoke gate
    if (preInvokeFunctionInternal?.invoke(contextWindow, miniBank, this) == false)
{
        return JudgeVerdict(
            shouldHalt = true,
            reason = PumpStationExitReason.InterventionTerminated
        )
    }

    // Pre-validation
    val baseInput = buildTurnContent()
    val input = preValidationJudgeFunctionInternal?.invoke(baseInput, miniBank, this)
        ?.let { baseInput.copy(miniBankContext = it) } ?: baseInput

    // Judge LLM call
    val result = runAgent(judgeAgent, input)
    val judgeUsage = agentTokenUsage(judgeAgent)

    // Post-judge hook
    val postResult = postJudgeFunctionInternal?.invoke(result, this) ?: result

    // Parse verdict + flag check.
    // When the judge contract is disabled (judgeExpectsJsonContract = false), the
    // JSON parser is skipped and the verdict comes solely from the flag check on
    // the agent's MultimodalContent. This matches the canonical loop-control
    // pattern documented on checkMultimodalFlags: "agents signal via flags, not
    // via magic contracts."
    val parsed = if (judgeExpectsJsonContract) parseJudgeVerdict(postResult) else JudgeVerdict.empty()
    val verdict = parsed.withFlagCheck(postResult)

    taskState.latestContent = postResult
    emitEventInternal(JudgeCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        isComplete = verdict.isComplete,
        shouldTerminate = verdict.shouldTerminate,
        result = postResult,
        inputTokens = judgeUsage?.first,
        outputTokens = judgeUsage?.second?.first,
        totalTokens = judgeUsage?.second?.second
    ))
    recordAndCheckKillSwitch(judgeAgent)
    return verdict
}

/**
 * Run the dispatch agent, parse the path request, attempt repair on parse failure.
 * Returns null if dispatch fails irrecoverably.
 */
internal suspend fun PumpStation.runDispatchPhase(): PathRequest?
{
    taskState.phase = PumpStationPhase.Dispatch
    emitEventInternal(DispatchStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex
    ))

    // Build the dispatch input from buildTurnContent() so the conversation
    // history (which now includes the prior judge verdict + path outputs) is
    // embedded in the user message text. Prepend the latestContent text —
    // when present, it is the most recent prior agent's output (judge verdict
    // on the previous turn, or path output on turn 0) and gives the dispatch
    // LLM direct context without forcing it to re-parse the serialized
    // history block.
    val baseInput = if (taskState.latestContent != null && taskState.latestContent!!.text.isNotEmpty())
    {
        val enriched = buildTurnContent()
        enriched.copy(text = "[LATEST PRIOR AGENT OUTPUT]\n" + taskState.latestContent!!.text +
            "\n[/LATEST PRIOR AGENT OUTPUT]\n\n" + enriched.text)
    }
    else buildTurnContent()
    val input = preValidationDispatchFunctionInternal?.invoke(baseInput, contextWindow, miniBank, this)
        ?.let { baseInput.copy(miniBankContext = it) } ?: baseInput

    var result = runAgent(dispatchAgent, input)

    // Flag check at the DITL hook point: the universal loop-control contract.
    // If the dispatch agent signaled halt on its response, we don't fire the
    // post-generate hook — the harness needs to see the halt signal first.
    val dispatchFlags = checkMultimodalFlags(result, "Dispatch")
    if (dispatchFlags.shouldHalt)
{
        taskState.lastError = PumpStationError.P2PRequestInvalid
        return null
    }

    // Post-generate DITL hook: fires after the dispatch agent has produced
    // its path output. The hook may return a P2PInterface (e.g. an alternate
    // agent the developer wants exposed for tracing) which we surface in
    // the result's metadata. The actual path request is still parsed below
    // from the original output.
    postGenerateFunctionInternal?.invoke(result, this)?.let { returnedAgent ->
        result.metadata["postGenerateAgent"] = returnedAgent
    }

    var repairAttempts = 0
    val dispatchUsage = agentTokenUsage(dispatchAgent)
    recordAndCheckKillSwitch(dispatchAgent)

    while (repairAttempts <= failurePolicy.maxDispatchRepairAttempts)
{
        val flags = checkMultimodalFlags(result, "Dispatch")
        if (flags.shouldHalt)
{
            taskState.lastError = PumpStationError.P2PRequestInvalid
            return null
        }
        val pathRequest = parseDispatchOutput(result)
        if (pathRequest != null)
{
            // Empty pathName is treated as a harness error, not a "I'm done" sentinel.
            // The dispatch LLM MUST always pick a path from the visible list. Returning
            // empty pathName silently would let the loop spin without progress; instead we
            // emit PathFailed, append a hint to the conversation history so the next
            // dispatch LLM sees the constraint, and let the loop continue to the next
            // turn. The harness safety net (maxTurns) bounds the retry count.
            if (pathRequest.pathName.isBlank())
{
                val hintMessage = "[Harness Notice] Your dispatch output was a valid PathRequest " +
                    "JSON but the pathName field was empty. Empty pathName is NOT a valid " +
                    "signal — you MUST pick an exact path name from the visible list above. " +
                    "If you cannot make progress, pick a path whose purpose is to ask the " +
                    "user for clarification. To signal task completion, use a path that sets " +
                    "passPipeline=true on its result, not an empty pathName."
                turnHistory.add(
                    ConverseData(
                        role = ConverseRole.harness,
                        content = MultimodalContent(text = hintMessage)
                    )
                )
                emitEventInternal(PathFailed(
                    runId = taskState.runId,
                    turnIndex = taskState.turnIndex,
                    pathName = "(empty)",
                    riskLevel = PathRiskLevel.Low,
                    error = PumpStationError.DispatchJsonRepairFailed,
                    errorMessage = "Dispatch returned a valid PathRequest with empty pathName. " +
                        "Hint appended to turn history."
                ))
                if (failurePolicy.stopHarnessOnInvalidPathRequest)
{
                    taskState.lastError = PumpStationError.DispatchJsonRepairFailed
                }
                emitEventInternal(DispatchCompleted(
                    runId = taskState.runId,
                    turnIndex = taskState.turnIndex,
                    selectedPathName = null,
                    pathRequest = pathRequest,
                    result = result,
                    inputTokens = dispatchUsage?.first,
                    outputTokens = dispatchUsage?.second?.first,
                    totalTokens = dispatchUsage?.second?.second
                ))
                return null
            }
            emitEventInternal(DispatchCompleted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                selectedPathName = pathRequest.pathName,
                pathRequest = pathRequest,
                result = result,
                inputTokens = dispatchUsage?.first,
                outputTokens = dispatchUsage?.second?.first,
                totalTokens = dispatchUsage?.second?.second
            ))
            // Soft-nudge: if the policy requires a rationale and the dispatch LLM
            // emitted null/blank rationale, append a Hint to turn history so the
            // next dispatch LLM sees the field it forgot.
            applyRationaleNudgeIfNeeded(pathRequest, pathRequest.pathSelectionRationale)
            return pathRequest
        }

        // Parse failed
        if (!failurePolicy.repairInvalidDispatchJson) break
        if (repairAttempts >= failurePolicy.maxDispatchRepairAttempts) break
        repairAttempts++

        val repairPrompt = buildRepairPrompt(result)
        result = runAgent(dispatchAgent, repairPrompt)
    }

    // Repair exhausted
    emitEventInternal(DispatchCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        selectedPathName = null,
        pathRequest = null,
        result = result,
        inputTokens = dispatchUsage?.first,
        outputTokens = dispatchUsage?.second?.first,
        totalTokens = dispatchUsage?.second?.second
    ))
    emitEventInternal(PathFailed(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        pathName = "(unknown)",
        riskLevel = PathRiskLevel.Low,
        error = PumpStationError.DispatchJsonRepairFailed,
        errorMessage = "Repair exhausted after $repairAttempts attempts"
    ))
    if (failurePolicy.stopHarnessOnInvalidPathRequest)
{
        taskState.lastError = PumpStationError.DispatchJsonRepairFailed
    }
    return null
}

/**
 * Multi-path dispatch phase. Mirrors [runDispatchPhase] but parses a
 * [PathRequestList] and launches every parsed path via the existing async
 * substrate. Emits [PathBatchStarted] before fan-out, [PathBatchCompleted]
 * after fan-out, [PathBatchFailed] on parse/repair exhaustion.
 *
 * Returns `null` on success — `runTurn`'s "no foreground path this turn"
 * branch then continues the loop. The async paths' results drain at the
 * next judge via [drainPendingAsyncResults] and merge into turn history.
 *
 * Phase 2 will replace this null-return pattern with structured concurrency
 * (coroutineScope / async / awaitAll) and proper halt-flag aggregation.
 */
internal suspend fun PumpStation.runDispatchPhaseMulti(): PathRequest?
{
    taskState.phase = PumpStationPhase.Dispatch
    emitEventInternal(DispatchStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex
    ))

    val baseInput = if (taskState.latestContent != null && taskState.latestContent!!.text.isNotEmpty())
    {
        val enriched = buildTurnContent()
        enriched.copy(text = "[LATEST PRIOR AGENT OUTPUT]\n" + taskState.latestContent!!.text +
            "\n[/LATEST PRIOR AGENT OUTPUT]\n\n" + enriched.text)
    }
    else buildTurnContent()
    val input = preValidationDispatchFunctionInternal?.invoke(baseInput, contextWindow, miniBank, this)
        ?.let { baseInput.copy(miniBankContext = it) } ?: baseInput

    var result = runAgent(dispatchAgent, input)

    val dispatchFlags = checkMultimodalFlags(result, "Dispatch")
    if (dispatchFlags.shouldHalt)
{
        taskState.lastError = PumpStationError.P2PRequestInvalid
        return null
    }

    postGenerateFunctionInternal?.invoke(result, this)?.let { returnedAgent ->
        result.metadata["postGenerateAgent"] = returnedAgent
    }

    var repairAttempts = 0
    val dispatchUsage = agentTokenUsage(dispatchAgent)
    recordAndCheckKillSwitch(dispatchAgent)

    while (repairAttempts <= failurePolicy.maxDispatchRepairAttempts)
{
        val flags = checkMultimodalFlags(result, "Dispatch")
        if (flags.shouldHalt)
{
            taskState.lastError = PumpStationError.P2PRequestInvalid
            return null
        }
        val pathRequestList = parseDispatchOutputMulti(result)
        if (pathRequestList != null && pathRequestList.paths.isNotEmpty())
{
            emitEventInternal(PathBatchStarted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                pathNames = pathRequestList.paths.map { it.pathName },
                batchRationale = pathRequestList.batchRationale
            ))

            var succeededCount = 0
            var failedCount = 0
            for (request in pathRequestList.paths)
{
                val resolvedPath = resolvePath(request.pathName)
                if (resolvedPath == null)
{
                    failedCount++
                    emitEventInternal(PathFailed(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        pathName = request.pathName,
                        riskLevel = PathRiskLevel.Low,
                        error = PumpStationError.UnknownPath,
                        errorMessage = "Path '${request.pathName}' not found in batch"
                    ))
                    continue
                }
                val pathInput = buildPathInput(resolvedPath, request)
                try
{
                    launchAsyncPath(resolvedPath, pathInput)
                    succeededCount++
                }
                catch (e: Exception)
{
                    failedCount++
                    emitEventInternal(PathFailed(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        pathName = request.pathName,
                        riskLevel = resolvedPath.riskLevel,
                        error = classifyPathException(e),
                        errorMessage = e.message
                    ))
                }
            }

            emitEventInternal(PathBatchCompleted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                totalPaths = pathRequestList.paths.size,
                succeededPaths = succeededCount,
                failedPaths = failedCount
            ))

            emitEventInternal(DispatchCompleted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                selectedPathName = pathRequestList.paths.firstOrNull()?.pathName,
                pathRequest = pathRequestList.paths.firstOrNull(),
                result = result,
                inputTokens = dispatchUsage?.first,
                outputTokens = dispatchUsage?.second?.first,
                totalTokens = dispatchUsage?.second?.second
            ))

            return null
        }

        if (!failurePolicy.repairInvalidDispatchJson) break
        if (repairAttempts >= failurePolicy.maxDispatchRepairAttempts) break
        repairAttempts++

        val repairPrompt = buildMultiPathRepairPrompt(result)
        result = runAgent(dispatchAgent, repairPrompt)
    }

    emitEventInternal(PathBatchFailed(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        errorMessage = "Multi-path repair exhausted after $repairAttempts attempts",
        repairAttempts = repairAttempts
    ))
    if (failurePolicy.stopHarnessOnInvalidPathRequest)
{
        taskState.lastError = PumpStationError.DispatchJsonRepairFailed
    }
    return null
}

/**
 * Build a repair prompt asking the dispatch agent to fix its malformed JSON.
 */
internal fun PumpStation.buildRepairPrompt(badOutput: MultimodalContent): MultimodalContent
{
    val repairText = """
[Harness Notice] Your previous dispatch output was not parseable as a PathRequest JSON.
Previous output: ${badOutput.text.take(maxRepairPromptTokensInternal)}

Please retry with a valid PathRequest JSON object. The schema is:
{
  "pathName": "the exact path name from the visible list",
  "inputData": { ... path-specific input fields ... }
}
""".trimIndent()
    return MultimodalContent(text = repairText)
}

/**
 * Multi-path variant of [buildRepairPrompt]. Targets the [PathRequestList]
 * schema when the station is in [PathExecutionShape.MultiPath] mode.
 */
internal fun PumpStation.buildMultiPathRepairPrompt(badOutput: MultimodalContent): MultimodalContent
{
    val repairText = """
[Harness Notice] Your previous dispatch output was not parseable as a PathRequestList JSON.
Previous output: ${badOutput.text.take(maxRepairPromptTokensInternal)}

Please retry with a valid PathRequestList JSON object. The schema is:
{
  "paths": [
    {"pathName": "...", "pathSchema": "...", "pathSelectionRationale": "..."}
  ],
  "batchRationale": "..."
}
""".trimIndent()
    return MultimodalContent(text = repairText)
}

/**
 * Resolve the path and call invokePath(). On unknown path, emit PathFailed
 * and replace latestContent with an LLM-targeted error message.
 */
/**
 * Resolve the path and call invokePath(). On unknown path, emit PathFailed
 * and replace latestContent with an LLM-targeted error message.
 *
 * Returns the [MultimodalContent] produced by the path, so the caller
 * ([runTurn]) can inspect `passPipeline` / `terminatePipeline` flags and act on
 * them (e.g. trigger [PumpStationExitReason.PassSignal] or halt with
 * [PumpStationError.PathExecutionException]). Returns null when the path is
 * not found.
 */
internal suspend fun PumpStation.runPathFlow(request: PathRequest): MultimodalContent?
{
    val path = resolvePath(request.pathName)
    if (path == null)
{
        emitEventInternal(PathFailed(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            pathName = request.pathName,
            riskLevel = PathRiskLevel.Low,
            error = PumpStationError.UnknownPath,
            errorMessage = "Path '${request.pathName}' not found"
        ))
        taskState.latestContent = MultimodalContent(
            text = buildLlmErrorMessage(
                PumpStationError.UnknownPath,
                mapOf("pathName" to request.pathName, "availablePaths" to getVisiblePathNames())
            )
        )
        // maxConsecutiveUnknownPaths guard: bump the streak counter and trip
        // when the limit is reached. Mirrors the maxConsecutiveSamePath trip
        // at PumpStation.kt:3074-3098 — emit LoopGuardTripped, set the
        // exitReason + lastError, mark latestContent.terminatePipeline so
        // the runTurn halt path at PumpStationLoop.kt:2897-2900 stops the
        // harness. Reset the counter on the trip so a subsequent run on
        // a different station doesn't inherit the streak.
        consecutiveUnknownPathCount += 1
        val limit = maxConsecutiveUnknownPathsInternal
        if (limit != null && consecutiveUnknownPathCount >= limit)
        {
            emitEventInternal(LoopGuardTripped(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                guard = "maxConsecutiveUnknownPaths",
                pathName = request.pathName,
                detail = "consecutive=$consecutiveUnknownPathCount, limit=$limit",
                metric = "consecutive",
                observed = consecutiveUnknownPathCount,
                limit = limit
            ))
            emitEventInternal(PathFailed(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                phase = PumpStationPhase.PathExecution,
                pathName = request.pathName,
                riskLevel = PathRiskLevel.Low,
                error = PumpStationError.LoopGuardTriggered,
                errorMessage = "maxConsecutiveUnknownPaths exceeded for path '${request.pathName}'"
            ))
            taskState.latestContent = (taskState.latestContent ?: MultimodalContent())
                .also { it.terminatePipeline = true }
            taskState.lastError = PumpStationError.LoopGuardTriggered
            taskState.exitReason = PumpStationExitReason.LoopGuardTripped
            consecutiveUnknownPathCount = 0
            // Return a non-null result with terminatePipeline set so the
            // runTurn halt path at PumpStationLoop.kt:2897-2900 (which reads
            // `pathResult.terminatePipeline`) stops the harness. Mirrors the
            // maxConsecutiveSamePath trip mechanic at PumpStation.kt:3093-3098.
            return MultimodalContent(text = request.pathName)
                .also { it.terminatePipeline = true }
        }
        return null
    }
    // Resolved path — the LLM picked a real name. Reset the streak so a
    // subsequent run of unknown paths starts a fresh count.
    consecutiveUnknownPathCount = 0
    val input = buildPathInput(path, request)

    // Async path: launch on the station-scoped scope, enqueue a PendingTurnEntry
    // on completion, and return immediately so the foreground turn continues.
    // The result lands in turnHistory on the next foreground drain
    // (start of judge phase or start of finalization).
    if (path.isRunsInBackground)
    {
        launchAsyncPath(path, input)
        return null
    }

    return invokePathInternal(path, input)
}

/**
 * Launch an async path execution on the station's asyncScope. Captures the
 * path's result into a [PendingTurnEntry] tagged with a monotonic [seq] and
 * enqueues it for the foreground drain. Exceptions are caught and reported
 * as [PathFailed] events so they don't escape into the harness's exception
 * boundary.
 */
private fun PumpStation.launchAsyncPath(path: PathObject, input: MultimodalContent)
{
    val pathName = path.pathName
    val riskLevel = path.riskLevel
    val seq = asyncSeqCounterInternal.incrementAndGet()
    val turnIndexSnapshot = taskState.turnIndex
    val dispatcher = asyncScope

    val launchOn: kotlinx.coroutines.CoroutineScope =
        if (asyncJobsScopedToStationInternal) dispatcher
        else kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.GlobalScope.coroutineContext)
    val semaphore = kotlinx.coroutines.sync.Semaphore(
        permits = maxConcurrentBackgroundAgentsInternal.coerceAtLeast(1)
    )

    launchOn.launch {
        try
        {
            val result = semaphore.withPermit { invokePathInternal(path, input) }
            val entry = PendingTurnEntry(
                seq = seq,
                turnIndex = turnIndexSnapshot,
                pathName = pathName,
                agentName = null,
                source = "asyncPath",
                result = result,
                inputTokens = null,
                outputTokens = null,
                totalTokens = null,
                passPipeline = result.passPipeline,
                terminatePipeline = result.terminatePipeline
            )
            pendingAsyncResultsInternal.trySend(entry)
        }
        catch (e: kotlinx.coroutines.CancellationException)
        {
            // Honour cancellation: do not enqueue a partial result.
            throw e
        }
        catch (e: Throwable)
        {
            emitEventInternal(PathFailed(
                runId = taskState.runId,
                turnIndex = turnIndexSnapshot,
                phase = PumpStationPhase.PathExecution,
                pathName = pathName,
                riskLevel = riskLevel,
                error = classifyPathException(e),
                errorMessage = e.message
            ))
        }
    }
}

/**
 * Build the input MultimodalContent for a path execution.
 *
 * The dispatch LLM's `pathSchema` field carries the path's input shape, but a
 * chat-mode LLM can freely emit non-JSON content there (e.g.
 * `"pathSchema": "Hello I am not valid JSON"`). Concatenating that raw string
 * into the path LLM's prompt makes the path obediently research the schema
 * text instead of the user's topic.
 *
 * Warn-and-continue contract:
 *   - When the dispatch emits a non-empty `pathSchema`, we try to round-trip
 *     it through [extractJson]<[PathRequest]>. If that fails (or produces a
 *     default schema), the dispatch-emitted schema is treated as garbage.
 *     We:
 *     1. Append a `[Harness Notice]` hint to `turnHistory` so the next
 *        dispatch LLM sees the constraint (see
 *        [PumpStation.buildPathSchemaFallbackMessage]); and
 *     2. Use the path's own canonical [PathObject.pathSchema] as the
 *        authoritative input, so the path LLM sees the real research topic
 *        plus the path's own schema — never the dispatch's garbage.
 *   - When the dispatch emits a valid JSON schema, it passes through
 *     unchanged (canonical pattern is pathName-only dispatch where the
 *     field is empty; runtime-customized schemas remain supported).
 */
internal fun PumpStation.buildPathInput(path: PathObject, request: PathRequest): MultimodalContent
{
    val base = buildTurnContent()
    /*
     * Validate the dispatch-emitted schema before merging it with the user's
     * input. A non-JSON schema is treated as garbage and discarded with a
     * hint; we then fall back to the path's own canonical schema. The user's
     * original input is the ground-truth topic — it must reach the path LLM
     * unconditionally.
     */
    val dispatchSchema = request.pathSchema
    /*
     * Treat the dispatch's pathSchema as valid only when it round-trips
     * through a JSON parse and yields a JsonObject envelope (the schema ought
     * to be a JSON object, not a list/scalar/string). This mirrors
     * [com.TTT.Pipeline.PumpStationHelpers.parseDispatchOutput]'s contract
     * so the validity bar is consistent: a string that does not decode as a
     * JSON object is treated as garbage and falls into the [Harness Notice]
     * path.
     */
    /*
     * We deliberately use [kotlinx.serialization.json.Json.parseToJsonElement]
     * here (NOT [extractJson]<[PathRequest]>) because round-tripping through
     * [PathRequest] requires the kotlinx-serialization compiler plugin,
     * which is unavailable under direct kotlinc execution in this sandbox.
     * The semantics are identical for the dispatch validation: a non-empty
     * JSON-object-shaped string passes; everything else (prose, partial JSON,
     * lists, scalars, etc.) is treated as garbage. The fallback to
     * [PathObject.pathSchema] and the [Harness Notice] hint are unchanged.
     */
    val dispatchSchemaIsValid = dispatchSchema.isNotEmpty() &&
        runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(dispatchSchema)
            element is kotlinx.serialization.json.JsonObject
        }.getOrDefault(false)
    val effectiveSchema = when
    {
        dispatchSchema.isEmpty()       -> path.pathSchema
        dispatchSchemaIsValid          -> dispatchSchema
        else                           -> run {
            /*
             * Append a [Harness Notice] hint so the next dispatch LLM sees
             * the constraint and self-corrects. We always include the
             * dispatch-emitted garbage in the hint detail map so the next
             * turn's dispatch LLM knows what was filtered.
             */
            turnHistory.add(
                ConverseData(
                    role = ConverseRole.harness,
                    content = MultimodalContent(
                        text = buildPathSchemaFallbackMessage(
                            mapOf(
                                "pathName" to request.pathName,
                                "output" to dispatchSchema
                            )
                        )
                    )
                )
            )
            path.pathSchema
        }
    }
    val originalInputText = taskState.originalInput?.text?.takeIf { it.isNotBlank() }
    base.text = when
    {
        originalInputText != null && effectiveSchema.isNotEmpty() -> "$originalInputText\n\n$effectiveSchema"
        originalInputText != null -> originalInputText
        effectiveSchema.isNotEmpty() -> effectiveSchema
        else -> base.text
    }
    base.metadata.putAll(
        mutableMapOf<Any, Any>(
            "selectedPath" to path.pathName,
            "pathRequest" to request
        )
    )
    return base
}

//=========================================Group I: Health/Memory/Compaction==========================================
// Shared file-level job tracking for background memory agents. Lives at file scope
// because background agents may be queued from suspend functions across many
// turn invocations; the mutex guarantees only one queue mutation at a time.

internal val backgroundJobs: MutableList<Job> = mutableListOf()
internal val backgroundMutex: Mutex = Mutex()

/**
 * Returns true if the current context fill ratio exceeds the configured
 * [compactionThreshold]. Used by all three compaction strategies to gate
 * whether compaction should fire this turn.
 */
internal fun PumpStation.shouldCompact(): Boolean =
    contextFillRatio() > compactionThresholdInternal

//=========================================v3: Pre-prune==========================================================

/**
 * Pre-prune step applied to the raw [turnHistory] before it reaches the summary agent.
 * Pure-Kotlin transforms that drop noise the LLM doesn't need to see, so the LLM cost
 * of compaction is paid on the smallest possible input. The default implementation is
 * the chain in [defaultPrePruneForCompaction]; the developer can replace it via
 * [PumpStation.setPrePruneTransform] or extend it via
 * [PumpStation.appendPrePruneTransform].
 */
internal suspend fun PumpStation.prePruneForCompaction(rawTurns: List<ConverseData>): List<ConverseData>
{
    val custom = prePruneTransformInternal
    val baseList = if (custom != null)
    {
        custom.invoke(rawTurns, this)
    }
    else
    {
        defaultPrePruneForCompaction(rawTurns)
    }
    var result = baseList
    for (extra in extraPrePruneTransformsInternal)
    {
        result = extra.invoke(result, this)
    }
    return result
}

/**
 * Default pre-prune transform. The eight rules run in order; each drops or rewrites
 * a specific kind of noise:
 *  1. Drop blank turns (no text, no binary, no context).
 *  2. Drop stash placeholders (the full content is already in `stashInternal`).
 *  3. Keep only the most-recent system message.
 *  4. Drop pure echo turns (text exactly equal to the previous turn's text).
 *  5. Collapse adjacent tool-call/result pairs.
 *  6. Strip excess metadata keys (keep only the allowlist).
 *  7. Normalize whitespace (collapse runs of newlines, trim per turn).
 *  8. Drop turns already in [turnSummary] (case-insensitive contains check).
 */
internal fun PumpStation.defaultPrePruneForCompaction(rawTurns: List<ConverseData>): List<ConverseData>
{
    val turnSummaryLower = turnSummary.lowercase()

    // Rule 1: drop blank
    var turns = rawTurns.filter { turn ->
        turn.content.text.isNotBlank() || turn.content.binaryContent.isNotEmpty()
    }

    // Rule 2: drop stash placeholders
    turns = turns.filter { it.content.metadata["stashId"] == null }

    // Rule 3: keep only the most-recent system message
    val lastSystemIndex = turns.indexOfLast { it.role == ConverseRole.system }
    turns = turns.filterIndexed { i, turn ->
        turn.role != ConverseRole.system || i == lastSystemIndex
    }

    // Rule 4: drop pure echoes (text exactly equal to previous turn's text after trim)
    turns = turns.fold(mutableListOf<ConverseData>()) { acc, turn ->
        val last = acc.lastOrNull()
        if (last != null && last.content.text.trim() == turn.content.text.trim())
        {
            acc  // drop this echo
        }
        else
        {
            acc.add(turn)
            acc
        }
    }.toList()

    // Rule 5: collapse adjacent tool-call/result pairs into a single summary turn.
    // Heuristic: a ConverseRole.tool_response turn followed by a ConverseRole.assistant turn
    // whose text is JSON containing a "name" field that names the same tool. Best
    // effort; drops the pair and inserts a single assistant turn noting the call.
    val collapsed = mutableListOf<ConverseData>()
    var i = 0
    while (i < turns.size)
    {
        val current = turns[i]
        val next = turns.getOrNull(i + 1)
        if (current.role == ConverseRole.tool_response && next != null && next.role == ConverseRole.assistant)
        {
            collapsed.add(ConverseData(
                role = ConverseRole.assistant,
                content = MultimodalContent(text = "[tool-call: ${current.content.text.take(120)}]")
            ))
            i += 2
        }
        else
        {
            collapsed.add(current)
            i += 1
        }
    }
    turns = collapsed

    // Rule 6: strip excess metadata (keep only the allowlist).
    val allowedMeta = setOf("stashId", "pathName", "toolName", "tokenCount")
    turns = turns.map { turn ->
        if (turn.content.metadata.isEmpty()) turn
        else
        {
            val kept = turn.content.metadata.filterKeys { it in allowedMeta }
            if (kept.size == turn.content.metadata.size) turn
            else
            {
                val c = turn.content.copy()
                c.metadata.clear()
                c.metadata.putAll(kept)
                ConverseData(role = turn.role, content = c)
            }
        }
    }

    // Rule 7: normalize whitespace per turn.
    turns = turns.map { turn ->
        val normalized = turn.content.text.replace(Regex("\n{3,}"), "\n\n").trim()
        if (normalized == turn.content.text) turn
        else
        {
            val c = turn.content.copy()
            c.text = normalized
            ConverseData(role = turn.role, content = c)
        }
    }

    // Rule 8: drop turns already in turnSummary (case-insensitive contains).
    if (turnSummaryLower.isNotBlank())
    {
        turns = turns.filter { turn ->
            val text = turn.content.text.lowercase()
            !turnSummaryLower.contains(text)
        }
    }

    return turns
}

//=========================================v3.5: SafePrune phase================================================

/**
 * Run the optional SafePrune phase. Fires when [safePruneEnabledInternal] is true
 * and the size gate is met. Each enabled [SafePruneStrategy] runs in declared order
 * against the same turnHistory snapshot; later strategies see the output of earlier
 * ones. The protect-recent-N tail of the history is never mutated.
 *
 * No LLM call. Emits a single [SafePruneApplied] event with a [SafePruneReport] when
 * at least one strategy fires and the size gate is met. When disabled or below
 * threshold, no event is emitted.
 */
internal suspend fun PumpStation.runSafePrunePhase()
{
    if (!safePruneEnabledInternal) return
    if (safePruneEnabledStrategiesInternal.isEmpty()) return
    if (turnHistory.history.size <= safePruneSizeThresholdInternal) return

    val originalCount = turnHistory.history.size
    val originalChars = turnHistory.history.sumOf { it.content.text.length }

    val snapshot = turnHistory.history.toList()
    val globalProtectBoundary = resolveSafePruneProtectBoundary(safePruneProtectRecentNInternal, snapshot.size)

    val enabledSnapshot = safePruneEnabledStrategiesInternal.toSet()
    val dryRunSet = safePruneStrategyDryRunInternal
    val isAnyDryRun = enabledSnapshot.any { it in dryRunSet }

    // Two parallel pipelines: the actual list (only non-dry-run strategies applied)
    // and the hypothetical list (all strategies applied, including dry-run).
    // The hypothetical list is what the SafePruneDryRunCompleted report carries;
    // the actual list is what replaces turnHistory when no dry-run is active.
    var workingActual = snapshot
    var workingHypothetical = snapshot

    fun runStrategy(
        strategy: SafePruneStrategy,
        apply: (List<ConverseData>, Int) -> List<ConverseData>,
        hypotheticalApply: (List<ConverseData>, Int) -> List<ConverseData> = apply
    )
    {
        if (strategy !in enabledSnapshot) return
        val boundary = resolveStrategyBoundary(strategy, snapshot.size, globalProtectBoundary)
        if (strategy !in dryRunSet)
        {
            workingActual = apply(workingActual, boundary)
        }
        workingHypothetical = hypotheticalApply(workingHypothetical, boundary)
    }

    runStrategy(SafePruneStrategy.ReplaceWithSummaryRef,
        { entries, boundary -> applyReplaceWithSummaryRef(entries, boundary, turnSummary) })
    runStrategy(SafePruneStrategy.DropPureEchoes,
        { entries, boundary -> applyDropPureEchoes(entries, boundary) })
    runStrategy(SafePruneStrategy.CollapseToolCallResults,
        { entries, boundary -> applyCollapseToolCallResults(entries, boundary) })
    runStrategy(SafePruneStrategy.DeduplicateByHash,
        { entries, boundary -> applyDeduplicateByHash(entries, boundary, safePruneHashWindowInternal.coerceAtLeast(1)) })
    runStrategy(SafePruneStrategy.StripLongToolArguments,
        { entries, boundary -> applyStripLongToolArguments(entries, boundary, safePruneMaxToolArgLengthInternal.coerceAtLeast(1)) })
    runStrategy(SafePruneStrategy.MetadataOnlyCompression,
        { entries, boundary -> applyMetadataOnlyCompression(entries, boundary) })

    // Determine if any mutation happened. Use the hypothetical list for the report
    // (which is what the dry-run consumer cares about) and the actual list for the
    // mutation (which is what turnHistory will become).
    val actualChars = workingActual.sumOf { it.content.text.length }
    val actualCountChanged = workingActual.size != snapshot.size
    val actualTextChanged = actualChars != originalChars

    if (!actualCountChanged && !actualTextChanged && !isAnyDryRun)
    {
        // No strategy produced a mutation. Skip both event emissions.
        return
    }

    val hypotheticalChars = workingHypothetical.sumOf { it.content.text.length }
    val hypotheticalTokensRemoved = (originalChars - hypotheticalChars).coerceAtLeast(0) / 4
    val hypotheticalCount = workingHypothetical.size

    // The report always describes the HYPOTHETICAL effect (full strategy sweep).
    // That way observers comparing reports across dry-run on/off see consistent numbers.
    val report = SafePruneReport(
        enabledFlags = enabledSnapshot,
        originalCount = originalCount,
        finalCount = hypotheticalCount,
        tokensRemoved = hypotheticalTokensRemoved,
        firedAtTurnIndex = taskState.turnIndex
    )

    if (isAnyDryRun)
    {
        // Dry-run strategies don't get applied, but non-dry-run strategies DO.
        // The history still reflects the partial (non-dry-run) effect; the report
        // describes the full hypothetical effect for observability.
        turnHistory.history.clear()
        turnHistory.history.addAll(workingActual)
        emitEventInternal(SafePruneDryRunCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = PumpStationPhase.SafePruneDryRun,
            report = report
        ))
        return
    }

    turnHistory.history.clear()
    turnHistory.history.addAll(workingActual)

    emitEventInternal(SafePruneApplied(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        phase = PumpStationPhase.SafePrune,
        report = report
    ))
}

/**
 * Resolve the protectBoundary from a protectRecentN value. Clamps to [0, snapshotSize].
 */
internal fun resolveSafePruneProtectBoundary(protectRecentN: Int, snapshotSize: Int): Int
{
    val protectN = protectRecentN.coerceAtLeast(0)
    return (snapshotSize - protectN).coerceAtLeast(0)
}

/**
 * Resolve the effective protectBoundary for a single strategy, honouring any
 * per-strategy [SafePrunePolicy] override. Falls back to the PumpStation-global
 * boundary when the policy is null or when the policy's protectRecentN is null.
 *
 * The caller passes [snapshotSize] (the current turnHistory size) so the boundary
 * is computed against the actual history, not against the strategies set.
 */
internal fun PumpStation.resolveStrategyBoundary(
    strategy: SafePruneStrategy,
    snapshotSize: Int,
    globalBoundary: Int
): Int
{
    val policy = safePruneStrategyPoliciesInternal[strategy]
    val effectiveProtectN = policy?.protectRecentN ?: return globalBoundary
    return resolveSafePruneProtectBoundary(effectiveProtectN, snapshotSize)
}

/**
 * Strategy A: rewrite entries older than [protectBoundary] whose text already appears
 * in [turnSummary] to a `[See turnSummary]` marker. Metadata preserved.
 */
internal fun applyReplaceWithSummaryRef(
    entries: List<ConverseData>,
    protectBoundary: Int,
    turnSummary: String
): List<ConverseData>
{
    if (turnSummary.isBlank() || protectBoundary >= entries.size) return entries
    val summaryLower = turnSummary.lowercase()
    return entries.mapIndexed { index, turn ->
        if (index < protectBoundary)
        {
            val text = turn.content.text
            if (text.isNotBlank() && summaryLower.contains(text.lowercase()))
            {
                val rewritten = turn.content.copy()
                rewritten.text = "[See turnSummary]"
                ConverseData(role = turn.role, content = rewritten)
            }
            else turn
        }
        else turn
    }
}

/**
 * Strategy B: drop entries whose trimmed text matches the immediately-preceding entry's
 * trimmed text. Applied to entries older than [protectBoundary].
 */
internal fun applyDropPureEchoes(
    entries: List<ConverseData>,
    protectBoundary: Int
): List<ConverseData>
{
    if (entries.isEmpty()) return entries
    val result = mutableListOf<ConverseData>()
    for ((index, turn) in entries.withIndex())
    {
        if (index < protectBoundary)
        {
            // index is within the eligible region — apply echo check
            val last = result.lastOrNull()
            if (last != null && last.content.text.trim() == turn.content.text.trim() && turn.content.text.isNotBlank())
            {
                // drop pure echo
            }
            else
            {
                result.add(turn)
            }
        }
        else
        {
            // protected region — never mutate
            result.add(turn)
        }
    }
    return result
}

/**
 * Strategy C: collapse adjacent agent/tool_result pairs into a single assistant
 * turn with a `[tool-call: {preview}]` marker. Applied to entries older than [protectBoundary].
 *
 * Heuristic: a ConverseRole.agent turn followed by any of the tool-response roles
 * (tool_response / pcp_response / mcp_response) is treated as a single tool invocation
 * and replaced with a marker that preserves a 120-char preview of the call text.
 */
internal fun applyCollapseToolCallResults(
    entries: List<ConverseData>,
    protectBoundary: Int
): List<ConverseData>
{
    if (entries.size < 2) return entries
    val toolResponseRoles = setOf(
        ConverseRole.tool_response,
        ConverseRole.pcp_response,
        ConverseRole.mcp_response
    )
    val result = mutableListOf<ConverseData>()
    var index = 0
    while (index < entries.size)
    {
        val current = entries[index]
        val next = entries.getOrNull(index + 1)

        if (index >= protectBoundary)
        {
            // protected region — never mutate
            result.add(current)
            index += 1
            continue
        }

        if (current.role == ConverseRole.agent && next != null && next.role in toolResponseRoles)
        {
            val preview = current.content.text.take(120)
            result.add(ConverseData(
                role = ConverseRole.assistant,
                content = MultimodalContent(text = "[tool-call: $preview]")
            ))
            index += 2
        }
        else
        {
            result.add(current)
            index += 1
        }
    }
    return result
}

/**
 * Strategy D: drop entries whose text SHA-256 matches an earlier entry within the last
 * [hashWindow] positions. Entries older than [protectBoundary] are eligible.
 */
internal fun applyDeduplicateByHash(
    entries: List<ConverseData>,
    protectBoundary: Int,
    hashWindow: Int
): List<ConverseData>
{
    if (entries.isEmpty()) return entries
    val result = mutableListOf<ConverseData>()
    val recentHashes = ArrayDeque<String>(hashWindow)
    for ((index, turn) in entries.withIndex())
    {
        if (index >= protectBoundary)
        {
            // protected region — never mutate
            result.add(turn)
            recentHashes.addLast(hashText(turn.content.text))
            if (recentHashes.size > hashWindow) recentHashes.removeFirst()
            continue
        }
        val hash = hashText(turn.content.text)
        if (turn.content.text.isNotBlank() && hash in recentHashes)
        {
            // drop duplicate within window
        }
        else
        {
            result.add(turn)
            recentHashes.addLast(hash)
            if (recentHashes.size > hashWindow) recentHashes.removeFirst()
        }
    }
    return result
}

/**
 * Strategy E: replace tool_response entries whose text length exceeds [maxLength]
 * with a `[tool-call: {name} — args truncated, was {N} chars]` stub. Entries older than
 * [protectBoundary] are eligible.
 */
internal fun applyStripLongToolArguments(
    entries: List<ConverseData>,
    protectBoundary: Int,
    maxLength: Int
): List<ConverseData>
{
    return entries.mapIndexed { index, turn ->
        if (index < protectBoundary && turn.role == ConverseRole.tool_response && turn.content.text.length > maxLength)
        {
            val name = extractToolName(turn.content.text)
            val stub = "[tool-call: $name — args truncated, was ${turn.content.text.length} chars]"
            val rewritten = turn.content.copy()
            rewritten.text = stub
            ConverseData(role = turn.role, content = rewritten)
        }
        else turn
    }
}

/**
 * Strategy F: drop system-role entries whose text is empty and which carry only
 * metadata. Entries older than [protectBoundary] are eligible. Entries whose metadata
 * includes `pathName` or `tokenCount` are preserved regardless (those keys carry
 * signal for downstream tracing).
 */
internal fun applyMetadataOnlyCompression(
    entries: List<ConverseData>,
    protectBoundary: Int
): List<ConverseData>
{
    val protectedKeys = setOf("pathName", "tokenCount")
    return entries.filterIndexed { index, turn ->
        if (index < protectBoundary &&
            turn.role == ConverseRole.system &&
            turn.content.text.isBlank() &&
            turn.content.metadata.isNotEmpty() &&
            protectedKeys.none { it in turn.content.metadata.keys })
        {
            false
        }
        else true
    }
}

private fun hashText(text: String): String
{
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun extractToolName(toolResponseText: String): String
{
    val match = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(toolResponseText)
    return match?.groupValues?.get(1) ?: "unknown"
}

//=========================================v3: Strategy implementations=============================================

/**
 * Estimated token count for a list of [ConverseData] entries. Used by the [Inflated]
 * check in every strategy: the output's estimated tokens must not exceed the input's.
 * The estimate is a rough character-based heuristic matching the existing
 * [contextFillRatio] math (4 chars per token).
 */
internal fun estimateTokensForCompaction(turns: List<ConverseData>): Int
{
    return turns.sumOf { it.content.text.length } / 4
}

/**
 * Whole-strategy compaction. Summarizes the entire pre-pruned turn history into a
 * single assistant message and clears the previous history.
 *
 * The function takes the pre-pruned turn list and a [capturedGeneration] so the
 * orchestrator owns the cursor. The summary agent is invoked under
 * [summaryMutex][com.TTT.Pipeline.PumpStation.summaryMutex] so concurrent async
 * summary updates serialize against this call. The CAS check on return drops the
 * result if the cursor has moved in the meantime (a concurrent compaction committed
 * first).
 */
internal suspend fun PumpStation.compactWholeWithCapturedState(
    prePrunedTurns: List<ConverseData>,
    capturedGeneration: Long
): CompactionResult
{
    if (summaryAgentInternal == null) return CompactionResult.SkippedNoAgent

    val inputTokens = estimateTokensForCompaction(prePrunedTurns)
    val summaryContent = MultimodalContent(text = prePrunedTurns.joinToString("\n") { "[${it.role}] ${it.content.text}" })

    val summary = summaryMutex.withLock {
        // Re-check the CAS inside the lock so we don't waste an LLM call if a concurrent
        // compaction committed between our entry and our summary-agent invocation.
        if (compactionCursorWrite.generation != capturedGeneration) return@withLock null
        summaryAgentInternal!!.executeLocal(summaryContent)
    } ?: return CompactionResult.DiscardedPreEmpted(
        observedGeneration = capturedGeneration,
        currentGeneration = compactionCursorWrite.generation
    )

    val outputTokens = summary.text.length / 4
    if (outputTokens > inputTokens)
    {
        return CompactionResult.Inflated(inputTokens, outputTokens, attempt = 1)
    }

    // CAS apply
    return if (compactionCursorWrite.generation == capturedGeneration)
    {
        applyCompactionResult(summary, prePrunedTurns.size, capturedGeneration, inputTokens, outputTokens, null)
    }
    else
    {
        CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)
    }
}

/**
 * Sequential chunked strategy. Partitions the pre-pruned turns into roughly equal
 * contiguous chunks, then summarizes each in order, carrying the running summary
 * forward as context for the next chunk. Causal ordering is preserved.
 */
internal suspend fun PumpStation.compactChunkedSequentialWithCapturedState(
    prePrunedTurns: List<ConverseData>,
    capturedGeneration: Long
): CompactionResult
{
    if (summaryAgentInternal == null) return CompactionResult.SkippedNoAgent

    val inputTokens = estimateTokensForCompaction(prePrunedTurns)
    val chunkCount = computeChunkCount(inputTokens, prePrunedTurns.size)
    val chunks = prePrunedTurns.chunkRounded(chunkCount)

    var runningSummary: String? = null
    for ((index, chunk) in chunks.withIndex())
    {
        // Pre-check CAS: bail if a concurrent compaction committed.
        if (compactionCursorWrite.generation != capturedGeneration)
        {
            return CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)
        }
        val prompt = if (runningSummary == null)
        {
            MultimodalContent(text = chunk.joinToString("\n") { "[${it.role}] ${it.content.text}" })
        }
        else
        {
            MultimodalContent(
                text = "PREVIOUS SUMMARY:\n${runningSummary}\n\nNEW TURNS TO INCORPORATE:\n${
                    chunk.joinToString("\n") { "[${it.role}] ${it.content.text}" }
                }"
            )
        }
        val chunkSummary = summaryMutex.withLock {
            if (compactionCursorWrite.generation != capturedGeneration) null
            else summaryAgentInternal!!.executeLocal(prompt)
        } ?: return CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)
        runningSummary = chunkSummary.text
    }

    val finalText = runningSummary ?: return CompactionResult.SkippedBelowThreshold
    val finalContent = MultimodalContent(text = finalText)
    val outputTokens = finalText.length / 4
    if (outputTokens > inputTokens)
    {
        return CompactionResult.Inflated(inputTokens, outputTokens, attempt = 1)
    }
    return if (compactionCursorWrite.generation == capturedGeneration)
    {
        applyCompactionResult(finalContent, prePrunedTurns.size, capturedGeneration, inputTokens, outputTokens, ChunkFanoutMode.Sequential)
    }
    else
    {
        CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)
    }
}

/**
 * Parallel chunked strategy. Partitions the pre-pruned turns into roughly equal
 * chunks, summarizes each concurrently with bounded concurrency (semaphore permit
 * count = [PumpStation.maxParallelChunksInternal]), then folds the per-chunk
 * summaries into one final summary via a second summary-agent call.
 *
 * Cancellation propagates through the [coroutineScope]: if the kill switch trips or
 * the scope is cancelled, in-flight chunk coroutines throw [CancellationException]
 * and the fold is skipped.
 */
internal suspend fun PumpStation.compactChunkedParallelWithCapturedState(
    prePrunedTurns: List<ConverseData>,
    capturedGeneration: Long
): CompactionResult
{
    if (summaryAgentInternal == null) return CompactionResult.SkippedNoAgent

    val inputTokens = estimateTokensForCompaction(prePrunedTurns)
    val chunkCount = computeChunkCount(inputTokens, prePrunedTurns.size)
    val chunks = prePrunedTurns.chunkRounded(chunkCount)
    val permits = maxParallelChunksInternal.coerceAtLeast(1)
    val semaphore = Semaphore(permits = permits)

    val perChunkSummaries: List<MultimodalContent?> = coroutineScope {
        chunks.mapIndexed { idx, chunk ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    summaryMutex.withLock {
                        if (compactionCursorWrite.generation != capturedGeneration) null
                        else
                        {
                            val prompt = MultimodalContent(
                                text = chunk.joinToString("\n") { "[${it.role}] ${it.content.text}" }
                            )
                            summaryAgentInternal!!.executeLocal(prompt)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    // If any chunk saw a moved cursor, the fold is invalid.
    if (perChunkSummaries.any { it == null })
    {
        return CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)
    }

    // Fold the per-chunk summaries into one final summary.
    val foldInput = perChunkSummaries
        .filterNotNull()
        .joinToString("\n\n=== Chunk Boundary ===\n\n") { it.text }
    val folded = summaryMutex.withLock {
        if (compactionCursorWrite.generation != capturedGeneration) null
        else summaryAgentInternal!!.executeLocal(MultimodalContent(text = foldInput))
    } ?: return CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)

    val outputTokens = folded.text.length / 4
    if (outputTokens > inputTokens)
    {
        return CompactionResult.Inflated(inputTokens, outputTokens, attempt = 1)
    }
    return if (compactionCursorWrite.generation == capturedGeneration)
    {
        applyCompactionResult(folded, prePrunedTurns.size, capturedGeneration, inputTokens, outputTokens, ChunkFanoutMode.Parallel)
    }
    else
    {
        CompactionResult.DiscardedPreEmpted(capturedGeneration, compactionCursorWrite.generation)
    }
}

/**
 * Hybrid strategy. Picks Whole if there is enough headroom, otherwise Chunked
 * (with the configured fan-out mode). Skips entirely if there is no current memory
 * pressure.
 */
internal suspend fun PumpStation.compactHybridWithCapturedState(
    prePrunedTurns: List<ConverseData>,
    capturedGeneration: Long,
    attempt: Int
): CompactionResult
{
    val headroom = 1.0 - contextFillRatio()
    return if (headroom >= hybridWholeHeadroomInternal)
    {
        compactWholeWithCapturedState(prePrunedTurns, capturedGeneration)
    }
    else
    {
        if (compactionFanoutModeInternal == ChunkFanoutMode.Parallel)
        {
            compactChunkedParallelWithCapturedState(prePrunedTurns, capturedGeneration)
        }
        else
        {
            compactChunkedSequentialWithCapturedState(prePrunedTurns, capturedGeneration)
        }
    }
}

//=========================================v3: Backup ring + restore==============================================

/**
 * Push a [CompactionBackup] onto the ring buffer. Drops the oldest backup if the
 * buffer exceeds [PumpStation.maxCompactionBackupsInternal].
 */
internal fun PumpStation.pushCompactionBackup(backup: CompactionBackup)
{
    val ring = compactionBackupsInternal
    ring.addLast(backup)
    while (ring.size > maxCompactionBackupsInternal)
    {
        ring.removeFirst()
    }
}

/**
 * Restore the most-recent [CompactionBackup] to the harness. Emits
 * [CompactionRolledBack] so observability can see the rollback. Returns the
 * restored backup so the orchestrator can continue with the retry or handoff.
 *
 * If the DITL [PumpStation.compactionRolledBackFunction] is bound, it runs first
 * and may return a replacement backup that is used instead of the rolled-back one.
 */
internal suspend fun PumpStation.restoreFromBackup(
    backup: CompactionBackup,
    reason: String
): CompactionBackup?
{
    emitEventInternal(CompactionRolledBack(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        backupGeneration = backup.generation,
        reason = reason
    ))

    val ditl = compactionRolledBackFunctionInternal
    val effective = if (ditl != null) ditl.invoke(backup, reason, this) else null

    val apply = effective ?: backup
    turnHistory.history.clear()
    turnHistory.history.addAll(apply.turnHistory)
    taskState.latestContent = apply.latestContent
    contextWindow.loreBookKeys.clear()
    contextWindow.loreBookKeys.putAll(apply.contextWindow.loreBookKeys)
    // MiniBank is a small map; replace wholesale.
    miniBank.contextMap.clear()
    miniBank.contextMap.putAll(apply.miniBank.contextMap)

    // Roll the cursor back so the next attempt can re-claim the slot.
    compactionCursorWrite = compactionCursorWrite.copy(generation = backup.generation)

    return apply
}

/**
 * Compute the number of chunks to use for the Chunked strategy. Returns at least 1,
 * capped by [PumpStation.maxChunksInternal].
 */
internal fun PumpStation.computeChunkCount(inputTokens: Int, turnsCount: Int): Int
{
    if (turnsCount <= 1) return 1
    val byBudget = if (chunkTokenBudgetInternal <= 0) 1 else maxOf(1, inputTokens / chunkTokenBudgetInternal)
    val byTurns = turnsCount
    val raw = minOf(byBudget, byTurns, maxChunksInternal)
    return maxOf(1, raw)
}

/**
 * Partition a list into [chunkCount] roughly-equal contiguous slices. Used by the
 * Chunked strategies. Always returns at least one chunk (the input itself if
 * chunkCount is 1 or the list is small).
 */
internal fun <T> List<T>.chunkRounded(chunkCount: Int): List<List<T>>
{
    if (chunkCount <= 1 || size <= 1) return listOf(this)
    val n = minOf(chunkCount, size)
    val chunkSize = (size + n - 1) / n  // ceil
    return this.chunked(chunkSize)
}

/**
 * Apply a successful compaction result. Replaces [turnHistory] with a single
 * assistant message containing the summary, then advances the [compactionCursor].
 * Returns [CompactionResult.Applied].
 */
internal fun PumpStation.applyCompactionResult(
    summary: MultimodalContent,
    previousHistorySize: Int,
    generation: Long,
    inputTokens: Int,
    outputTokens: Int,
    fanout: ChunkFanoutMode?
): CompactionResult.Applied
{
    turnHistory.history.clear()
    turnHistory.add(ConverseData(role = ConverseRole.assistant, content = summary))
    compactionCursorWrite = CompactionCursor(
        generation = generation,
        lastCompactedTurnIndex = taskState.turnIndex,
        lastCompactionStrategy = compactionStrategyInternal,
        lastCompactionInputTokens = inputTokens,
        lastCompactionOutputTokens = outputTokens,
        lastCompactionTimestamp = System.currentTimeMillis(),
        lastFanoutMode = fanout
    )
    return CompactionResult.Applied(inputTokens, outputTokens, generation, fanout)
}

/**
 * Run the proactive health check. Fires if interval or error ratio triggers.
 */
internal suspend fun PumpStation.runHealthCheckPhase()
{
    if (healthAgentInternal == null) return
    val turnsSinceLast = taskState.turnIndex - lastHealthCheckTurnInternal
    val errorRatio = computeErrorRatio()
    val shouldFire = (healthAgentTurnIntervalInternal != null && turnsSinceLast >= healthAgentTurnIntervalInternal!!) ||
                     (healthAgentErrorRatioThresholdInternal != null && errorRatio >= healthAgentErrorRatioThresholdInternal!!)
    if (!shouldFire) return

    val agent = healthAgentBuilderFunctionInternal?.invoke(this) ?: healthAgentInternal!!
    emitEventInternal(HealthCheckStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex
    ))
    val healthContext = buildHealthContext()
    val result = agent.executeLocal(healthContext)
    val report = parseHealthReport(result)
    if (report.terminateHarness)
    {
        taskState.latestContent?.terminatePipeline = true
    }
    lastHealthCheckTurnInternal = taskState.turnIndex
    emitEventInternal(HealthCheckCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        status = report.status,
        warnings = report.warnings.size,
        terminateHarness = report.terminateHarness
    ))
}

/**
 * Queue memory agents (lorebook, summary) for background execution. Block on
 * them if context fill ratio exceeds [compactionThreshold] so the harness does
 * not advance into a context blowout.
 */
internal suspend fun PumpStation.runMemoryUpdatePhase()
{
    emitEventInternal(MemoryUpdateStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        memoryMode = memoryManagementModeInternal
    ))

    val needsMemory = (lorebookAgentInternal != null || summaryAgentInternal != null)
    val intervalHit = (backgroundTurnIntervalInternal > 0 && taskState.turnIndex % backgroundTurnIntervalInternal == 0)
    val pressure = contextFillRatio() > compactionThresholdInternal

    if (needsMemory && (intervalHit || pressure))
    {
        if (lorebookAgentInternal != null)
        {
            backgroundMutex.withLock {
                backgroundJobs += launchAsyncJob { updateLorebook() }
            }
        }
        if (summaryAgentInternal != null)
        {
            backgroundMutex.withLock {
                backgroundJobs += launchAsyncJob { updateSummary() }
            }
        }
    }

    if (pressure)
    {
        withTimeoutOrNull(memoryUpdateTimeoutMsInternal) {
            backgroundJobs.forEach { it.join() }
        }
        backgroundJobs.clear()
    }

    val result = MemoryActionResult(
        memoryMode = memoryManagementModeInternal,
        memoryStrategy = compactionStrategyInternal,
        loreBookActive = lorebookAgentInternal != null,
        summaryActive = summaryAgentInternal != null,
        compactionPercent = contextFillRatio(),
        budgetSettings = tokenBudgetSettings ?: TokenBudgetSettings()
    )
    taskState.memoryActionResult = result

    // Post-memory DITL hook: fires after the lorebook/summary agents have
    // completed (or the memory-update timeout elapsed). The hook receives the
    // current latestContent and may return a transformed version which
    // replaces it. Allows the developer to scrub secrets, normalize output,
    // or annotate the content before the next phase.
    val currentContent = taskState.latestContent
    if (currentContent != null)
{
        // Flag check at the DITL hook point: if the memory agents (or the
        // path that produced latestContent) signaled halt on the content,
        // surface it through lastError and skip the hook.
        val memFlags = checkMultimodalFlags(currentContent, "Memory")
        if (memFlags.shouldHalt)
{
            taskState.lastError = PumpStationError.P2PRequestInvalid
            return
        }
        if (!memFlags.shouldPass)
{
            postMemoryFunctionInternal?.invoke(currentContent, this)?.let { transformed ->
                taskState.latestContent = transformed
            }
        }
    }

    emitEventInternal(MemoryUpdateCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        memoryMode = memoryManagementModeInternal,
        result = result
    ))
}

/**
 * v3: Run the compaction strategy with multi-attempt retry and pre-emption detection.
 *
 * The phase is split into a [runCompactionAttempt] per-attempt function (which the
 * orchestrator calls in a loop) and this top-level function (which owns the
 * pre/post-DITL hooks and the event emissions). The attempt loop runs up to
 * [PumpStation.maxCompactionAttemptsInternal] times; on [CompactionResult.Inflated]
 * or [CompactionResult.RolledBack], the orchestrator restores the most-recent
 * [CompactionBackup] and retries with a smaller scope. If the retry budget is
 * exhausted, the harness hands off to the existing truncation path. The kill
 * switch is not part of this cascade.
 */
internal suspend fun PumpStation.runCompactionPhase(): CompactionResult
{
    val initialStrategy = compactionStrategyInternal
    val initialMemoryMode = memoryManagementModeInternal

    // Pre-attempt gate: bail before emitting CompactionStarted so observability does
    // not see a started event for an attempt that was never going to run. Mirrors
    // the pre-v3 behavior: no event when below threshold or no summary agent.
    if (!shouldAttemptCompaction(initialStrategy))
    {
        return CompactionResult.SkippedBelowThreshold
    }
    if (summaryAgentInternal == null)
    {
        return CompactionResult.SkippedNoAgent
    }

    emitEventInternal(CompactionStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        strategy = initialStrategy,
        memoryMode = initialMemoryMode
    ))

    preCompactionFunctionInternal?.invoke(
        taskState.latestContent ?: MultimodalContent(),
        turnHistory.history.firstOrNull() ?: ConverseData(role = ConverseRole.system, content = MultimodalContent()),
        turnHistory,
        this
    )

    val beforeSize = turnHistory.history.size
    var workingStrategy: PumpStationCompactionStrategy = initialStrategy
    var workingChunkBudget: Int = chunkTokenBudgetInternal
    var lastResult: CompactionResult = CompactionResult.SkippedBelowThreshold
    var attemptsUsed = 0

    while (attemptsUsed < maxCompactionAttemptsInternal)
    {
        attemptsUsed += 1

        // Pre-attempt gate: bail if the strategy is below its threshold.
        if (!shouldAttemptCompaction(workingStrategy))
        {
            lastResult = CompactionResult.SkippedBelowThreshold
            break
        }
        if (summaryAgentInternal == null)
        {
            lastResult = CompactionResult.SkippedNoAgent
            break
        }

        val result = runCompactionAttempt(workingStrategy, attemptsUsed, workingChunkBudget)
        lastResult = result

        when (result)
        {
            is CompactionResult.Applied,
            is CompactionResult.DiscardedPreEmpted,
            is CompactionResult.SkippedBelowThreshold,
            is CompactionResult.SkippedNoAgent,
            is CompactionResult.SkippedCursorAlreadyAdvanced,
            is CompactionResult.HandedOffToTruncation -> break

            is CompactionResult.Inflated ->
            {
                emitEventInternal(CompactionInflated(
                    runId = taskState.runId,
                    turnIndex = taskState.turnIndex,
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens,
                    attempt = result.attempt,
                    willRetry = attemptsUsed < maxCompactionAttemptsInternal
                ))

                val mostRecent = compactionBackupsInternal.lastOrNull()
                if (mostRecent != null)
                {
                    restoreFromBackup(mostRecent, reason = "Inflated (attempt ${result.attempt})")
                }

                if (attemptsUsed >= maxCompactionAttemptsInternal)
                {
                    lastResult = handOffToTruncation()
                    break
                }

                // Downgrade scope for the next attempt.
                workingStrategy = when (workingStrategy)
                {
                    PumpStationCompactionStrategy.Whole -> PumpStationCompactionStrategy.Chunked
                    PumpStationCompactionStrategy.Chunked -> PumpStationCompactionStrategy.Chunked
                    PumpStationCompactionStrategy.Hybrid -> PumpStationCompactionStrategy.Chunked
                }
                workingChunkBudget = maxOf(1, workingChunkBudget / 2)
            }

            is CompactionResult.RolledBack ->
            {
                if (attemptsUsed >= maxCompactionAttemptsInternal)
                {
                    lastResult = handOffToTruncation()
                    break
                }
                // Strategy stays the same; the rollback is the retry trigger.
            }
        }
    }

    val afterSize = turnHistory.history.size

    postCompactionFunctionInternal?.invoke(
        taskState.latestContent ?: MultimodalContent(),
        turnHistory,
        this
    )

    emitEventInternal(CompactionCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        strategy = initialStrategy,
        memoryMode = initialMemoryMode,
        previousHistorySize = beforeSize,
        newHistorySize = afterSize,
        result = lastResult
    ))

    return lastResult
}

/**
 * v3: Per-attempt orchestrator. Captures a [CompactionBackup], advances the cursor
 * optimistically, runs the pre-prune + strategy, and emits a [CompactionAttemptCompleted]
 * event. The strategy is parameterized by the working [PumpStationCompactionStrategy]
 * and the working chunk token budget so retry-with-smaller-scope can shrink the input.
 */
internal suspend fun PumpStation.runCompactionAttempt(
    strategy: PumpStationCompactionStrategy,
    attempt: Int,
    chunkBudget: Int
): CompactionResult
{
    if (summaryAgentInternal == null) return CompactionResult.SkippedNoAgent

    val capturedGeneration = compactionCursorWrite.generation + 1
    val capturedTurnIndex = taskState.turnIndex
    val rawTurns = turnHistory.history.toList()

    // Capture backup before doing anything irreversible.
    val backup = CompactionBackup(
        generation = capturedGeneration,
        turnIndex = capturedTurnIndex,
        turnHistory = rawTurns,
        latestContent = taskState.latestContent,
        contextWindow = contextWindow.copy(),
        miniBank = miniBank.copy()
    )
    pushCompactionBackup(backup)

    // Optimistic cursor advance.
    compactionCursorWrite = compactionCursorWrite.copy(
        generation = capturedGeneration,
        lastCompactedTurnIndex = capturedTurnIndex,
        lastCompactionStrategy = strategy
    )

    val prePrunedTurns = prePruneForCompaction(rawTurns)

    // Honor the working chunk budget passed in by the orchestrator.
    val savedChunkBudget = chunkTokenBudget
    chunkTokenBudget = chunkBudget
    try
    {
        val result = when (strategy)
        {
            PumpStationCompactionStrategy.Whole -> compactWholeWithCapturedState(prePrunedTurns, capturedGeneration)
            PumpStationCompactionStrategy.Chunked ->
            {
                if (compactionFanoutModeInternal == ChunkFanoutMode.Parallel)
                {
                    compactChunkedParallelWithCapturedState(prePrunedTurns, capturedGeneration)
                }
                else
                {
                    compactChunkedSequentialWithCapturedState(prePrunedTurns, capturedGeneration)
                }
            }
            PumpStationCompactionStrategy.Hybrid -> compactHybridWithCapturedState(prePrunedTurns, capturedGeneration, attempt)
        }

        emitEventInternal(CompactionAttemptCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            attempt = attempt,
            strategy = strategy,
            fanout = compactionFanoutModeInternal.takeIf { strategy == PumpStationCompactionStrategy.Chunked },
            result = result
        ))

        return result
    }
    finally
    {
        chunkTokenBudget = savedChunkBudget
    }
}

/**
 * v3: True if the strategy is configured to fire at the current fill ratio. Mirrors
 * the pre-v3 `shouldCompact()` behavior for the per-strategy case and adds
 * [PumpStationCompactionStrategy.Hybrid] headroom handling.
 */
internal fun PumpStation.shouldAttemptCompaction(strategy: PumpStationCompactionStrategy): Boolean
{
    return when (strategy)
    {
        PumpStationCompactionStrategy.Whole -> shouldCompact()
        PumpStationCompactionStrategy.Chunked -> shouldCompact()
        PumpStationCompactionStrategy.Hybrid -> contextFillRatio() >= compactionThresholdInternal
    }
}

/**
 * v3: Final handoff. Sets the failure state, emits the [CompactionHandedOffToTruncation]
 * event, and returns [CompactionResult.HandedOffToTruncation]. The harness continues;
 * the kill switch is NOT tripped (kill switch is an independent cost-control system).
 */
internal fun PumpStation.handOffToTruncation(): CompactionResult.HandedOffToTruncation
{
    val before = estimateTokensForCompaction(turnHistory.history)
    taskState.lastError = PumpStationError.CompactionInflated
    // Truncate oldest 50% of turn history as the emergency cut. The lorebook holds
    // the canonical facts per the user's design intent; this is just a memory shape fix.
    if (turnHistory.history.isNotEmpty())
    {
        val dropCount = turnHistory.history.size / 2
        repeat(dropCount) { turnHistory.history.removeAt(0) }
    }
    val after = estimateTokensForCompaction(turnHistory.history)
    emitEventInternal(CompactionHandedOffToTruncation(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        contextWindowBefore = before,
        contextWindowAfter = after
    ))
    return CompactionResult.HandedOffToTruncation(before, after)
}

/**
 * Background lorebook update. Locks on [lorebookMutex] so concurrent turns
 * queue their updates in chronological order. v3: tries to parse the agent's
 * response as the typed [LorebookAgentOutput] envelope first; falls back to the
 * legacy free-form JSON path if the typed parse fails. Discards outputs whose
 * [LorebookAgentOutput.compactedThroughTurn] is `<= lorebookCursor.lastUpdatedTurnIndex`
 * (the work has been subsumed by a later update).
 *
 * The legacy free-form JSON contract is preserved (see [applyLorebookUpdates]).
 */
internal suspend fun PumpStation.updateLorebook()
{
    if (lorebookAgentInternal == null) return
    lorebookMutex.withLock {
        val input = buildLorebookAgentInput()
        val response = lorebookAgentInternal!!.executeLocal(MultimodalContent(text = serialize(input, false)))

        val flags = checkMultimodalFlags(response, "Lorebook")
        if (flags.shouldHalt)
        {
            taskState.lastError = PumpStationError.P2PRequestInvalid
            taskState.latestContent = response
            return
        }
        if (flags.shouldPass) return

        // Try the typed envelope first.
        val typed = extractJson<LorebookAgentOutput>(response.text)
        if (typed != null)
        {
            applyTypedLorebookUpdates(typed)
        }
        else
        {
            // Fall back to the legacy free-form JSON path.
            applyLorebookUpdates(response)
        }
    }
}

/**
 * Build the [LorebookAgentInput] the lorebook agent sees. Slices
 * `turnHistory.history` by the cursor so the agent only gets the fresh turns.
 * The pre-prune step is applied first, so blank turns, stash placeholders, etc.
 * are not passed through.
 */
internal suspend fun PumpStation.buildLorebookAgentInput(): LorebookAgentInput
{
    val cursor = lorebookCursorInternal
    val freshTurns = prePruneForCompaction(turnHistory.history.toList())
    return LorebookAgentInput(
        turnsSinceLastUpdate = freshTurns,
        lastLorebookUpdateTurnIndex = cursor.lastUpdatedTurnIndex,
        currentLorebook = contextWindow.loreBookKeys.values.toList(),
        taskContext = LorebookTaskContext(
            task = entryUserPrompt,
            persona = personality,
            systemTask = systemTask,
            userGuidelines = userGuidelines
        ),
        harnessGeneration = compactionCursorInternal.generation
    )
}

/**
 * Construct a fresh [LoreBook] from a [LorebookUpdate]. Helper kept as a top-level
 * function (not an extension on [LoreBook]) because [LoreBook] is a `data class` whose
 * primary constructor parameter is marked `@Transient`, and Kotlin's parser can
 * confuse an `LoreBook().also { ... }` chain with an unresolved expression. Centralizing
 * construction here keeps the apply path short and unambiguous.
 */
internal fun newLoreBookFromUpdate(update: LorebookUpdate): LoreBook
{
    val fresh = LoreBook(false)
    fresh.key = update.key
    fresh.value = update.value
    fresh.weight = update.weight
    fresh.linkedKeys.addAll(update.linkedKeys)
    fresh.aliasKeys.addAll(update.aliasKeys)
    fresh.requiredKeys.addAll(update.requiredKeys)
    return fresh
}

/**
 * Apply a [LorebookAgentOutput] from the typed envelope. Discards outputs whose
 * [LorebookAgentOutput.compactedThroughTurn] is not strictly greater than the
 * current cursor (work has been subsumed). Applies deletions before updates so
 * an update + delete on the same key resolves to "no entry". Advances the
 * [lorebookCursor] on success.
 */
internal fun PumpStation.applyTypedLorebookUpdates(output: LorebookAgentOutput)
{
    val cursor = lorebookCursorInternal
    if (output.compactedThroughTurn <= cursor.lastUpdatedTurnIndex)
    {
        return
    }

    val map = contextWindow.loreBookKeys

    // Deletions first.
    for (key in output.deletions)
    {
        if (key.isNotEmpty()) map.remove(key)
    }

    // Then updates.
    for (update in output.updates)
    {
        if (update.key.isEmpty()) continue
        val existing = map[update.key]
        if (existing == null)
        {
            val fresh = newLoreBookFromUpdate(update)
            map[update.key] = fresh
        }
        else
        {
            when (update.operation)
            {
                LorebookOperation.Merge ->
                {
                    val incoming = newLoreBookFromUpdate(update)
                    existing.combineValue(incoming)
                }
                LorebookOperation.Replace ->
                {
                    existing.value = update.value
                    existing.weight = update.weight
                    existing.linkedKeys.clear()
                    existing.linkedKeys.addAll(update.linkedKeys)
                    existing.aliasKeys.clear()
                    existing.aliasKeys.addAll(update.aliasKeys)
                    existing.requiredKeys.clear()
                    existing.requiredKeys.addAll(update.requiredKeys)
                }
            }
        }
    }

    lorebookCursorWrite = cursor.copy(
        lastUpdatedTurnIndex = output.compactedThroughTurn,
        lastUpdateTimestamp = System.currentTimeMillis(),
        lastUpdateGeneration = compactionCursorInternal.generation
    )
}

/**
 * Parse the lorebook agent's response and apply each update to
 * [ContextWindow.loreBookKeys]. The agent is expected to return either a
 * single [LoreBook] JSON object or a JSON array of [LoreBook] entries.
 * Silently no-ops on parse failure.
 */
internal fun PumpStation.applyLorebookUpdates(response: MultimodalContent)
{
    val updates: List<LoreBook> = extractJson<List<LoreBook>>(response.text)
        ?: extractJson<LoreBook>(response.text)?.let { listOf(it) }
        ?: return

    val map = contextWindow.loreBookKeys
    for (entry in updates)
{
        if (entry.key.isEmpty()) continue
        val existing = map[entry.key]
        if (existing != null)
{
            existing.combineValue(entry)
        } else
        {
            map[entry.key] = entry
        }
    }
}

/**
 * Background summary update. Locks on [summaryMutex] so concurrent turns
 * queue their updates in chronological order.
 */
internal suspend fun PumpStation.updateSummary()
{
    if (summaryAgentInternal == null) return
    summaryMutex.withLock {
        val content = taskState.latestContent ?: MultimodalContent()
        val summaryResult = summaryAgentInternal!!.executeLocal(content)

        // Flag check: the summary agent can signal halt/pass via flags.
        // If the agent's response is marked terminate, we surface that
        // through lastError and skip the summary update.
        val flags = checkMultimodalFlags(summaryResult, "Summary")
        if (flags.shouldHalt)
{
            taskState.lastError = PumpStationError.P2PRequestInvalid
            return
        }
        if (flags.shouldPass) return

        turnSummary = summaryResult.text
    }
}

/**
 * Build the structured JSON context for the health agent. Serializes a
 * [HealthContext] snapshot of the current harness state.
 */
internal fun PumpStation.buildHealthContext(): MultimodalContent
{
    val healthData = HealthContext(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        harnessStatus = taskState.status,
        lastError = taskState.lastError?.name,
        consecutivePathCount = consecutivePathCountInternal,
        lastSelectedPathName = lastSelectedPathNameInternal,
        pathCallCounts = pathCallCounts.toMap(),
        visiblePathNames = getVisiblePathNames(),
        reservePathNames = getReservePathNames(),
        contextFillPercent = contextFillRatio(),
        turnHistorySummary = turnHistory.history.takeLast(5).mapNotNull { it.content.text },
        recentErrors = emptyList()
    )
    val json = serialize(healthData, false)
    return MultimodalContent(text = json)
}

/**
 * Parse a [HealthReport] from a health agent's output. Falls back to a default
 * (Unknown status) report if the JSON cannot be deserialized.
 */
internal fun PumpStation.parseHealthReport(content: MultimodalContent): HealthReport
{
    return try
    {
        deserialize<HealthReport>(content.text) ?: HealthReport()
    }
    catch (e: Exception)
    {
        HealthReport()
    }
}

//=========================================Group J: Foreground/Background Agents====================================

/**
 * Run foreground (Blocking) harness agents at the configured interval.
 * Each agent runs synchronously; the harness awaits its result.
 */
internal suspend fun PumpStation.runForegroundAgentsPhase()
{
    if (foregroundTurnIntervalInternal == 0) return
    if (taskState.turnIndex % foregroundTurnIntervalInternal != 0) return

    for (slot in additionalHarnessAgentSlotsInternal)
{
        if (slot.concurrency != PumpStationConcurrencyMode.Blocking) continue
        val agent = slot.builderFunction?.invoke(this) ?: slot.agent ?: continue
        agent.setParentInterface(this)
        agent.P2PInit()
        val result = agent.executeLocal(buildTurnContent())
        taskState.latestContent = result
        val fgUsage = agentTokenUsage(agent)
        emitEventInternal(ForegroundAgentCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            agentName = agent::class.simpleName ?: "Unknown",
            result = result,
            inputTokens = fgUsage?.first,
            outputTokens = fgUsage?.second?.first,
            totalTokens = fgUsage?.second?.second
        ))
    }
}

/**
 * Queue background (Async) harness agents. They run as coroutines; the harness
 * continues to the next phase.
 *
 * Async work is launched on the station-scoped [asyncScope] (not [GlobalScope])
 * so [cancelAsyncJobs] can guarantee no async coroutine outlives
 * [executeLocal]. Each launched coroutine respects the per-slot
 * [HarnessAgentSlot.appendsToTurnHistory] opt-in: when true, the result is
 * captured into a [PendingTurnEntry] and merged into [turnHistory] by the
 * foreground drain. When false (the default), the result is discarded, which
 * preserves the historical fire-and-forget semantics.
 */
/**
 * Launch an async memory-update job (lorebook or summary) on the station-scoped
 * [asyncScope] so [cancelAsyncJobs] can guarantee no async coroutine outlives
 * [executeLocal]. When the station is configured with
 * [PumpStation.setAsyncJobsScopedToStation] = false, falls back to
 * [GlobalScope] (the pre-substrate behavior).
 */
private fun PumpStation.launchAsyncJob(block: suspend () -> Unit): kotlinx.coroutines.Job
{
    val launchOn: kotlinx.coroutines.CoroutineScope =
        if (asyncJobsScopedToStationInternal) asyncScope
        else kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.GlobalScope.coroutineContext)
    return launchOn.launch { block() }
}

internal suspend fun PumpStation.runBackgroundAgentsPhase()
{
    if (backgroundTurnIntervalInternal == 0) return
    if (taskState.turnIndex % backgroundTurnIntervalInternal != 0) return

    val semaphore = kotlinx.coroutines.sync.Semaphore(
        permits = maxConcurrentBackgroundAgentsInternal.coerceAtLeast(1)
    )
    val dispatcher = asyncScope

    for (slot in additionalHarnessAgentSlotsInternal)
{
        if (slot.concurrency != PumpStationConcurrencyMode.Async) continue
        val appends = slot.appendsToTurnHistory || asyncAgentsAppendToTurnHistoryInternal
        val seq = if (appends) asyncSeqCounterInternal.incrementAndGet() else -1L
        val turnIndexSnapshot = taskState.turnIndex
        val agentNameHint = slot.agent?.let { it::class.simpleName } ?: "HarnessAgent"

        val launchOn: kotlinx.coroutines.CoroutineScope =
            if (asyncJobsScopedToStationInternal) dispatcher
            else kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.GlobalScope.coroutineContext)

        backgroundMutex.withLock {
            backgroundJobs += launchOn.launch {
                try
                {
                    val agent = slot.builderFunction?.invoke(this@runBackgroundAgentsPhase) ?: slot.agent
                    if (agent != null)
                    {
                        agent.setParentInterface(this@runBackgroundAgentsPhase)
                        agent.P2PInit()
                        semaphore.withPermit {
                            val result = agent.executeLocal(this@runBackgroundAgentsPhase.buildTurnContent())
                            if (appends)
                            {
                                val entry = PendingTurnEntry(
                                    seq = seq,
                                    turnIndex = turnIndexSnapshot,
                                    pathName = null,
                                    agentName = agentNameHint,
                                    source = "asyncHarnessAgent",
                                    result = result,
                                    inputTokens = null,
                                    outputTokens = null,
                                    totalTokens = null,
                                    passPipeline = result.passPipeline,
                                    terminatePipeline = result.terminatePipeline
                                )
                                pendingAsyncResultsInternal.trySend(entry)
                            }
                        }
                    }
                }
                catch (e: kotlinx.coroutines.CancellationException)
                {
                    // Honour cancellation: do not enqueue a partial result.
                    throw e
                }
                catch (e: Exception)
                {
                    // Isolate failures
                }
            }
        }
    }
}

//=========================================Group K: Context Blowout Detection========================================

private val stashIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
internal fun PumpStation.generateStashId(): String =
    "stash-${System.currentTimeMillis()}-${stashIdCounter.incrementAndGet()}"

/**
 * Detect context blowout and handle it. Called at every phase boundary.
 * Returns true if blowout was detected and handled, false otherwise.
 */
internal suspend fun PumpStation.detectAndHandleContextBlowout(afterPhase: PumpStationPhase): Boolean
{
    val fillRatio = contextFillRatio()
    if (fillRatio <= blowoutThresholdInternal) return false

    // BLOWOUT DETECTED
    emitEventInternal(ContextBlowoutDetected(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        fillRatio = fillRatio,
        threshold = blowoutThresholdInternal,
        afterPhase = afterPhase
    ))
    if (failurePolicy.stashOversizedOutputs)
{
        val stashId = generateStashId()
        val currentContent = taskState.latestContent ?: MultimodalContent()
        stashInternal[stashId] = ConverseData(role = ConverseRole.assistant, content = currentContent)
        stashManifestInternal.add(StashEntry(
            id = stashId,
            sourcePath = taskState.selectedPathName,
            createdTurn = taskState.turnIndex,
            reason = StashReason.TokenOverflow,
            tokenEstimate = currentContent.toString().length,
            byteSize = currentContent.toString().toByteArray().size.toLong(),
            preview = currentContent.text.take(200)
        ))
        emitEventInternal(StashCreated(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            stashId = stashId,
            sourcePath = taskState.selectedPathName,
            reason = StashReason.TokenOverflow,
            tokenEstimate = currentContent.toString().length
        ))
        // Replace with placeholder
        val placeholder = MultimodalContent(
            text = "[Stashed: $stashId — content was ${currentContent.toString().length} chars due to context blowout. See stash manifest.]",
            context = currentContent.context
        )
        placeholder.metadata.putAll(currentContent.metadata)
        placeholder.metadata["stashId"] = stashId
        taskState.latestContent = placeholder
    }

    preCompactionFunctionInternal?.invoke(
        taskState.latestContent ?: MultimodalContent(),
        turnHistory.history.firstOrNull() ?: ConverseData(role = ConverseRole.system, content = MultimodalContent()),
        turnHistory,
        this
    )

    runCompactionPhase()

    postCompactionFunctionInternal?.invoke(
        taskState.latestContent ?: MultimodalContent(),
        turnHistory,
        this
    )

    onContextTruncatedInternal?.invoke(true, (1.0 - contextFillRatio()).toInt())

    return true
}

//=========================================Group L: Exit Flow with Goal Recursion====================================

/**
 * Run the exit flow. If judge said complete, optionally validate with goal agent.
 * On goal fail, append to history and continue. On goal pass or no goal, halt.
 */
internal suspend fun PumpStation.runExitFlow(turnSnapshot: PumpStationInterruptSnapshot): TurnResult
{
    if (!checkPauseGuards(PumpStationPausePhase.BeforeGoalValidation))
{
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    // Phase boundary: BeforeGoalValidation — poll interrupt then drain steering
    // before the goal agent runs. The turnSnapshot is captured at the top of
    // runTurn; if the goal flow throws an interrupt, the catch in
    // runHarnessLoop will use it to rewind the turn.
    injectInterruptForPhase(PumpStationPausePhase.BeforeGoalValidation, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.BeforeGoalValidation)
    if (goalAgent == null)
{
        val exitContent = buildGoalContent()
        return runPostGoalHook(exitContent)
    }

    val agent = goalAgentBuilderFunction?.invoke(this) ?: goalAgent!!
    agent.setParentInterface(this)
    agent.P2PInit()
    refreshPipelinesPrompts()

    emitEventInternal(GoalValidationStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex
    ))
    val goalContent = buildGoalContent()
    val result = agent.executeLocal(goalContent)
    val passed = !result.terminatePipeline
    emitEventInternal(GoalValidationCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        passed = passed,
        reason = if (!passed) result.text else null
    ))

    if (!passed)
{
        turnHistory.add(ConverseData(role = ConverseRole.assistant, content = result))
        taskState.goalFailCount++
        if (taskState.goalFailCount > maxGoalFailAttemptsInternal)
{
            return TurnResult.Halt(PumpStationExitReason.GoalValidationFailed)
        }
        return TurnResult.Continue
    }

    return runPostGoalHook(result)
}

/**
 * Post-success hook fired from [runExitFlow] on every successful exit (broad coverage
 * including the no-goal-agent and passPipeline-routed paths). Runs [postGoalFunction]
 * synchronously if configured, then resolves and runs [postGoalAgent] /
 * [postGoalAgentBuilderFunction] if either is set. Emits [PostGoalCompleted] with
 * `passed=true, transformedContent=false` when neither is configured so observers
 * still correlate every `runExitFlow` invocation.
 *
 * Mirrors the goal agent's content authority (transformation + terminatePipeline
 * signal) but NOT its control-flow authority — a non-passing post-goal agent halts
 * with [PumpStationExitReason.JudgeComplete] instead of returning `Continue`, to
 * prevent infinite re-loop if a post-goal agent always fails. Loop-back on success
 * exit is the harness's call, not the hook's.
 */
internal suspend fun PumpStation.runPostGoalHook(inputContent: MultimodalContent): TurnResult
{
    val transformed = postGoalFunction?.invoke(inputContent, this)
    val contentForAgent = transformed ?: inputContent
    val transformedContentFlag = transformed != null && transformed !== inputContent

    val resolvedAgent: P2PInterface? = postGoalAgentBuilderFunction?.invoke(this) ?: postGoalAgent

    if (resolvedAgent == null)
{
        emitEventInternal(PostGoalCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            passed = true,
            reason = null,
            transformedContent = transformedContentFlag
        ))
        taskState.latestContent = contentForAgent
        return TurnResult.Halt(PumpStationExitReason.JudgeComplete)
    }

    resolvedAgent.setParentInterface(this)
    resolvedAgent.P2PInit()
    val result = resolvedAgent.executeLocal(contentForAgent)
    val passed = !result.terminatePipeline

    emitEventInternal(PostGoalCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        passed = passed,
        reason = if (!passed) result.text else null,
        transformedContent = transformedContentFlag
    ))

    taskState.latestContent = result

    return TurnResult.Halt(PumpStationExitReason.JudgeComplete)
}

//=========================================Group M: Main Loop Wiring================================================
// The keystone methods that wire all phase helpers into a single harness loop.
// runPreInitPhase runs once at the start, runHarnessLoop drives the turn loop,
// runTurn runs a single iteration, runFinalizationPhase emits completion/failure
// and returns the final content, and drainBackgroundEventQueue flushes queued
// events to the synchronous observer.

//=========================================Pre-Init Phase===========================================================

/**
 * Pre-initialization phase. Runs once at the start of executeLocal. Sets the
 * initial taskState, refreshes agent instances and prompts, runs the optional
 * preInitAgent and preInitFunction, emits HarnessStarted, and advances the
 * phase to Judge so the first runTurn iteration starts cleanly.
 */
internal suspend fun PumpStation.runPreInitPhase(content: MultimodalContent)
{
    taskState.originalInput = content
    taskState.latestContent = content
    taskState.status = PumpStationStatus.Running
    taskState.phase = PumpStationPhase.PreInit

    // Initialize the global PipeTracer stream for this run when tracing is enabled. Doing
    // it here (after P2PInit has set taskState.runId) ensures the trace ID matches the
    // harness's runId and the visualization report can correlate by it.
    if(tracingEnabledInternal && taskState.runId.isNotBlank())
    {
        com.TTT.Debug.PipeTracer.startTrace(taskState.runId)
    }

    refreshAgentInstances()
    refreshPipelinesPrompts()
    refreshSettingsPropagation()

    val initAgent = preInitAgentInternal
    if (initAgent != null)
{
        taskState.latestContent = initAgent.executeLocal(content)
    }
    val initFunction = preInitFunctionInternal
    if (initFunction != null)
{
        taskState.latestContent = initFunction.invoke(content, this)
    }

    emitEventInternal(HarnessStarted(
        runId = taskState.runId,
        turnIndex = 0,
        originalInput = content
    ))

    // No-exit-signal advisory: emit a HarnessWarning when the developer has configured
    // NONE of the four legitimate exit mechanisms documented in [WarningCode.NoExitSignalConfigured]:
    // (1) a judge agent, (2) [requestJudgeNextTurn] bound to a path with judgeRunMode = FlagTriggered,
    // (3) a path that returns [MultimodalContent.passPipeline] = true on success, and
    // (4) a path that returns [MultimodalContent.terminatePipeline] = true on failure.
    // Path (3) and (4) are inferred from the path having a custom executionFunction.
    val hasJudge = judgeAgent != null || judgeAgentBuilderFunction != null
    val isFlagTriggered = judgeRunModeInternal == PumpStationJudgeRunMode.FlagTriggered
    val hasPathExitSignal = pathList.values.any { it.hasExecutionFunction }
    val allowsLongRun = maxTurnsInternal > 1
    if (!hasJudge && !isFlagTriggered && !hasPathExitSignal && allowsLongRun)
    {
        emitEventInternal(HarnessWarning(
            runId = taskState.runId,
            turnIndex = 0,
            code = WarningCode.NoExitSignalConfigured,
            message = "No exit signal configured. Harness will run until maxTurns " +
                "and fail with MaxTurnsExceeded. Configure one of: " +
                "(1) judge agent, " +
                "(2) requestJudgeNextTurn() bound to a path with judgeRunMode = FlagTriggered, " +
                "(3) passPipeline = true on a path result for success exit, " +
                "(4) terminatePipeline = true for failure exit.",
            mechanisms = listOf(
                ExitMechanism.JudgeAlways,
                ExitMechanism.JudgeFlagTriggered,
                ExitMechanism.PathPassPipeline,
                ExitMechanism.PathTerminatePipeline
            )
        ))
    }

    taskState.phase = PumpStationPhase.Judge
}

//=========================================Harness Loop============================================================

/**
 * Main harness loop. Runs turns until [maxTurns] is reached, the task
 * is halted by the judge or exit flow, or the harness is suspended/killed.
 * Sets [PumpStationTaskState.lastError] and [PumpStationTaskState.exitReason]
 * when max turns is hit so the finalization phase emits a failure event.
 */
/**
 * Drive the harness turn loop. Returns a [KillSwitchException] (re-thrown by the caller after
 * [runFinalizationPhase] has emitted the standard failure event) when the auto-enforcement
 * path trips the switch on token limits. Returns null for normal exit (max turns, halt signal,
 * or no trip).
 *
 * The catch sits at the loop boundary so the harness can transition cleanly: it sets
 * [PumpStationTaskState.lastError] and [PumpStationTaskState.exitReason] to the
 * [PumpStationError.KillSwitchTripped] / [PumpStationExitReason.KillSwitchTripped] pair
 * before breaking out of the loop. The exception itself is returned (not re-thrown) so that
 * [runFinalizationPhase] can still run and emit the [HarnessFailed] event for downstream
 * observers (the visualizer, the trace logger, etc.) before the caller sees the throw.
 */
internal suspend fun PumpStation.runHarnessLoop(): KillSwitchException?
{
    var tripException: KillSwitchException? = null
    while (taskState.turnIndex < maxTurnsInternal && taskState.status == PumpStationStatus.Running)
{
        if (!checkPauseGuards(PumpStationPausePhase.BeforeJudge)) break
        // Outer snapshot for the new turn. If an interrupt fires during
        // finalization or between turns, the catch handler below restores
        // this snapshot and re-enters the loop with the same turnIndex.
        val turnSnapshot = takeInterruptSnapshot()
        // Phase boundary: BeforeJudge — poll interrupt BEFORE runTurn is
        // entered. If an interrupt arrived between turns, this is the catch
        // point.
        try
        {
            injectInterruptForPhase(PumpStationPausePhase.BeforeJudge, turnSnapshot)
        }
        catch (e: PumpStationInterruptException)
        {
            // Catch here (before runTurn is entered). Rewind, inject, and
            // restart the while loop with the same turnIndex.
            restoreFromInterruptSnapshot(e.snapshot)
            turnHistory.add(ConverseData(role = ConverseRole.harness, content = e.content))
            continue
        }
        val result = try
        {
            runTurn()
        }
        catch (e: KillSwitchException)
        {
            taskState.lastError = PumpStationError.KillSwitchTripped
            taskState.exitReason = PumpStationExitReason.KillSwitchTripped
            tripException = e
            break
        }
        catch (e: PumpStationInterruptException)
        {
            // Inner catch: interrupt fired DURING runTurn. The inner
            // turnSnapshot (taken at the top of runTurn) is the rewind
            // target. turnIndex is NOT advanced; the loop re-enters the
            // same slot with the interrupt message in turnHistory.
            restoreFromInterruptSnapshot(e.snapshot)
            turnHistory.add(ConverseData(role = ConverseRole.harness, content = e.content))
            continue
        }
        if (result is TurnResult.Halt)
{
            taskState.exitReason = result.reason
            break
        }
        taskState.turnIndex++
    }
    if (taskState.turnIndex >= maxTurnsInternal && taskState.lastError == null)
{
        taskState.lastError = PumpStationError.MaxTurnsExceeded
        taskState.exitReason = PumpStationExitReason.MaxTurnsHit
    }
    return tripException
}

//=========================================Single Turn=============================================================

/**
 * One iteration of the harness loop. Returns [TurnResult.Continue] to re-enter
 * the loop or [TurnResult.Halt] to exit. Runs health check, judge, dispatch,
 * path execution, foreground/background agents, memory update, and compaction
 * with context-blowout detection at each phase boundary.
 */
internal suspend fun PumpStation.runTurn(): TurnResult
{
    // Inner snapshot for the current turn. If an interrupt fires during this
    // turn, the catch handler in runHarnessLoop restores this snapshot and
    // re-enters runTurn from BeforeJudge without incrementing turnIndex.
    val turnSnapshot = takeInterruptSnapshot()
    refreshAgentInstances()
    refreshPipelinesPrompts()
    refreshSettingsPropagation()

    runHealthCheckPhase()
    detectAndHandleContextBlowout(PumpStationPhase.HealthCheck)

    // Phase boundary: BeforeJudge — poll interrupt (higher priority than
    // steering) then drain steering.
    injectInterruptForPhase(PumpStationPausePhase.BeforeJudge, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.BeforeJudge)
    val judgeVerdict = runJudgePhase()
    detectAndHandleContextBlowout(PumpStationPhase.Judge)
    // Phase boundary: AfterJudge.
    injectInterruptForPhase(PumpStationPausePhase.AfterJudge, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.AfterJudge)
    if (judgeVerdict.shouldHalt)
{
        return TurnResult.Halt(judgeVerdict.reason ?: PumpStationExitReason.TerminateSignal)
    }
    if (judgeVerdict.isComplete) return runExitFlow(turnSnapshot)

    // Phase boundary: BeforeDispatch.
    injectInterruptForPhase(PumpStationPausePhase.BeforeDispatch, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.BeforeDispatch)
    val pathRequest = when (pathExecutionShapeInternal)
    {
        PathExecutionShape.SinglePath -> runDispatchPhase()
        PathExecutionShape.MultiPath -> runDispatchPhaseMulti()
    } ?: return TurnResult.Continue
    // Phase boundary: AfterDispatch.
    injectInterruptForPhase(PumpStationPausePhase.AfterDispatch, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.AfterDispatch)
    detectAndHandleContextBlowout(PumpStationPhase.Dispatch)

    if (!checkPauseGuards(PumpStationPausePhase.BeforePathExecution))
{
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    // Phase boundary: BeforePathExecution — sequenced after the pause guard
    // so a suspended harness does not accumulate one-shot interrupt entries
    // on every resume cycle.
    injectInterruptForPhase(PumpStationPausePhase.BeforePathExecution, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.BeforePathExecution)
    val pathResult = runPathFlow(pathRequest)
    // Phase boundary: AfterPathExecution.
    injectInterruptForPhase(PumpStationPausePhase.AfterPathExecution, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.AfterPathExecution)
    detectAndHandleContextBlowout(PumpStationPhase.PathExecution)
    if (pathResult != null)
    {
        taskState.latestContent = pathResult
        if (pathResult.passPipeline)
        {
            return if (goalAgent == null)
            {
                TurnResult.Halt(PumpStationExitReason.PassSignal)
            }
            else
            {
                runExitFlow(turnSnapshot)
            }
        }
        if (pathResult.terminatePipeline)
        {
            return TurnResult.Halt(PumpStationExitReason.TerminateSignal)
        }
    }
    pruneTurnHistory()
    pruneRawTurnHistory()
    runSafePrunePhase()

    runForegroundAgentsPhase()
    detectAndHandleContextBlowout(PumpStationPhase.ForegroundAgents)

    runBackgroundAgentsPhase()
    // Phase boundary: BeforeMemoryUpdate.
    injectInterruptForPhase(PumpStationPausePhase.BeforeMemoryUpdate, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.BeforeMemoryUpdate)
    runMemoryUpdatePhase()
    // Phase boundary: BeforeCompaction.
    injectInterruptForPhase(PumpStationPausePhase.BeforeCompaction, turnSnapshot)
    injectSteeringForPhase(PumpStationPausePhase.BeforeCompaction)
    runCompactionPhase()

    return TurnResult.Continue
}

/**
 * Prune turnHistory if it exceeds maxTurnHistorySize. The oldest entries are
 * popped, summarized into a single ConverseData, and appended to maintain context.
 * rawTurnHistory is not affected (it's the full event log).
 */
internal suspend fun PumpStation.pruneTurnHistory()
{
    while (turnHistory.history.size > maxTurnHistorySizeInternal)
{
        val popped = turnHistory.history.take(turnHistory.history.size - maxTurnHistorySizeInternal + 1)
        turnHistory.history.removeAll(popped.toSet())
        val summary = summarizePoppedEntries(popped)
        turnHistory.add(summary)
    }
}

/**
 * Summarize a list of popped ConverseData entries into a single entry.
 * The summary preserves the key content of the popped entries.
 */
internal fun PumpStation.summarizePoppedEntries(popped: List<ConverseData>): ConverseData
{
    val combinedText = popped.joinToString("\n") { it.content.text }
    return ConverseData(
        role = ConverseRole.assistant,
        content = MultimodalContent(text = "[Summary of ${popped.size} older turns]\n$combinedText")
    )
}

/**
 * Prune rawTurnHistory if maxRawTurnHistorySize is set and exceeded.
 * Unlike pruneTurnHistory, this is a simple FIFO pop (no summarization).
 */
internal fun PumpStation.pruneRawTurnHistory()
{
    val maxSize = maxRawTurnHistorySizeInternal ?: return
    while (rawTurnHistory.history.size > maxSize)
{
        rawTurnHistory.history.removeAt(0)
    }
}

//=========================================Finalization Phase======================================================

/**
 * Finalization phase. Drains any pending background events, awaits in-flight
 * background jobs (bounded by [memoryUpdateTimeoutMs]), and runs a final
 * compaction if the context fill ratio is still above [compactionThreshold].
 * Emits either [HarnessCompleted] or [HarnessFailed] based on whether
 * [PumpStationTaskState.lastError] is set, and returns the final output.
 */
internal suspend fun PumpStation.runFinalizationPhase(): MultimodalContent
{
    // Phase boundary: BeforeExit — poll interrupt then drain steering before
    // the loop exits and the final output is returned. This is the last
    // opportunity to inject content that the post-finalization observers may
    // need to see.
    val finalSnapshot = takeInterruptSnapshot()
    try
    {
        injectInterruptForPhase(PumpStationPausePhase.BeforeExit, finalSnapshot)
    }
    catch (e: PumpStationInterruptException)
    {
        // Interrupt fired during finalization. Rewind, inject, and the harness
        // re-enters runHarnessLoop from BeforeJudge (note: the harness loop
        // has already returned by the time finalization runs, so the re-entry
        // is not possible here — instead, the interrupt is logged and the
        // finalization proceeds with the interrupt message appended).
        restoreFromInterruptSnapshot(e.snapshot)
        turnHistory.add(ConverseData(role = ConverseRole.harness, content = e.content))
    }
    injectSteeringForPhase(PumpStationPausePhase.BeforeExit)
    // 1. Drain async turn results from in-flight async paths / harness
    //    agents. We do this BEFORE the cancel so any work that has already
    //    enqueued a PendingTurnEntry gets merged into turnHistory.
    drainPendingAsyncResults()
    // 2. Cancel the station-scoped async jobs. cancelAsyncJobs respects
    //    asyncJobGracePeriodMs and uses the new pathway so coroutines
    //    cannot outlive executeLocal (closes the orphan-after-timeout
    //    hazard from the prior analysis). backgroundJobs is still drained
    //    here for backward compatibility with any pre-substrate callers
    //    that joined via backgroundJobs.forEach.
    cancelAsyncJobs()
    drainBackgroundEventQueue()
    withTimeoutOrNull(memoryUpdateTimeoutMsInternal) {
        backgroundJobs.forEach { it.join() }
    }
    backgroundJobs.clear()

    if (contextFillRatio() > compactionThresholdInternal)
{
        runCompactionPhase()
    }

    val isFailure = taskState.lastError in listOf(
        PumpStationError.MaxTurnsExceeded,
        PumpStationError.KillSwitchTripped,
        PumpStationError.P2PRequestInvalid,
        PumpStationError.InitNotCalled,
        // v3: CompactionInflated is set by handOffToTruncation when the retry budget is exhausted.
        // Treat it as a failure so callers see status=Failed (not Completed) when a mid-loop
        // compaction blowout happened.
        PumpStationError.CompactionInflated
    )

    if (isFailure)
{
        emitEventInternal(HarnessFailed(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            error = taskState.lastError ?: PumpStationError.UnknownPath,
            errorMessage = taskState.lastError?.name,
            exitReason = taskState.exitReason ?: PumpStationExitReason.Error
        ))
        taskState.status = PumpStationStatus.Failed
    } else
    {
        emitEventInternal(HarnessCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            exitReason = taskState.exitReason ?: PumpStationExitReason.JudgeComplete,
            finalOutput = taskState.latestContent
        ))
        taskState.status = PumpStationStatus.Completed
    }

    // v3: auto-dispatch the trace to the TPipe-TraceServer when both tracing and
    // RemoteTraceConfig.dispatchAutomatically are enabled. PipeTracer.exportTrace
    // already gates on dispatchAutomatically at Debug/PipeTracer.kt:132 and catches
    // HTTP failures internally; the outer `tracingEnabledInternal` check avoids the
    // export call when the developer never opted into tracing.
    //
    // After the implicit first dispatch (from PipeTracer.exportTrace), re-dispatch
    // with `kind = "pumpstation"` so the TraceServer dashboard can render the
    // PumpStation badge + filter chip. The TraceServer's `_upsertSummary` will
    // replace the first entry with this kind-stamped version. Belt-and-suspenders:
    // also stamp `tags["component"] = "pumpstation"` for clients using the
    // `?tag=component:pumpstation` filter path.
    if(tracingEnabledInternal && RemoteTraceConfig.dispatchAutomatically)
    {
        PipeTracer.exportTrace(taskState.runId, com.TTT.Debug.TraceFormat.HTML)
        com.TTT.Debug.RemoteTraceDispatcher.dispatchTrace(
            pipelineId = taskState.runId,
            name = taskState.runId,
            status = when (taskState.status)
            {
                com.TTT.Pipeline.PumpStationStatus.Completed -> "SUCCESS"
                else -> "FAILURE"
            },
            kind = "pumpstation",
        )
    }

    // Prefer the path's actual output (lastPathResult) as the deliverable. The harness's
    // contract is "do the work and return the result" — for a code-generation or research
    // harness, the result is the last path's output, not the judge\'s verdict or the
    // dispatch\'s path-request. latestContent is the "current turn state" used to build
    // prompts; lastPathResult is the "what the harness produced" surface. Falling back to
    // latestContent preserves backward compatibility for harnesses that never set
    // lastPathResult (e.g. tests that wire paths without going through invokePath).
    return taskState.lastPathResult ?: (taskState.latestContent ?: MultimodalContent())
}

//=========================================Background Event Drain=================================================

/**
 * Drain all events currently buffered in [backgroundEventQueue] and dispatch
 * each to the synchronous [eventObserver]. Runs synchronously (no coroutine
 * suspension) so it can be called from the finalization phase on the same
 * thread that produced the events.
 */
internal fun PumpStation.drainBackgroundEventQueue()
{
    val observer = eventObserverInternal ?: return
    while (true)
{
        val result = backgroundEventQueueInternal.tryReceive()
        if (result.isSuccess)
{
            observer(result.getOrThrow())
        } else
        {
            break
        }
    }
}


/**
 * Read token usage from a [P2PInterface] when it is a [Pipeline]. Returns null for opaque
 * P2PInterface implementations. When non-null, the returned pair's first element is the
 * input token count and the second is a (outputTokens, totalTokens) pair.
 *
 * Used by Judge/Dispatch/Foreground agent emission sites to record per-agent token usage
 * without forcing every harness agent to be a Pipeline.
 */
internal fun agentTokenUsage(agent: P2PInterface?): Pair<Int, Pair<Int, Int>>?
{
    val pipeline = agent as? Pipeline ?: return null
    val usage = pipeline.getTokenUsage()
    val input = usage.totalInputTokens
    val output = usage.totalOutputTokens
    if (input <= 0 && output <= 0) return null
    val total = input + output
    return input to (output to total)
}

/**
 * Auto-enforce a [P2PInterface]'s attached [KillSwitch]. Mirrors [com.TTT.Pipeline.Manifold.checkKillSwitch].
 *
 * If the switch has an [KillSwitch.inputTokenLimit] or [KillSwitch.outputTokenLimit] configured
 * and the supplied counts exceed them, the switch's [KillSwitch.onTripped] callback is invoked
 * synchronously. The default callback throws [KillSwitchException] (see
 * [com.TTT.P2P.KillSwitchException]); the harness loop catches that at the [runHarnessLoop]
 * boundary and transitions the run to a [PumpStationError.KillSwitchTripped] state.
 *
 * The supplied [runStartElapsedMs] is used to populate [KillSwitchContext.elapsedMs]. When zero
 * (e.g. before [PumpStation.P2PInitInternal] has run) elapsedMs defaults to 0.
 *
 * @param inputTokens  The current accumulated input token count for the caller's scope.
 * @param outputTokens The current accumulated output token count for the caller's scope.
 * @param runStartElapsedMs Wall-clock millis when the harness run started.
 * @throws KillSwitchException via the default [KillSwitch.onTripped] when any limit is exceeded.
 */
internal fun P2PInterface.checkKillSwitch(
    inputTokens: Int,
    outputTokens: Int,
    runStartElapsedMs: Long
)
{
    val ks = killSwitch ?: return
    val inputLimit = ks.inputTokenLimit
    val outputLimit = ks.outputTokenLimit
    val inputExceeded = inputLimit != null && inputTokens > inputLimit
    val outputExceeded = outputLimit != null && outputTokens > outputLimit
    if (!inputExceeded && !outputExceeded) return

    val elapsedMs = if (runStartElapsedMs > 0) System.currentTimeMillis() - runStartElapsedMs else 0L
    val reason = when
    {
        inputExceeded -> "input_exceeded"
        outputExceeded -> "output_exceeded"
        else -> return
    }
    ks.onTripped(KillSwitchContext(
        p2pInterface = this,
        inputTokensSpent = inputTokens,
        outputTokensSpent = outputTokens,
        elapsedMs = elapsedMs,
        reason = reason,
        accumulatedInputTokens = inputTokens,
        accumulatedOutputTokens = outputTokens
    ))
}

/**
 * Record the token usage from a single agent call into the station-level accumulator and
 * auto-enforce the station's [PumpStation.killSwitch] against the running total. Returns
 * silently when [agent] is null or not a [Pipeline] (no token usage available).
 *
 * Called from the harness loop after each phase that produces a [Pipeline] response — judge,
 * and dispatch. The path phase uses [PathObject.checkKillSwitch] directly against the
 * path's own per-path usage and additionally calls [PumpStation.addTokenUsage] to fold the
 * path's tokens into the station total.
 *
 * Uses the legacy [Pipeline.inputTokensSpent] / [Pipeline.outputTokensSpent] fields rather
 * than the comprehensive [com.TTT.Pipe.TokenUsage.totalInputTokens] because the latter is
 * reset by [Pipeline.execute] at the start of each agent call. The legacy fields are only
 * overwritten by the comprehensive tracking path, so when a test or a custom agent does not
 * enable comprehensive token tracking, the values survive across calls — which is what the
 * station-level kill switch wants (it tracks the *cumulative* total across all agents).
 */
internal fun PumpStation.recordAndCheckKillSwitch(agent: P2PInterface?)
{
    val pipeline = agent as? Pipeline ?: return
    val inputTokens = pipeline.inputTokensSpent
    val outputTokens = pipeline.outputTokensSpent
    if (inputTokens <= 0 && outputTokens <= 0) return
    addTokenUsage(inputTokens, outputTokens)
    checkKillSwitch(
        accumulatedInputTokensInternal,
        accumulatedOutputTokensInternal,
        runStartElapsedMsInternal
    )
}

//==========================================Nudge=============================================
/**
 * Soft-nudges the dispatch LLM to commit a [PathRequest.pathSelectionRationale]
 * on the next dispatch turn.
 *
 * Returns false (silently) when the failure policy is OFF, when the rationale is
 * already non-blank, or when the station has no runId yet. When the policy is ON
 * AND the rationale is null/blank, append a single "Harness Notice" message to
 * [com.TTT.Pipeline.PumpStation.turnHistory] so the next dispatch sees the field
 * it forgot. The nudge is informational — never a hard dispatch failure.
 *
 * @param request the [PathRequest] returned by the dispatch LLM.
 * @param rationale the rationale string the dispatch LLM emitted. May be null.
 * @return true when a hint was appended, false when the call was silent.
 */
internal fun PumpStation.applyRationaleNudgeIfNeeded(
    request: PathRequest,
    rationale: String?
): Boolean
{
    if (!this.failurePolicy.requirePathSelectionRationale) return false
    if (!rationale.isNullOrBlank()) return false
    if (this.taskState.runId.isBlank()) return false
    // Skip repeat nudges within a run — a prior [Harness Notice] message
    // already told the dispatch LLM what to do; repeating it bloats the
    // prompt without adding signal.
    val alreadyNudged = this.turnHistory.history.any { conv ->
        conv.content.text?.contains("[Harness Notice]") == true
    }
    if (alreadyNudged) return false

    val hintMessage = "[Harness Notice] Your dispatch output was a valid PathRequest JSON but " +
        "the pathSelectionRationale field was empty. The harness is configured to require a " +
        "rationale (requirePathSelectionRationale=true). On your next dispatch, commit a brief " +
        "1-2 sentence explanation of WHY you picked the path you picked."

    this.turnHistory.add(
        ConverseData(
            role = ConverseRole.harness,
            content = MultimodalContent(text = hintMessage)
        )
    )
    return true
}
