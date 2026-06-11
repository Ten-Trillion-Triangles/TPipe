package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Context.LoreBook
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Util.deserialize
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 */
internal suspend fun PumpStation.runAgent(agent: Pipeline?, input: MultimodalContent): MultimodalContent
{
    if (agent == null) return input
    return agent.execute(input)
}

/**
 * Run the judge agent, parse the verdict, check flags.
 * Returns JudgeVerdict indicating what to do next.
 */
internal suspend fun PumpStation.runJudgePhase(): JudgeVerdict
{
    taskState.phase = PumpStationPhase.Judge
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

    // Post-judge hook
    val postResult = postJudgeFunctionInternal?.invoke(result, this) ?: result

    // Parse verdict + flag check
    val verdict = parseJudgeVerdict(postResult).withFlagCheck(postResult)

    taskState.latestContent = postResult
    emitEventInternal(JudgeCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        isComplete = verdict.isComplete,
        shouldTerminate = verdict.shouldTerminate
    ))
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

    val baseInput = taskState.latestContent ?: buildTurnContent()
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
 * Resolve the path and call invokePath(). On unknown path, emit PathFailed
 * and replace latestContent with an LLM-targeted error message.
 */
internal suspend fun PumpStation.runPathFlow(request: PathRequest)
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
        return
    }
    val input = buildPathInput(path, request)
    invokePathInternal(path, input)
}

/**
 * Build the input MultimodalContent for a path execution.
 */
internal fun PumpStation.buildPathInput(path: PathObject, request: PathRequest): MultimodalContent
{
    val base = buildTurnContent()
    base.text = request.pathSchema.ifEmpty { path.pathSchema }
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

/**
 * Whole-strategy compaction: summarize the entire turn history into a single
 * assistant message and clear the previous history. Requires [summaryAgent] to
 * be configured; returns silently if it is not.
 */
internal fun PumpStation.compactWhole()
{
    if (summaryAgentInternal == null) return
    runBlocking {
        val summaryContent = MultimodalContent(text = turnHistory.toString())
        val summaryResult = summaryAgentInternal!!.executeLocal(summaryContent)
        turnHistory.history.clear()
        turnHistory.add(ConverseData(role = ConverseRole.assistant, content = summaryResult))
    }
}

/**
 * Chunked-strategy compaction. Currently a thin wrapper around [compactWhole];
 * a full chunked implementation will partition turn history by token budget and
 * summarize each chunk independently.
 */
internal fun PumpStation.compactChunked()
{
    compactWhole()
}

/**
 * Hybrid-strategy compaction: choose whole vs. chunked based on remaining
 * headroom. Skips entirely if there is no current memory pressure.
 */
internal fun PumpStation.compactHybrid()
{
    if (contextFillRatio() < compactionThresholdInternal) return
    compactWhole()
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
                backgroundJobs += GlobalScope.launch { updateLorebook() }
            }
        }
        if (summaryAgentInternal != null)
        {
            backgroundMutex.withLock {
                backgroundJobs += GlobalScope.launch { updateSummary() }
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
 * Run compaction strategy if memory pressure or scheduled. Uses the
 * [preCompactionFunction] and [postCompactionFunction] DITL hooks to allow the
 * developer to inspect and transform state before and after compaction.
 */
internal suspend fun PumpStation.runCompactionPhase()
{
    if (compactionStrategyInternal == PumpStationCompactionStrategy.Whole && !shouldCompact()) return
    if (compactionStrategyInternal == PumpStationCompactionStrategy.Chunked && !shouldCompact()) return
    if (compactionStrategyInternal == PumpStationCompactionStrategy.Hybrid)
    {
        val headroom = contextFillRatio() < compactionThresholdInternal
        if (headroom) return
    }

    emitEventInternal(CompactionStarted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        strategy = compactionStrategyInternal,
        memoryMode = memoryManagementModeInternal
    ))

    preCompactionFunctionInternal?.invoke(
        taskState.latestContent ?: MultimodalContent(),
        turnHistory.history.firstOrNull() ?: ConverseData(role = ConverseRole.system, content = MultimodalContent()),
        turnHistory,
        this
    )

    when (compactionStrategyInternal)
    {
        PumpStationCompactionStrategy.Whole -> compactWhole()
        PumpStationCompactionStrategy.Chunked -> compactChunked()
        PumpStationCompactionStrategy.Hybrid -> compactHybrid()
    }

    val beforeSize = turnHistory.history.size
    val afterSize = turnHistory.history.size

    postCompactionFunctionInternal?.invoke(
        taskState.latestContent ?: MultimodalContent(),
        turnHistory,
        this
    )

    emitEventInternal(CompactionCompleted(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        strategy = compactionStrategyInternal,
        memoryMode = memoryManagementModeInternal,
        previousHistorySize = beforeSize,
        newHistorySize = afterSize
    ))
}

/**
 * Background lorebook update. Locks on [lorebookMutex] so concurrent turns
 * queue their updates in chronological order.
 *
 * The lorebook agent is expected to return a JSON object of the form:
 * ```
 * {
 *   "updates": [
 *     {
 *       "key": "string",
 *       "value": "string",
 *       "weight": 0,
 *       "linkedKeys": ["..."],
 *       "aliasKeys": ["..."],
 *       "requiredKeys": ["..."]
 *     }
 *   ]
 * }
 * ```
 * For each update, if the key already exists in [ContextWindow.loreBookKeys] the
 * new value is merged with the existing entry via [LoreBook.combineValue]; otherwise
 * a fresh entry is created. Parse failures are silently ignored — the caller
 * already isolates exceptions, and a malformed response should never crash a turn.
 */
internal suspend fun PumpStation.updateLorebook()
{
    if (lorebookAgentInternal == null) return
    lorebookMutex.withLock {
        val content = taskState.latestContent ?: MultimodalContent()
        val response = lorebookAgentInternal!!.executeLocal(content)

        // Flag check: the lorebook agent signals halt/pass via the universal
        // loop-control flags. These take precedence over the JSON payload —
        // if the agent set terminatePipeline on its response, the harness
        // needs to see that even if the JSON is otherwise valid.
        val flags = checkMultimodalFlags(response, "Lorebook")
        if (flags.shouldHalt)
{
            taskState.lastError = PumpStationError.P2PRequestInvalid
            taskState.latestContent = response
            return
        }
        if (flags.shouldPass) return

        applyLorebookUpdates(response)
    }
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
 */
internal suspend fun PumpStation.runBackgroundAgentsPhase()
{
    if (backgroundTurnIntervalInternal == 0) return
    if (taskState.turnIndex % backgroundTurnIntervalInternal != 0) return

    for (slot in additionalHarnessAgentSlotsInternal)
{
        if (slot.concurrency != PumpStationConcurrencyMode.Async) continue
        backgroundMutex.withLock {
            backgroundJobs += GlobalScope.launch {
                try
                {
                    val agent = slot.builderFunction?.invoke(this@runBackgroundAgentsPhase) ?: slot.agent
                    if (agent != null)
                    {
                        agent.setParentInterface(this@runBackgroundAgentsPhase)
                        agent.P2PInit()
                        agent.executeLocal(this@runBackgroundAgentsPhase.buildTurnContent())
                    }
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
internal suspend fun PumpStation.runExitFlow(): TurnResult
{
    if (!checkPauseGuards(PumpStationPausePhase.BeforeGoalValidation))
{
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    if (goalAgent == null)
{
        return TurnResult.Halt(PumpStationExitReason.JudgeComplete)
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
    taskState.phase = PumpStationPhase.Judge
}

//=========================================Harness Loop============================================================

/**
 * Main harness loop. Runs turns until [maxHarnessTurns] is reached, the task
 * is halted by the judge or exit flow, or the harness is suspended/killed.
 * Sets [PumpStationTaskState.lastError] and [PumpStationTaskState.exitReason]
 * when max turns is hit so the finalization phase emits a failure event.
 */
internal suspend fun PumpStation.runHarnessLoop()
{
    while (taskState.turnIndex < maxHarnessTurnsInternal && taskState.status == PumpStationStatus.Running)
{
        if (!checkPauseGuards(PumpStationPausePhase.BeforeJudge)) break
        val result = runTurn()
        if (result is TurnResult.Halt)
{
            taskState.exitReason = result.reason
            break
        }
        taskState.turnIndex++
    }
    if (taskState.turnIndex >= maxHarnessTurnsInternal && taskState.lastError == null)
{
        taskState.lastError = PumpStationError.MaxTurnsExceeded
        taskState.exitReason = PumpStationExitReason.MaxTurnsHit
    }
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
    refreshAgentInstances()
    refreshPipelinesPrompts()
    refreshSettingsPropagation()

    runHealthCheckPhase()
    detectAndHandleContextBlowout(PumpStationPhase.HealthCheck)

    val judgeVerdict = runJudgePhase()
    detectAndHandleContextBlowout(PumpStationPhase.Judge)
    if (judgeVerdict.shouldHalt)
{
        return TurnResult.Halt(judgeVerdict.reason ?: PumpStationExitReason.TerminateSignal)
    }
    if (judgeVerdict.isComplete) return runExitFlow()

    val pathRequest = runDispatchPhase() ?: return TurnResult.Continue
    detectAndHandleContextBlowout(PumpStationPhase.Dispatch)
    if (pathRequest.pathName.isBlank()) return TurnResult.Continue

    if (!checkPauseGuards(PumpStationPausePhase.BeforePathExecution))
{
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    runPathFlow(pathRequest)
    detectAndHandleContextBlowout(PumpStationPhase.PathExecution)
    pruneTurnHistory()
    pruneRawTurnHistory()

    runForegroundAgentsPhase()
    detectAndHandleContextBlowout(PumpStationPhase.ForegroundAgents)

    runBackgroundAgentsPhase()
    runMemoryUpdatePhase()
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
        PumpStationError.InitNotCalled
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

    return taskState.latestContent ?: MultimodalContent()
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
    val input = usage.totalInputTokens.takeIf { it > 0 } ?: return null
    val output = usage.totalOutputTokens.takeIf { it > 0 } ?: 0
    val total = if (input > 0 || output > 0) input + output else 0
    return input to (output to total)
}
