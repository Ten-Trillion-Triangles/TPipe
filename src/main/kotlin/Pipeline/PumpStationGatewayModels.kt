package com.TTT.Pipeline

import com.TTT.Enums.PumpStationGatewayHistorySource
import com.TTT.Enums.PumpStationGatewayPromptPlacement
import com.TTT.Enums.PumpStationGatewaySetting
import com.TTT.P2P.P2PInterface

//=========================================Verdict================================================================

/**
 * The verdict a gateway agent returns when it evaluates a step.
 *
 * @property complete True when the step is verifiably finished.
 * @property reason Short explanation of the verdict.
 * @property instruction What the agent must do next when the step is NOT complete.
 *                        Appended to turn history as a harness-role entry.
 */
@kotlinx.serialization.Serializable
data class PumpStationGatewayVerdict(
    val complete: Boolean = false,
    val reason: String = "",
    val instruction: String = ""
)

//=========================================Configuration========================================================

/**
 * Configuration for one named gateway.
 *
 * Holds an agent reference rather than being serializable, matching [HarnessAgentSlot] which
 * also carries a non-serializable builder function.
 *
 * @property name Unique gateway name. Referenced by stage definitions and by request carriers.
 * @property agent The gateway agent. Must be a [Pipeline] so the system prompt can be injected.
 * @property builderFunction Optional per-turn agent factory, evaluated before each invocation.
 * @property systemPrompt Saved criteria string. Appended to [DEFAULT_GATEWAY_PROMPT].
 * @property userPrompt Injectable instruction. Placement is governed by [userPromptPlacement].
 * @property userPromptPlacement Where [userPrompt] lands in the assembled prompt.
 * @property historySource Which history the gateway reads.
 * @property todoPageKey Optional ContextBank page key of a todo list to bind this gateway to.
 * @property todoTaskNumber Optional todo task number to bind this gateway to.
 */
data class PumpStationGatewayConfig(
    val name: String,
    val agent: Pipeline? = null,
    val builderFunction: (suspend (PumpStation) -> P2PInterface)? = null,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val userPromptPlacement: PumpStationGatewayPromptPlacement = PumpStationGatewayPromptPlacement.BeforeHistory,
    val historySource: PumpStationGatewayHistorySource = PumpStationGatewayHistorySource.Summarized,
    val todoPageKey: String? = null,
    val todoTaskNumber: Int? = null
)

//=========================================Stage=================================================================

/**
 * One stage in the ordered gateway stage sequence.
 *
 * @property name Human-readable stage name, surfaced in events and traces.
 * @property gateway Name of the gateway that gates this stage.
 * @property gateBlocking When false, a "not complete" verdict still advances the stage.
 *                         Use this for advisory gates, and as the escape hatch for a gate
 *                         that cannot be satisfied.
 * @property onEnter Reconfiguration applied when this stage becomes active.
 */
data class PumpStationGatewayStage(
    val name: String,
    val gateway: String,
    val gateBlocking: Boolean = true,
    val onEnter: List<PumpStationStageAction> = emptyList()
)

//=========================================Stage Actions=========================================================

/**
 * A harness reconfiguration applied when a gateway stage becomes active.
 *
 * Actions are applied in list order on the foreground harness loop, immediately after the
 * stage advances. A failing action is recorded and skipped; the remaining actions still run.
 */
sealed interface PumpStationStageAction
{
    /** Reveal a reserve path by name, bypassing its revealWhen predicate. */
    data class RevealPath(val pathName: String) : PumpStationStageAction

    /**
     * Hide a path by name.
     *
     * A visible path moves back into the reserve list. A reserve path that was revealed
     * by its revealWhen predicate stays in the reserve list, but the predicate is
     * neutralised and the sticky reveal flag is cleared, so it leaves the visible set.
     */
    data class HidePath(val pathName: String) : PumpStationStageAction

    /** Replace the entire visible path set with exactly these names. */
    data class SetVisiblePaths(val pathNames: List<String>) : PumpStationStageAction

    /** Enable or disable a named harness agent slot. */
    data class SetHarnessAgentEnabled(val slotName: String, val enabled: Boolean) : PumpStationStageAction

    /** Replace the entire harness agent roster. */
    data class SetHarnessAgents(val slots: List<HarnessAgentSlot>) : PumpStationStageAction

    /** Change a station setting. */
    data class SetSetting(
        val setting: PumpStationGatewaySetting,
        val value: PumpStationGatewaySettingValue
    ) : PumpStationStageAction

    /** Arbitrary developer-supplied reconfiguration. */
    class Custom(val apply: suspend (PumpStation) -> Unit) : PumpStationStageAction
}

/**
 * A typed setting value for [PumpStationStageAction.SetSetting].
 *
 * A value whose type does not match the target setting is rejected at apply time and
 * recorded as a failed action rather than silently coerced.
 */
sealed interface PumpStationGatewaySettingValue
{
    data class IntValue(val value: Int) : PumpStationGatewaySettingValue
    data class DoubleValue(val value: Double) : PumpStationGatewaySettingValue
    data class BooleanValue(val value: Boolean) : PumpStationGatewaySettingValue
    data class StringValue(val value: String) : PumpStationGatewaySettingValue
}
