package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Inline-content preview length for the visualizer. Content longer than this gets truncated
 * in the metadata previews and the visualizer's collapsibles surface a "show full" toggle.
 */
private const val CONTENT_PREVIEW_MAX = 8 * 1024

/**
 * String used to mark a clipped preview. Avoids non-ASCII characters in trace event strings
 * to keep the JSON serializer happy on all platforms.
 */
private const val ELLIPSIS = "..."

/**
 * Extract a string preview from a [MultimodalContent] suitable for stuffing into a metadata map.
 * Returns a tuple of (preview, totalLength). Previews are clipped to [CONTENT_PREVIEW_MAX] chars
 * to keep the trace event size reasonable.
 */
private fun contentPreview(content: MultimodalContent?): Pair<String, Int>
{
    if (content == null) return "" to 0
    val text = content.text
    val reasoning = content.modelReasoning
    val binaryCount = content.binaryContent.size
    val combined = buildString {
        if (text.isNotEmpty())
        {
            append("text=")
            append(if (text.length > CONTENT_PREVIEW_MAX) text.substring(0, CONTENT_PREVIEW_MAX) + ELLIPSIS else text)
        }
        if (reasoning.isNotEmpty())
        {
            if (isNotEmpty()) append("\n")
            append("reasoning=")
            append(if (reasoning.length > CONTENT_PREVIEW_MAX) reasoning.substring(0, CONTENT_PREVIEW_MAX) + ELLIPSIS else reasoning)
        }
        if (binaryCount > 0)
        {
            if (isNotEmpty()) append("\n")
            append("binary=")
            append(binaryCount)
            append(" item(s)")
        }
    }
    return combined to (text.length + reasoning.length)
}

//=========================================Trace Funnel============================================================

/**
 * Mirror a [PumpStationEvent] into the global [PipeTracer] when tracing is enabled. The funnel
 * performs the [PumpStationEvent] → [TraceEvent] conversion (mapping each sealed subtype to its
 * PUMP_STATION_* event type and extracting relevant fields into metadata) and is the single entry
 * point for trace emission. The visualizer reads its `turnIndex` from the trace event metadata,
 * so each event carries a consistent `turnIndex` regardless of where it was emitted.
 *
 * This helper does NOT replace the in-process `backgroundEventQueue` emission — it runs in
 * addition to it, so test observers and the live UI both keep working.
 */
internal fun PumpStation.tracePumpStationEvent(event: PumpStationEvent)
{
    if(!tracingEnabledInternal) return
    val traceId = taskState.runId.takeIf { it.isNotBlank() } ?: return

    val traceEvent = convertPumpStationEvent(event) ?: return
    PipeTracer.addEvent(traceId, traceEvent)
}

/**
 * Convert a [PumpStationEvent] to a [TraceEvent] for visualization. Returns null for events that
 * have no trace representation (currently none, but the safety net keeps the funnel total).
 *
 * The mapping is exhaustive over the [PumpStationEvent] sealed interface. Each branch carries the
 * most useful fields in the metadata map so the visualizer can render rich detail panels without
 * having to deserialize the original sealed type.
 */
private fun PumpStation.convertPumpStationEvent(event: PumpStationEvent): TraceEvent?
{
    val pipeId = taskState.runId
    val pipeName = "PumpStation"
    val baseMetadata: MutableMap<String, Any> = mutableMapOf(
        "turnIndex" to event.turnIndex,
        "phase" to event.phase.name,
        "runId" to event.runId
    )

    val eventType: TraceEventType
    var agentContent: MultimodalContent? = null
    when (event)
    {
        is HarnessStarted -> eventType = TraceEventType.PUMP_STATION_STARTED
        is PreInitCompleted -> eventType = TraceEventType.PUMP_STATION_STARTED
        is HarnessCompleted -> eventType = TraceEventType.PUMP_STATION_COMPLETED
        is HarnessFailed ->
        {
            eventType = TraceEventType.PUMP_STATION_FAILED
            baseMetadata["error"] = event.error.name
            baseMetadata["errorMessage"] = event.errorMessage ?: ""
            baseMetadata["exitReason"] = event.exitReason.name
        }
        is HarnessSuspended ->
        {
            eventType = TraceEventType.PUMP_STATION_SUSPENDED
            baseMetadata["pausedAt"] = event.pausedAt.map { it.name }
            baseMetadata["reason"] = event.reason ?: ""
        }
        is HarnessResumed -> eventType = TraceEventType.PUMP_STATION_RESUMED

        is HealthCheckStarted -> eventType = TraceEventType.PUMP_STATION_HEALTH_CHECK_STARTED
        is HealthCheckCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED
            baseMetadata["status"] = event.status.name
            baseMetadata["warnings"] = event.warnings
            baseMetadata["terminateHarness"] = event.terminateHarness
        }
        is JudgeStarted -> eventType = TraceEventType.PUMP_STATION_JUDGE_STARTED
        is JudgeCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_JUDGE_COMPLETED
            baseMetadata["isComplete"] = event.isComplete
            baseMetadata["shouldTerminate"] = event.shouldTerminate
            agentContent = event.result
            val (preview, len) = contentPreview(event.result)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
            if (event.result?.modelReasoning?.isNotEmpty() == true)
            {
                baseMetadata["modelReasoning"] = event.result!!.modelReasoning
                baseMetadata["modelReasoningLen"] = event.result!!.modelReasoning.length
            }
            if (event.result?.binaryContent?.isNotEmpty() == true) baseMetadata["binaryCount"] = event.result!!.binaryContent.size
            event.inputTokens?.let { baseMetadata["inputTokens"] = it } ?: baseMetadata.put("inputTokens", -1)
            event.outputTokens?.let { baseMetadata["outputTokens"] = it } ?: baseMetadata.put("outputTokens", -1)
            event.totalTokens?.let { baseMetadata["totalTokens"] = it } ?: baseMetadata.put("totalTokens", -1)
        }
        is DispatchStarted -> eventType = TraceEventType.PUMP_STATION_DISPATCH_STARTED
        is DispatchCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_DISPATCH_COMPLETED
            baseMetadata["selectedPathName"] = event.selectedPathName ?: ""
            baseMetadata["pathRequest"] = event.pathRequest?.toString() ?: ""
            agentContent = event.result
            val (preview, len) = contentPreview(event.result)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
            if (event.result?.modelReasoning?.isNotEmpty() == true)
            {
                baseMetadata["modelReasoning"] = event.result!!.modelReasoning
                baseMetadata["modelReasoningLen"] = event.result!!.modelReasoning.length
            }
            if (event.result?.binaryContent?.isNotEmpty() == true) baseMetadata["binaryCount"] = event.result!!.binaryContent.size
            event.inputTokens?.let { baseMetadata["inputTokens"] = it } ?: baseMetadata.put("inputTokens", -1)
            event.outputTokens?.let { baseMetadata["outputTokens"] = it } ?: baseMetadata.put("outputTokens", -1)
            event.totalTokens?.let { baseMetadata["totalTokens"] = it } ?: baseMetadata.put("totalTokens", -1)
        }
        is PathSelected ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_SELECTED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["riskLevel"] = event.riskLevel.name
        }
        is PathSafetyStarted ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["riskLevel"] = event.riskLevel.name
        }
        is PathSafetyCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["riskLevel"] = event.riskLevel.name
            baseMetadata["approved"] = event.approved
            baseMetadata["reason"] = event.reason ?: ""
        }
        is PathStarted ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_STARTED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["riskLevel"] = event.riskLevel.name
        }
        is PathCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_COMPLETED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["riskLevel"] = event.riskLevel.name
            agentContent = event.result
            val (preview, len) = contentPreview(event.result)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
            if (event.result?.modelReasoning?.isNotEmpty() == true)
            {
                baseMetadata["modelReasoning"] = event.result!!.modelReasoning
                baseMetadata["modelReasoningLen"] = event.result!!.modelReasoning.length
            }
            if (event.result?.binaryContent?.isNotEmpty() == true) baseMetadata["binaryCount"] = event.result!!.binaryContent.size
            event.inputTokens?.let { baseMetadata["inputTokens"] = it } ?: baseMetadata.put("inputTokens", -1)
            event.outputTokens?.let { baseMetadata["outputTokens"] = it } ?: baseMetadata.put("outputTokens", -1)
            event.totalTokens?.let { baseMetadata["totalTokens"] = it } ?: baseMetadata.put("totalTokens", -1)
        }
        is PathFailed ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_FAILED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["riskLevel"] = event.riskLevel.name
            baseMetadata["error"] = event.error.name
            baseMetadata["errorMessage"] = event.errorMessage ?: ""
        }
        is PathHidden ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_HIDDEN
            baseMetadata["pathName"] = event.pathName
            baseMetadata["reason"] = event.reason
        }
        is PathValidationCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_PATH_VALIDATION_COMPLETED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["approved"] = event.approved
            baseMetadata["reason"] = event.reason ?: ""
        }
        is InterventionStarted ->
        {
            eventType = TraceEventType.PUMP_STATION_INTERVENTION_STARTED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["trigger"] = event.trigger.name
        }
        is InterventionCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED
            baseMetadata["nudges"] = event.nudges
            baseMetadata["shouldContinue"] = event.shouldContinue
            agentContent = event.result
            val (preview, len) = contentPreview(event.result)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
            if (event.result?.modelReasoning?.isNotEmpty() == true)
            {
                baseMetadata["modelReasoning"] = event.result!!.modelReasoning
                baseMetadata["modelReasoningLen"] = event.result!!.modelReasoning.length
            }
            if (event.result?.binaryContent?.isNotEmpty() == true) baseMetadata["binaryCount"] = event.result!!.binaryContent.size
            event.inputTokens?.let { baseMetadata["inputTokens"] = it } ?: baseMetadata.put("inputTokens", -1)
            event.outputTokens?.let { baseMetadata["outputTokens"] = it } ?: baseMetadata.put("outputTokens", -1)
            event.totalTokens?.let { baseMetadata["totalTokens"] = it } ?: baseMetadata.put("totalTokens", -1)
        }
        is ForegroundAgentCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED
            baseMetadata["agentName"] = event.agentName
            agentContent = event.result
            val (preview, len) = contentPreview(event.result)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
            if (event.result?.modelReasoning?.isNotEmpty() == true)
            {
                baseMetadata["modelReasoning"] = event.result!!.modelReasoning
                baseMetadata["modelReasoningLen"] = event.result!!.modelReasoning.length
            }
            if (event.result?.binaryContent?.isNotEmpty() == true) baseMetadata["binaryCount"] = event.result!!.binaryContent.size
            event.inputTokens?.let { baseMetadata["inputTokens"] = it } ?: baseMetadata.put("inputTokens", -1)
            event.outputTokens?.let { baseMetadata["outputTokens"] = it } ?: baseMetadata.put("outputTokens", -1)
            event.totalTokens?.let { baseMetadata["totalTokens"] = it } ?: baseMetadata.put("totalTokens", -1)
        }
        is MemoryUpdateStarted ->
        {
            eventType = TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED
            baseMetadata["memoryMode"] = event.memoryMode.name
        }
        is MemoryUpdateCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED
            baseMetadata["memoryMode"] = event.memoryMode.name
            baseMetadata["compactionPercent"] = event.result.compactionPercent
            baseMetadata["loreBookActive"] = event.result.loreBookActive
            baseMetadata["summaryActive"] = event.result.summaryActive
        }
        is StashCreated ->
        {
            eventType = TraceEventType.PUMP_STATION_STASH_CREATED
            baseMetadata["stashId"] = event.stashId
            baseMetadata["sourcePath"] = event.sourcePath ?: ""
            baseMetadata["reason"] = event.reason.name
            baseMetadata["tokenEstimate"] = event.tokenEstimate ?: -1
        }
        is CompactionStarted ->
        {
            eventType = TraceEventType.PUMP_STATION_COMPACTION_STARTED
            baseMetadata["strategy"] = event.strategy.name
            baseMetadata["memoryMode"] = event.memoryMode.name
        }
        is CompactionCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_COMPACTION_COMPLETED
            baseMetadata["strategy"] = event.strategy.name
            baseMetadata["memoryMode"] = event.memoryMode.name
            baseMetadata["previousHistorySize"] = event.previousHistorySize
            baseMetadata["newHistorySize"] = event.newHistorySize
        }
        is GoalValidationStarted -> eventType = TraceEventType.PUMP_STATION_GOAL_VALIDATION_STARTED
        is GoalValidationCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED
            baseMetadata["passed"] = event.passed
            baseMetadata["reason"] = event.reason ?: ""
        }
        is ReservePathRevealed ->
        {
            eventType = TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED
            baseMetadata["pathName"] = event.pathName
            baseMetadata["reservePathNames"] = event.reservePathNames
        }
        is LoopGuardTripped ->
        {
            eventType = TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED
            baseMetadata["guard"] = event.guard
            baseMetadata["pathName"] = event.pathName
            baseMetadata["detail"] = event.detail
        }
        is ContextBlowoutDetected ->
        {
            eventType = TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED
            baseMetadata["fillRatio"] = event.fillRatio
            baseMetadata["threshold"] = event.threshold
            baseMetadata["afterPhase"] = event.afterPhase.name
        }
        is BackgroundAgentQueued ->
        {
            eventType = TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED
            baseMetadata["agentName"] = event.agentName
        }
        is NestedP2PCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_NESTED_P2P_COMPLETED
            baseMetadata["pathName"] = event.pathName ?: ""
            baseMetadata["agentName"] = event.agentName
            agentContent = event.response
            val (preview, len) = contentPreview(event.response)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
            if (event.response?.modelReasoning?.isNotEmpty() == true)
            {
                baseMetadata["modelReasoning"] = event.response!!.modelReasoning
                baseMetadata["modelReasoningLen"] = event.response!!.modelReasoning.length
            }
            if (event.response?.binaryContent?.isNotEmpty() == true) baseMetadata["binaryCount"] = event.response!!.binaryContent.size
            event.inputTokens?.let { baseMetadata["inputTokens"] = it } ?: baseMetadata.put("inputTokens", -1)
            event.outputTokens?.let { baseMetadata["outputTokens"] = it } ?: baseMetadata.put("outputTokens", -1)
            event.totalTokens?.let { baseMetadata["totalTokens"] = it } ?: baseMetadata.put("totalTokens", -1)
        }
    }

    return TraceEvent(
        timestamp = event.timestamp,
        pipeId = pipeId,
        pipeName = pipeName,
        eventType = eventType,
        phase = TracePhase.valueOf(event.phase.name),
        content = agentContent,
        contextSnapshot = null,
        metadata = baseMetadata
    )
}

//=========================================Flag Check============================================================

/**
 * Read the standard MultimodalContent flags and convert them to a FlagCheckResult.
 * This is the canonical loop-control pattern: agents signal via flags, not via
 * magic contracts.
 */
internal fun checkMultimodalFlags(content: MultimodalContent, source: String): FlagCheckResult
{
    return FlagCheckResult(
        shouldHalt = content.terminatePipeline,
        shouldPass = content.passPipeline,
        shouldInterrupt = content.interuptPipeline,
        haltReason = content.metadata["haltReason"] as? String ?: "halted by $source"
    )
}

//=========================================Parser Helpers=======================================================

/**
 * Parse the judge agent's text output as a JudgeVerdict.
 * On parse failure, returns an empty (default) verdict — caller should
 * treat this as "not complete, continue loop".
 */
internal fun PumpStation.parseJudgeVerdict(content: MultimodalContent): JudgeVerdict
{
    return try
    {
        val json = Json.parseToJsonElement(content.text) as? JsonObject
            ?: return JudgeVerdict.empty()
        JudgeVerdict(
            isComplete = json["isComplete"]?.jsonPrimitive?.boolean ?: false,
            shouldTerminate = json["shouldTerminate"]?.jsonPrimitive?.boolean ?: false
        )
    }
    catch (e: Exception)
    {
        JudgeVerdict.empty()
    }
}

/**
 * Augment a JudgeVerdict with flag-based halt information. If the source content
 * has terminatePipeline set, shouldHalt is forced to true.
 */
internal fun JudgeVerdict.withFlagCheck(content: MultimodalContent): JudgeVerdict
{
    val flags = checkMultimodalFlags(content, "Judge")
    return if (flags.shouldHalt)
    {
        copy(shouldHalt = true, reason = PumpStationExitReason.TerminateSignal)
    }
    else if (flags.shouldPass)
    {
        copy(isComplete = true, shouldHalt = false)
    }
    else
    {
        this
    }
}

/**
 * Parse the dispatch agent's text output as a PathRequest.
 * On parse failure, returns null — caller should attempt repair.
 */
internal fun PumpStation.parseDispatchOutput(content: MultimodalContent): PathRequest?
{
    return try
    {
        val json = Json.parseToJsonElement(content.text) as? JsonObject
            ?: return null
        val name = json["pathName"]?.jsonPrimitive?.content ?: ""
        val schema = json["pathSchema"]?.jsonPrimitive?.content ?: ""
        if (name.isEmpty()) null else PathRequest(pathName = name, pathSchema = schema)
    }
    catch (e: Exception)
    {
        null
    }
}

//=========================================Path Resolution======================================================

/**
 * Look up a path by name, searching both normal paths and reserve paths.
 * Returns null if not found. Names are case-insensitive.
 */
internal fun PumpStation.resolvePath(name: String): PathObject?
{
    val lowerName = name.lowercase()
    return pathList[lowerName] ?: reservePaths[lowerName]
}

//=========================================Context Fill Ratio==================================================

/**
 * Estimate the current context window fill ratio. Returns 0.0 if no budget is set.
 * This is a rough estimate based on serialized sizes; exact token counting happens
 * inside the pipes themselves.
 */
internal fun PumpStation.contextFillRatio(): Double
{
    val historySize = turnHistory.history.sumOf { it.content.toString().length }
    val contentSize = taskState.latestContent?.toString()?.length ?: 0
    val totalSize = historySize + contentSize
    val maxSize = (tokenBudgetSettings?.contextWindowSize ?: 100_000) * 4  // rough chars/token
    return if (maxSize == 0) 0.0 else (totalSize.toDouble() / maxSize)
}

//=========================================Error Ratio=========================================================

/**
 * Compute the current error ratio: failed path calls / total path calls.
 * Returns 0.0 if no path calls have been made.
 */
internal fun PumpStation.computeErrorRatio(): Double
{
    val total = pathCallCounts.values.sum()
    if (total == 0) return 0.0
    val failed = taskState.lastError?.let { 1 } ?: 0
    return failed.toDouble() / total
}

//=========================================Prompt Composition==================================================

/**
 * Build the system prompt for the judge agent. Composes personality, role framing,
 * systemTask, userGuidelines, and entryUserPrompt into a layered prompt.
 */
internal fun PumpStation.buildJudgeSystemPrompt(): String
{
    val basePrompt = DEFAULT_JUDGE_PROMPT
        .replace("{entryUserPrompt}", entryUserPrompt)
    val composition = buildString {
        if (personality.isNotBlank()) append("Personality: $personality\n")
        if (systemTask.isNotBlank()) append("System task: $systemTask\n")
        if (userGuidelines.isNotBlank()) append("User guidelines: $userGuidelines\n")
    }
    return basePrompt + "\n" + composition
}

/**
 * Build the system prompt for the dispatch agent. Same layered composition.
 */
internal fun PumpStation.buildDispatchSystemPrompt(): String
{
    return DEFAULT_DISPATCH_PROMPT
        .replace("{personality}", personality)
        .replace("{systemTask}", systemTask)
        .replace("{userGuidelines}", userGuidelines)
        .replace("{entryUserPrompt}", entryUserPrompt)
}

/**
 * Build the system prompt for the goal agent.
 */
internal fun PumpStation.buildGoalSystemPrompt(): String
{
    return DEFAULT_GOAL_PROMPT
        .replace("{entryUserPrompt}", entryUserPrompt)
}

internal fun PumpStation.buildJudgeFooter(): String = DEFAULT_JUDGE_FOOTER
internal fun PumpStation.buildDispatchFooter(): String = DEFAULT_DISPATCH_FOOTER

//=========================================Content Building=====================================================

/**
 * Build the MultimodalContent for the judge/dispatch shared input.
 * - text: turnSummary (if any) + role-specific question
 * - context.converseHistory: turnHistory (curated, agent-facing)
 * - context.loreBookKeys: current lorebook
 * - miniBank: current
 * - metadata: taskState, phase, turnIndex, etc.
 */
internal fun PumpStation.buildTurnContent(): MultimodalContent
{
    val newContext = contextWindow.copy().apply {
        loreBookKeys = contextWindow.loreBookKeys.toMutableMap()
        contextElements = contextWindow.contextElements.toMutableList()
        converseHistory = turnHistory
    }
    val content = MultimodalContent(
        text = buildUserMessageForTurn(),
        binaryContent = taskState.latestContent?.binaryContent ?: mutableListOf(),
        context = newContext,
        miniBankContext = miniBank,
        tools = taskState.latestContent?.tools ?: PcPRequest()
    )
    content.metadata.putAll(
        mutableMapOf<Any, Any>(
            "taskState" to taskState,
            "phase" to taskState.phase,
            "turnIndex" to taskState.turnIndex,
            "runId" to taskState.runId,
            "isInitialTurn" to (taskState.turnIndex == 0),
            "visiblePaths" to getVisiblePathNames()
        )
    )
    return content
}

/**
 * Build the user-message text for a turn. The system prompt carries
 * personality/systemTask/userGuidelines/entryUserPrompt; the user message
 * just carries the conversational content.
 */
internal fun PumpStation.buildUserMessageForTurn(): String
{
    val summaryPrefix = if (turnSummary.isNotBlank()) "$turnSummary\n\n" else ""
    val phaseQuestion = when (taskState.phase)
    {
        PumpStationPhase.Judge -> "Is the task complete? Decide based on the conversation history."
        PumpStationPhase.Dispatch -> "Select the next path to invoke."
        PumpStationPhase.GoalValidation -> "Verify the work was done."
        else -> ""
    }
    return summaryPrefix + phaseQuestion
}

/**
 * Build the MultimodalContent for the goal agent. Deeper than buildTurnContent:
 * includes rawTurnHistory (full event log) in the context, so the goal agent
 * can do a thorough deep verification.
 */
internal fun PumpStation.buildGoalContent(): MultimodalContent
{
    val base = buildTurnContent()
    // Override the converseHistory in-place so metadata set on `base` survives
    base.context.converseHistory = rawTurnHistory
    base.metadata["judgeVerdict"] = "isComplete=true"
    base.metadata["rawHistorySize"] = rawTurnHistory.history.size
    return base
}

//=========================================Error Messages======================================================

/**
 * Build an LLM-targeted error message for the given PumpStationError.
 * Format: natural language, concrete examples, available paths listed.
 */
internal fun PumpStation.buildLlmErrorMessage(
    error: PumpStationError,
    details: Map<String, Any>
): String
{
    return when (error)
    {
        PumpStationError.InvalidPathRequest -> buildInvalidPathRequestMessage(details)
        PumpStationError.UnknownPath -> buildUnknownPathMessage(details)
        PumpStationError.DispatchJsonRepairFailed -> buildRepairFailedMessage(details)
        PumpStationError.PathExecutionException -> buildPathExecutionExceptionMessage(details)
        else -> "[Harness Notice] Error: ${error.name}. Details: $details"
    }
}

internal fun PumpStation.buildInvalidPathRequestMessage(details: Map<String, Any>): String
{
    val output = details["output"] ?: "(no output)"
    val available = (details["availablePaths"] as? List<*>)?.joinToString(", ") ?: "(none)"
    return """
[Harness Notice] Your dispatch output was not a valid PathRequest.

What you did: Returned output that doesn't match the PathRequest schema.
Output: $output

Available paths: $available

What to do instead: Return a PathRequest JSON object matching this schema:
{
  "pathName": "the exact path name from the list above",
  "inputData": { ... path-specific input fields ... }
}
""".trimIndent()
}

internal fun PumpStation.buildUnknownPathMessage(details: Map<String, Any>): String
{
    val pathName = details["pathName"] ?: "(unknown)"
    val available = (details["availablePaths"] as? List<*>)?.joinToString(", ") ?: "(none)"
    return """
[Harness Notice] You called a path that doesn't exist: "$pathName".

Available paths: $available

What to do instead: Pick a path from the list above and call it correctly. If no path matches your intent, return {"pathName": "", "inputData": {}} to end your turn without calling a path.
""".trimIndent()
}

internal fun PumpStation.buildRepairFailedMessage(details: Map<String, Any>): String
{
    val attempts = details["attempts"] ?: "multiple"
    return """
[Harness Notice] Your dispatch output could not be parsed as valid JSON, and $attempts repair attempts failed.

What to do instead: Return ONLY a JSON object matching the PathRequest schema. No prose, no markdown, no explanation — just the JSON.
""".trimIndent()
}

internal fun PumpStation.buildPathExecutionExceptionMessage(details: Map<String, Any>): String
{
    val pathName = details["pathName"] ?: "(unknown)"
    val input = details["input"] ?: "(none)"
    val exception = details["exceptionMessage"] ?: "(no message)"
    return """
[Harness Notice] The path "$pathName" failed during execution.

What you did: Called "$pathName" with input: $input
Why it's a problem: The path threw an exception: "$exception"
What to do instead: You can:
1. Retry with corrected input (if the exception suggests an input error)
2. Call a different path
3. Return {"pathName": "", "inputData": {}} to end your turn
""".trimIndent()
}
