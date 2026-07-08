package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.Util.extractAllJsonObjects
import com.TTT.Util.extractJson
import com.TTT.Util.isDefault
import com.TTT.Util.serializeConverseHistory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
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
 * Classify an exception caught during path execution. B4 fix: transport-
 * layer timeouts (SocketTimeoutException, IOException with "timeout" in
 * the message) are emitted as [PumpStationError.PathTimeout]; everything
 * else falls through to [PumpStationError.PathExecutionException].
 */
internal fun classifyPathException(e: Throwable): PumpStationError = when
{
    e is java.net.SocketTimeoutException -> PumpStationError.PathTimeout
    e is java.io.IOException &&
        e.message?.contains("timeout", ignoreCase = true) == true ->
            PumpStationError.PathTimeout
    else -> PumpStationError.PathExecutionException
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
        is HarnessWarning ->
        {
            eventType = TraceEventType.PUMP_STATION_HARNESS_WARNING
            baseMetadata["warningCode"] = event.code.name
            baseMetadata["mechanisms"] = event.mechanisms.joinToString(",") { it.name }
        }
        is HarnessCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_COMPLETED
            baseMetadata["exitReason"] = event.exitReason.name
            baseMetadata["finalOutput"] = event.finalOutput?.toString() ?: ""
        }
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
        is JudgeSkipped ->
        {
            eventType = TraceEventType.PUMP_STATION_JUDGE_SKIPPED
            baseMetadata["reason"] = event.reason
            baseMetadata["judgeRunMode"] = event.judgeRunMode.name
        }
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
            event.inputTokens?.let { baseMetadata["inputTokens"] = it }
            event.outputTokens?.let { baseMetadata["outputTokens"] = it }
            event.totalTokens?.let { baseMetadata["totalTokens"] = it }
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
            event.inputTokens?.let { baseMetadata["inputTokens"] = it }
            event.outputTokens?.let { baseMetadata["outputTokens"] = it }
            event.totalTokens?.let { baseMetadata["totalTokens"] = it }
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
            event.inputTokens?.let { baseMetadata["inputTokens"] = it }
            event.outputTokens?.let { baseMetadata["outputTokens"] = it }
            event.totalTokens?.let { baseMetadata["totalTokens"] = it }
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
            event.inputTokens?.let { baseMetadata["inputTokens"] = it }
            event.outputTokens?.let { baseMetadata["outputTokens"] = it }
            event.totalTokens?.let { baseMetadata["totalTokens"] = it }
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
            event.inputTokens?.let { baseMetadata["inputTokens"] = it }
            event.outputTokens?.let { baseMetadata["outputTokens"] = it }
            event.totalTokens?.let { baseMetadata["totalTokens"] = it }
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
            event.result?.let { baseMetadata["result"] = it::class.simpleName ?: "Unknown" }
        }
        is CompactionAttemptCompleted ->
        {
            // v3: per-attempt event. Uses the same event type as the legacy
            // COMPACTION_COMPLETED slot for visualizer compatibility; metadata
            // includes the attempt index and outcome class name.
            eventType = TraceEventType.PUMP_STATION_COMPACTION_COMPLETED
            baseMetadata["attempt"] = event.attempt
            baseMetadata["strategy"] = event.strategy.name
            baseMetadata["fanout"] = event.fanout?.name ?: "None"
            baseMetadata["result"] = event.result::class.simpleName ?: "Unknown"
        }
        is CompactionInflated ->
        {
            eventType = TraceEventType.PUMP_STATION_COMPACTION_INFLATED
            baseMetadata["inputTokens"] = event.inputTokens
            baseMetadata["outputTokens"] = event.outputTokens
            baseMetadata["attempt"] = event.attempt
            baseMetadata["willRetry"] = event.willRetry
        }
        is CompactionRolledBack ->
        {
            eventType = TraceEventType.PUMP_STATION_COMPACTION_ROLLED_BACK
            baseMetadata["backupGeneration"] = event.backupGeneration
            baseMetadata["reason"] = event.reason
        }
        is CompactionHandedOffToTruncation ->
        {
            eventType = TraceEventType.PUMP_STATION_COMPACTION_HANDED_OFF
            baseMetadata["contextWindowBefore"] = event.contextWindowBefore
            baseMetadata["contextWindowAfter"] = event.contextWindowAfter
        }
        is SafePruneApplied ->
        {
            eventType = TraceEventType.PUMP_STATION_SAFE_PRUNE_APPLIED
            baseMetadata["originalCount"] = event.report.originalCount
            baseMetadata["finalCount"] = event.report.finalCount
            baseMetadata["tokensRemoved"] = event.report.tokensRemoved
            baseMetadata["enabledFlags"] = event.report.enabledFlags.joinToString(",") { it.name }
        }
        is SafePruneDryRunCompleted ->
        {
            eventType = TraceEventType.PUMP_STATION_SAFE_PRUNE_DRY_RUN_COMPLETED
            baseMetadata["originalCount"] = event.report.originalCount
            baseMetadata["finalCount"] = event.report.finalCount
            baseMetadata["tokensRemoved"] = event.report.tokensRemoved
            baseMetadata["enabledFlags"] = event.report.enabledFlags.joinToString(",") { it.name }
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
        is AsyncTurnAppended ->
        {
            eventType = TraceEventType.PUMP_STATION_ASYNC_TURN_APPENDED
            baseMetadata["source"] = event.source
            baseMetadata["pathName"] = event.pathName ?: ""
            baseMetadata["agentName"] = event.agentName ?: ""
            baseMetadata["seq"] = event.seq
            agentContent = event.content
            val (preview, len) = contentPreview(event.content)
            if (preview.isNotEmpty()) baseMetadata["contentPreview"] = preview
            if (len > 0) baseMetadata["contentLength"] = len
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
            event.inputTokens?.let { baseMetadata["inputTokens"] = it }
            event.outputTokens?.let { baseMetadata["outputTokens"] = it }
            event.totalTokens?.let { baseMetadata["totalTokens"] = it }
        }
    }

    return TraceEvent(
        timestamp = event.timestamp,
        pipeId = pipeId,
        pipeName = pipeName,
        eventType = eventType,
        phase = mapPumpStationPhaseToTracePhase(event.phase),
        content = agentContent,
        contextSnapshot = null,
        metadata = baseMetadata
    )
}

/**
 * Map a [PumpStationPhase] to the closest [TracePhase] value. The two enums are
 * not in 1:1 correspondence (TracePhase is a general-purpose phase vocabulary;
 * PumpStationPhase is harness-specific), so we need an explicit mapping rather
 * than `TracePhase.valueOf(event.phase.name)`, which would throw on every event.
 *
 * Returns the closest semantic match. The original [PumpStationPhase] is also
 * preserved in the event's `metadata["phase"]` so the visualizer can render
 * the harness-specific label even when the TracePhase is a more generic bucket.
 */
internal fun mapPumpStationPhaseToTracePhase(phase: PumpStationPhase): TracePhase = when (phase)
{
    PumpStationPhase.PreInit -> TracePhase.INITIALIZATION
    PumpStationPhase.HealthCheck -> TracePhase.MONITORING
    PumpStationPhase.Judge -> TracePhase.VALIDATION
    PumpStationPhase.Dispatch -> TracePhase.ORCHESTRATION
    PumpStationPhase.PathSafety -> TracePhase.VALIDATION
    PumpStationPhase.PathExecution -> TracePhase.EXECUTION
    PumpStationPhase.PathValidation -> TracePhase.VALIDATION
    PumpStationPhase.Intervention -> TracePhase.EXECUTION
    PumpStationPhase.ForegroundAgents -> TracePhase.AGENT_COMMUNICATION
    PumpStationPhase.MemoryUpdate -> TracePhase.CONTEXT_PREPARATION
    PumpStationPhase.Compaction -> TracePhase.CONTEXT_PREPARATION
    PumpStationPhase.SafePrune -> TracePhase.CONTEXT_PREPARATION
    PumpStationPhase.SafePruneDryRun -> TracePhase.CONTEXT_PREPARATION
    PumpStationPhase.GoalValidation -> TracePhase.VALIDATION
    PumpStationPhase.Exit -> TracePhase.CLEANUP
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
 *
 * Uses TPipe's [com.TTT.Util.extractJson] so judge outputs wrapped in
 * markdown code fences or interleaved with reasoning prose still extract
 * cleanly. extractJson returns the first JSON object that deserializes
 * into [JudgeVerdict] — and our type has no required constructor fields, so
 * any reasonable {...} with optional booleans deserializes.
 *
 * The [com.TTT.Util.isDefault] guard catches the "deserialize-succeeded-but-
 * got-default" failure mode where the LLM emitted `{}` or a bare object that
 * decodes to a JudgeVerdict with all-default false values. Without the guard,
 * such output would be indistinguishable from a real "isComplete=false" verdict;
 * with the guard, default-only objects fall through to [JudgeVerdict.empty].
 */
internal fun PumpStation.parseJudgeVerdict(content: MultimodalContent): JudgeVerdict
{
    val parsed = try { extractJson<JudgeVerdict>(content.text) } catch (_: Exception) { null }
    if (parsed != null && !parsed.isDefault())
    {
        return parsed
    }
    return JudgeVerdict.empty()
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
    // Use TPipe's high-level schema-aware JSON extractor
        // [com.TTT.Util.extractJson]<[PathRequest]>. It does the brace-range scanning +
        // lenient parsing internally (via extractAllJsonObjects), then deserializes
        // the first matching JSON object into the requested type. This recovers
        // from every realistic LLM formatting quirk we observed:
        //   - markdown code fences ```json ... ``` (verified by inspecting the
        //     PUMP_STATION_PATH_FAILED events in the trace HTMLs)
        //   - reasoning prose before/after the JSON object
        //   - trailing prose after the closing brace
        //   - multiple JSON blocks (the function picks the first one deserializeable
        //     as PathRequest)
        //
        // The [com.TTT.Util.isDefault] guard rejects the failure mode where the
        // deserializer "succeeds" but produces a default-initialized PathRequest
        // because the LLM emitted `{}` (a JSON object that decodes cleanly but carries
        // no actual path selection). Without this guard, downstream callers would
        // treat a default PathRequest as a valid request.
        //
        // Blank pathName is handled in two layers:
        //   1. The LLM emits NO pathName key at all (e.g., `{}`) → isDefault() catches it
        //      here and we return null, falling into the repair-exhaustion path.
        //   2. The LLM emits `{"pathName": ""}` (explicit empty) → isDefault() also catches
        //      it (PathRequest defaults are blank), but we have already decided that
        //      explicit empty pathName is a legitimate "no path" signal that the harness
        //      must see. So we pre-check the raw text for an explicit pathName key and
        //      return the parsed PathRequest (with blank pathName) so the dispatch phase
        //      can emit PathFailed(pathName="(empty)") and append a hint to history.
        val hasExplicitPathName = content.text.contains("\"pathName\"")
        return try
        {
            val parsed = extractJson<PathRequest>(content.text)
            if (parsed != null && !parsed.isDefault())
            {
                parsed
            }
            else if (parsed != null && hasExplicitPathName)
            {
                // Explicit empty pathName — return as-is so the dispatch phase can record
                // the failure and surface it to the harness. The dispatch phase distinguishes
                // blank pathName from valid pathName after parseDispatchOutput succeeds.
                parsed
            }
            else
            {
                null
            }
        }
        catch (_: Exception)
        {
            null
        }
    }

/**
 * Parse the path-safety agent's text output as a structured verdict.
 *
 * The default path-safety system prompt asks the agent to reply with JSON like
 * `{"safe": boolean, "reason": string}`. Previously the harness only checked the
 * `terminatePipeline` / `passPipeline` flags on the agent's [MultimodalContent]
 * (see [PumpStation.invokePathInternal] around the path-safety block) — the agent's
 * actual `{"safe": false}` JSON was completely ignored, so the safety check was a
 * degenerate "always approve" that only failed when the LLM happened to set one of
 * the special flags. This parser reads the structured verdict.
 *
 * Returns `null` when the text is not parseable as a path-safety JSON object. The
 * caller is expected to fall back to the legacy flag-based check in that case so
 * custom agents that don't follow the JSON convention still work.
 *
 * The parser is intentionally strict on the `safe` field:
 *  - The field must be a JSON boolean literal (true / false).
 *  - Strings like "true", numbers, and null are all rejected → caller falls back.
 *  - Missing `safe` returns null.
 *  - Uses [com.TTT.Util.extractAllJsonObjects] so the verdict survives being
 *    wrapped in a markdown code fence or interleaved with reasoning prose.
 *    The first object that contains a `safe` field (boolean literal) wins.
 */
internal fun parsePathSafetyVerdict(text: String): Boolean?
{
    if (text.isBlank()) return null
    val candidates = try { extractAllJsonObjects(text) } catch (_: Exception) { emptyList() }
    for (element in candidates)
    {
        val obj = element as? JsonObject ?: continue
        val safeField = obj["safe"] ?: continue
        val safePrim = safeField as? JsonPrimitive ?: continue
        if (safePrim.isString) continue
        val bool = safePrim.booleanOrNull
        if (bool != null) return bool
    }
    return null
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
    val originalInputPrefix = taskState.originalInput?.text.orEmpty().let { if (it.isNotBlank()) "$it\n\n" else "" }
    val summaryPrefix = if (turnSummary.isNotBlank()) "$turnSummary\n\n" else ""
    val phaseQuestion = when (taskState.phase)
    {
        PumpStationPhase.Judge -> "Is the task complete? Decide based on the conversation history."
        PumpStationPhase.Dispatch -> "Select the next path to invoke."
        PumpStationPhase.GoalValidation -> "Verify the work was done."
        else -> ""
    }
    // Embed the conversation history into the user message text so downstream
    // pipes (which read only content.text) actually receive it. Without this,
    // [com.TTT.Pipe.Pipe.generateContent]'s default implementation drops
    // [MultimodalContent.context.converseHistory] on the floor — the system
    // prompt claims "The conversation history below shows every turn" but the
    // turn history never reaches the LLM.
    //
    // The serialized form matches [com.TTT.Pipe.Pipe.serializeConverseHistory]
    // which the rest of the codebase uses for embedded-history payloads (see
    // Pipe.kt:2091, 5479, 5726). Empty history is skipped to keep the
    // no-history case identical to the previous behavior.
    val historyBlock = if (turnHistory.history.isNotEmpty())
    {
        "\n\n[CONVERSATION HISTORY]\n" + serializeConverseHistory(turnHistory) + "\n[/CONVERSATION HISTORY]"
    }
    else ""
    return originalInputPrefix + summaryPrefix + phaseQuestion + historyBlock
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
