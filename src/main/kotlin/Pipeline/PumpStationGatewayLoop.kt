package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Context.ContextBank
import com.TTT.Context.TodoListTask
import com.TTT.Enums.PumpStationGatewayHistorySource
import com.TTT.Enums.PumpStationGatewayPromptPlacement
import com.TTT.Enums.PumpStationGatewaySetting
import com.TTT.Enums.PumpStationLatestContentPosition
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize

//=========================================Phase Outcome==========================================================

/**
 * The outcome of a single gateway phase.
 */
internal sealed interface GatewayPhaseOutcome
{
    /** No gateway was requested for this turn. */
    object NoRequests : GatewayPhaseOutcome

    /**
     * One or more gateways were evaluated.
     *
     * @property gatewayNames The gateways that produced a verdict.
     * @property stageAdvanced True when the active stage advanced during this phase.
     */
    data class Evaluated(
        val gatewayNames: List<String>,
        val stageAdvanced: Boolean
    ) : GatewayPhaseOutcome
}

//=========================================Phase==================================================================

/**
 * Evaluate every gateway requested for this turn, then advance the stage when its gate is met.
 *
 * The phase runs after path execution and before the foreground harness agents, so a gateway
 * can judge the work that was just performed. It never ends the turn: a "not complete" verdict
 * injects an instruction into turn history and leaves the stage locked, and the turn carries on
 * through the foreground agents, the memory update and the compaction. Skipping those phases on
 * a failing gate would starve the memory and compaction managers exactly when the harness needs
 * them most.
 *
 * @return The outcome of the phase.
 */
internal suspend fun PumpStation.runGatewayPhase(): GatewayPhaseOutcome
{
    val requested = gatewayService.drain()
    if(requested.isEmpty()) return GatewayPhaseOutcome.NoRequests

    val stage = gatewayStages.getOrNull(taskState.gatewayStageIndex)
    val stageGate = stage?.gateway

    val distinct = requested.distinct()
    val known = distinct.filter { gateways.containsKey(it) }
    distinct.filter { !gateways.containsKey(it) }.forEach { name ->
        emitEventInternal(GatewaySkipped(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            gatewayName = name,
            reason = "no gateway named $name is configured"
        ))
    }

    val satisfied = mutableSetOf<String>()
    val evaluated = mutableListOf<String>()

    for(name in known)
    {
        val config = gateways.getValue(name)
        val agent = config.builderFunction?.invoke(this) ?: config.agent
        if(agent == null)
        {
            emitEventInternal(GatewaySkipped(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                gatewayName = name,
                reason = "gateway $name has no agent"
            ))
            continue
        }

        // The gateway agent must be a Pipeline: the verdict contract is injected into
        // its decision pipe. A builder may hand back any P2PInterface, so a non-Pipeline
        // agent is a configuration error - it is reported, not silently run without a
        // contract, which would leave the gate permanently unsatisfiable.
        val pipeline = agent as? Pipeline
        if(pipeline == null)
        {
            emitEventInternal(GatewaySkipped(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                gatewayName = name,
                reason = "gateway $name must be a Pipeline so the verdict contract can be injected"
            ))
            continue
        }

        agent.setParentInterface(this)
        agent.P2PInit()
        applyPromptsToPipeline(pipeline, buildGatewaySystemPrompt(config), "")

        val content = buildGatewayContent(config)
        val result = try
        {
            agent.executeLocal(content)
        }
        catch(e: kotlinx.coroutines.CancellationException)
        {
            throw e
        }
        catch(e: Exception)
        {
            emitEventInternal(GatewayEvaluated(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                gatewayName = name,
                stageName = stage?.name,
                complete = false,
                reason = e.message ?: e::class.simpleName ?: "the gateway failed",
                parseFailed = true
            ))
            taskState.gatewayFailureCount++
            continue
        }

        val parse = parseGatewayVerdictDetailed(result)
        val verdict = parse.verdict
        emitEventInternal(GatewayEvaluated(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            gatewayName = name,
            stageName = stage?.name,
            complete = verdict.complete,
            reason = verdict.reason,
            instruction = verdict.instruction,
            parseFailed = !parse.parsed
        ))
        evaluated.add(name)

        if(verdict.complete)
        {
            satisfied.add(name)
            taskState.gatewayFailureCount = 0
        }
        else
        {
            taskState.gatewayFailureCount++
            injectGatewayInstruction(verdict)
        }
    }

    var stageAdvanced = false
    if(stage != null && stageGate != null)
    {
        val gateMet = stageGate in satisfied
        val gateEvaluated = stageGate in evaluated
        val advisoryPass = !stage.gateBlocking && gateEvaluated && !gateMet
        if(gateMet || advisoryPass)
        {
            advanceToStage(taskState.gatewayStageIndex + 1)
            stageAdvanced = true
        }
    }

    return GatewayPhaseOutcome.Evaluated(evaluated, stageAdvanced)
}

//=========================================Prompt Assembly========================================================

/**
 * Build the gateway agent's system prompt.
 *
 * The default contract prompt is always present: it carries the JSON verdict shape the harness
 * parses. The developer's saved criteria string is appended to it under a labelled header, and
 * the injectable instruction is appended last when its placement is
 * [PumpStationGatewayPromptPlacement.SystemPrompt]. A gateway can therefore never lose its
 * output contract.
 *
 * @param config The gateway configuration.
 * @return The assembled system prompt.
 */
internal fun PumpStation.buildGatewaySystemPrompt(config: PumpStationGatewayConfig): String
{
    val builder = StringBuilder(DEFAULT_GATEWAY_PROMPT)
    config.systemPrompt?.takeIf { it.isNotBlank() }?.let { criteria ->
        builder.append("\n\n=== GATEWAY CRITERIA (")
        builder.append(config.name)
        builder.append(") ===\n")
        builder.append(criteria)
    }
    if(config.userPromptPlacement == PumpStationGatewayPromptPlacement.SystemPrompt)
    {
        config.userPrompt?.takeIf { it.isNotBlank() }?.let { instruction ->
            builder.append("\n\n[GATEWAY INSTRUCTION]\n")
            builder.append(instruction)
            builder.append("\n[/GATEWAY INSTRUCTION]")
        }
    }
    return builder.toString()
}

/**
 * Build the gateway's user message: the turn content plus the injectable instruction placed
 * per the configured placement.
 *
 * The history the gateway reads is chosen by [PumpStationGatewayConfig.historySource]: the
 * pruned, curated history the judge and dispatch agents see, or the full retained event log.
 *
 * @param config The gateway configuration.
 * @return The content to hand to the gateway agent.
 */
internal suspend fun PumpStation.buildGatewayContent(config: PumpStationGatewayConfig): MultimodalContent
{
    val history = when(config.historySource)
    {
        PumpStationGatewayHistorySource.Summarized -> turnHistory
        PumpStationGatewayHistorySource.Full -> rawTurnHistory
    }

    val content = buildTurnContent(history)

    val instruction = config.userPrompt?.takeIf { it.isNotBlank() }
    if(instruction != null && config.userPromptPlacement != PumpStationGatewayPromptPlacement.SystemPrompt)
    {
        val block = "[GATEWAY INSTRUCTION]\n$instruction\n[/GATEWAY INSTRUCTION]"
        val position = when(config.userPromptPlacement)
        {
            PumpStationGatewayPromptPlacement.BeforeHistory -> PumpStationLatestContentPosition.BeforeHistory
            PumpStationGatewayPromptPlacement.AfterHistory -> PumpStationLatestContentPosition.AfterHistory
            PumpStationGatewayPromptPlacement.Suffix -> PumpStationLatestContentPosition.Suffix
            PumpStationGatewayPromptPlacement.SystemPrompt -> PumpStationLatestContentPosition.Suffix
        }
        content.text = insertBlockAroundHistory(content.text, block, position)
    }

    val todoTask = resolveGatewayTodoTask(config)
    if(todoTask != null)
    {
        val todoBlock = "[GATEWAY TODO TASK]\n" +
                "task: " + todoTask.task + "\n" +
                "completion requirements: " + todoTask.completionRequirements + "\n" +
                "[/GATEWAY TODO TASK]"
        content.text = insertBlockAroundHistory(
            content.text,
            todoBlock,
            PumpStationLatestContentPosition.BeforeHistory
        )
        content.metadata["todoTask"] = todoTask
    }

    content.metadata["gatewayName"] = config.name
    return content
}

/**
 * Resolve the todo task a gateway is bound to, when it declares both a page key and a task number.
 *
 * @param config The gateway configuration.
 * @return The linked task, or null when the gateway is standalone.
 */
private suspend fun PumpStation.resolveGatewayTodoTask(config: PumpStationGatewayConfig): TodoListTask?
{
    val pageKey = config.todoPageKey?.takeIf { it.isNotBlank() } ?: return null
    val taskNumber = config.todoTaskNumber ?: return null
    val todoList = ContextBank.getPagedTodoListSuspend(pageKey)
    return todoList.find(taskNumber)
}

//=========================================Verdict================================================================

/**
 * The outcome of parsing a gateway verdict.
 *
 * @property verdict The parsed verdict, or a fail-closed default when the parse failed.
 * @property parsed True when the agent output was understood.
 */
internal data class GatewayVerdictParse(
    val verdict: PumpStationGatewayVerdict,
    val parsed: Boolean
)

/**
 * Parse a gateway agent's verdict, reporting whether the output was understood.
 *
 * Tolerant of malformed model output: the repository's deserialize helper repairs common AI
 * JSON defects. When the typed contract cannot be read, the standard loop-control flags are
 * consulted as a fallback. On total failure the verdict fails closed, so the stage stays locked.
 *
 * @param content The gateway agent's output.
 * @return The verdict and whether it was understood.
 */
internal fun parseGatewayVerdictDetailed(content: MultimodalContent): GatewayVerdictParse
{
    val typed = try
    {
        deserialize<PumpStationGatewayVerdict>(content.text)
    }
    catch(e: Exception)
    {
        null
    }
    if(typed != null) return GatewayVerdictParse(typed, true)

    val flags = checkMultimodalFlags(content, "Gateway")
    if(flags.shouldPass)
    {
        return GatewayVerdictParse(
            PumpStationGatewayVerdict(complete = true, reason = "the gateway signalled pass"),
            true
        )
    }
    if(flags.shouldHalt)
    {
        return GatewayVerdictParse(
            PumpStationGatewayVerdict(complete = false, reason = flags.haltReason ?: "the gateway signalled halt"),
            true
        )
    }
    return GatewayVerdictParse(PumpStationGatewayVerdict(), false)
}

/**
 * Parse a gateway agent's verdict.
 *
 * @param content The gateway agent's output.
 * @return The verdict. Fails closed when the output cannot be understood.
 */
internal fun parseGatewayVerdict(content: MultimodalContent): PumpStationGatewayVerdict =
    parseGatewayVerdictDetailed(content).verdict

//=========================================Instruction Injection====================================================

/**
 * Append the gateway's instruction to turn history as a harness-role entry.
 *
 * The next turn's judge and dispatch agents both read turn history, so the instruction is
 * visible to both. Mirrors the interrupt path's injection.
 *
 * @param verdict The verdict that carries the instruction.
 */
private fun PumpStation.injectGatewayInstruction(verdict: PumpStationGatewayVerdict)
{
    val instruction = verdict.instruction.takeIf { it.isNotBlank() } ?: return
    val content = MultimodalContent(text = instruction)
    content.metadata["gatewayInstruction"] = true
    turnHistory.add(ConverseData(role = ConverseRole.harness, content = content))
}

//=========================================Stage Advance==========================================================

/**
 * Apply the reconfiguration of the first gateway stage.
 *
 * The first stage is active from the start, so its configuration is applied when the harness
 * initializes rather than when a gate is met.
 */
internal suspend fun PumpStation.applyInitialGatewayStage()
{
    if(gatewayStages.isEmpty()) return
    taskState.gatewayStageIndex = 0
    val stage = gatewayStages.first()
    taskState.gatewayStageName = stage.name

    val applied = mutableListOf<String>()
    val failed = mutableListOf<String>()
    stage.onEnter.forEach { action ->
        val failure = applyStageAction(action)
        if(failure == null) applied.add(describeStageAction(action)) else failed.add(failure)
    }

    emitEventInternal(GatewayStageAdvanced(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        fromStage = null,
        toStage = stage.name,
        applied = applied,
        failed = failed
    ))
}

/**
 * Advance the harness to the stage at [nextIndex] and apply that stage's reconfiguration.
 *
 * @param nextIndex The index of the stage to activate.
 */
internal suspend fun PumpStation.advanceToStage(nextIndex: Int)
{
    val from = taskState.gatewayStageIndex
    taskState.gatewayStageIndex = nextIndex

    val stage = gatewayStages.getOrNull(nextIndex)
    taskState.gatewayStageName = stage?.name

    val applied = mutableListOf<String>()
    val failed = mutableListOf<String>()

    stage?.onEnter?.forEach { action ->
        val failure = applyStageAction(action)
        if(failure == null) applied.add(describeStageAction(action)) else failed.add(failure)
    }

    emitEventInternal(GatewayStageAdvanced(
        runId = taskState.runId,
        turnIndex = taskState.turnIndex,
        fromStage = gatewayStages.getOrNull(from)?.name,
        toStage = stage?.name,
        applied = applied,
        failed = failed
    ))
}

/**
 * Apply one stage reconfiguration.
 *
 * A failing action - including one that throws - is recorded and skipped, so the
 * remaining actions of the stage still run and the stage advance is still reported.
 *
 * @param action The action to apply.
 * @return Null when the action applied, or a description of why it did not.
 */
internal suspend fun PumpStation.applyStageAction(action: PumpStationStageAction): String? =
    try
    {
        applyStageActionBody(action)
    }
    catch(e: kotlinx.coroutines.CancellationException)
    {
        throw e
    }
    catch(e: Exception)
    {
        "apply ${describeStageAction(action)}: " +
                (e.message ?: e::class.simpleName ?: "the action failed")
    }

/**
 * Apply one stage reconfiguration, without guarding against a throwing action.
 *
 * @param action The action to apply.
 * @return Null when the action applied, or a description of why it did not.
 */
private suspend fun PumpStation.applyStageActionBody(action: PumpStationStageAction): String? =
    when(action)
    {
        is PumpStationStageAction.RevealPath ->
        {
            if(moveReserveToPath(action.pathName)) null
            else if(pathExists(action.pathName)) "reveal path ${action.pathName}: the path is already visible"
            else "reveal path ${action.pathName}: no such path"
        }

        is PumpStationStageAction.HidePath ->
        {
            if(movePathToReserveInternal(action.pathName)) null
            else "hide path ${action.pathName}: the path is not visible"
        }

        is PumpStationStageAction.SetVisiblePaths ->
        {
            val wanted = action.pathNames.map { it.lowercase() }.toSet()
            pathList.values.map { it.pathName }.forEach { name ->
                if(!wanted.contains(name.lowercase())) movePathToReserveInternal(name)
            }
            val missing = mutableListOf<String>()
            action.pathNames.forEach { name ->
                if(!pathExists(name))
                {
                    missing.add(name)
                }
                else
                {
                    moveReserveToPath(name)
                }
            }
            if(missing.isEmpty()) null
            else "set visible paths: no path named ${missing.joinToString(", ")}"
        }

        is PumpStationStageAction.SetHarnessAgentEnabled ->
        {
            val slots = additionalHarnessAgentSlotsInternal
            val index = slots.indexOfFirst { it.name == action.slotName }
            if(index < 0)
            {
                "enable harness agent ${action.slotName}: no slot with that name"
            }
            else
            {
                slots[index] = slots[index].copy(enabled = action.enabled)
                null
            }
        }

        is PumpStationStageAction.SetHarnessAgents ->
        {
            additionalHarnessAgentSlotsInternal.clear()
            additionalHarnessAgentSlotsInternal.addAll(action.slots)
            null
        }

        is PumpStationStageAction.SetSetting -> applyStageSetting(action.setting, action.value)

        is PumpStationStageAction.Custom ->
        {
            action.apply(this)
            null
        }
    }

/**
 * Apply a stage setting change.
 *
 * A value whose type does not match the setting is rejected rather than coerced.
 *
 * @param setting The setting to change.
 * @param value The new value.
 * @return Null when the setting applied, or a description of why it did not.
 */
private fun PumpStation.applyStageSetting(
    setting: PumpStationGatewaySetting,
    value: PumpStationGatewaySettingValue
): String?
{
    fun intValue(): Int? = (value as? PumpStationGatewaySettingValue.IntValue)?.value
    fun doubleValue(): Double? = (value as? PumpStationGatewaySettingValue.DoubleValue)?.value

    when(setting)
    {
        PumpStationGatewaySetting.ForegroundTurnInterval ->
        {
            val v = intValue() ?: return "set $setting: an integer value is required"
            setForegroundTurnInterval(v)
        }

        PumpStationGatewaySetting.BackgroundTurnInterval ->
        {
            val v = intValue() ?: return "set $setting: an integer value is required"
            setBackgroundTurnInterval(v)
        }

        PumpStationGatewaySetting.MaxConcurrentForegroundAgents ->
        {
            val v = intValue() ?: return "set $setting: an integer value is required"
            setMaxConcurrentForegroundAgents(v)
        }

        PumpStationGatewaySetting.MaxConcurrentBackgroundAgents ->
        {
            val v = intValue() ?: return "set $setting: an integer value is required"
            setMaxConcurrentBackgroundAgents(v)
        }

        PumpStationGatewaySetting.CompactionThreshold ->
        {
            val v = doubleValue() ?: return "set $setting: a decimal value is required"
            setCompactionThreshold(v)
        }

        PumpStationGatewaySetting.MaxTurnHistorySize ->
        {
            val v = intValue() ?: return "set $setting: an integer value is required"
            setMaxTurnHistorySize(v)
        }

        PumpStationGatewaySetting.MaxTurns ->
        {
            val v = intValue() ?: return "set $setting: an integer value is required"
            setMaxTurns(v)
        }
    }
    return null
}

/**
 * Describe a stage action for the event log.
 *
 * @param action The action.
 * @return A short human-readable description.
 */
internal fun describeStageAction(action: PumpStationStageAction): String =
    when(action)
    {
        is PumpStationStageAction.RevealPath -> "reveal path ${action.pathName}"
        is PumpStationStageAction.HidePath -> "hide path ${action.pathName}"
        is PumpStationStageAction.SetVisiblePaths -> "set visible paths ${action.pathNames.joinToString(", ")}"
        is PumpStationStageAction.SetHarnessAgentEnabled ->
            "${if(action.enabled) "enable" else "disable"} harness agent ${action.slotName}"
        is PumpStationStageAction.SetHarnessAgents -> "set harness agents (${action.slots.size} slots)"
        is PumpStationStageAction.SetSetting -> "set ${action.setting.name}"
        is PumpStationStageAction.Custom -> "custom reconfiguration"
    }
