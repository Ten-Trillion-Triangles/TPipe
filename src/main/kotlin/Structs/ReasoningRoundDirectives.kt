package com.TTT.Structs

import kotlinx.serialization.Serializable

/**
 * Describes how one round of multi-round reasoning should be assembled.
 *
 * @param focusPoint The focus instruction that should guide this round.
 * @param mode The round mode that determines whether the round is isolated or merging prior reasoning.
 */
@Serializable
data class ReasoningRoundDirective(
    var focusPoint: String = "",
    var mode: ReasoningRoundMode = ReasoningRoundMode.Blind
)

/**
 * Controls whether a multi-round reasoning step should be isolated from prior thinking or merge it back together.
 *
 * Blind rounds see only the original user prompt plus the current focus point.
 * Merge rounds see the accumulated round stream and are instructed to synthesize it into one conclusion.
 */
@Serializable
enum class ReasoningRoundMode
{
    Blind,
    Merge
}

/**
 * Build the prompt envelope for a blind round.
 *
 * This prompt intentionally strips all earlier rounds, events, and conversation history so the reasoning pipe only
 * sees the original user request and the current focus point.
 *
 * @param round The one-based round number being composed.
 * @param originalUserPrompt The original user prompt that started the reasoning request.
 * @param focusPoint The current round focus instruction.
 *
 * @return A plain-text prompt envelope for an isolated reasoning round.
 */
fun composeBlindReasoningRoundPrompt(
    round: Int,
    originalUserPrompt: String,
    focusPoint: String
) : String
{
    return """
        ##ROUND $round - BLIND MODE##
        ##CURRENT FOCUS##
        $focusPoint

        ##ORIGINAL USER PROMPT##
        $originalUserPrompt
    """.trimIndent()
}

/**
 * Build the prompt envelope for a merge round.
 *
 * Merge mode takes the accumulated round stream, keeps each round boundary visible, and asks the model to synthesize
 * those separate thinking blocks into one final conclusion.
 *
 * @param round The one-based round number being composed.
 * @param originalUserPrompt The original user prompt that started the reasoning request.
 * @param accumulatedReasoning The flattened thought stream accumulated from prior completed rounds.
 * @param focusPoint The current round focus instruction.
 *
 * @return A plain-text prompt envelope for a synthesis round.
 */
fun composeMergeReasoningRoundPrompt(
    round: Int,
    originalUserPrompt: String,
    accumulatedReasoning: String,
    focusPoint: String
) : String
{
    return """
        ##ROUND $round - MERGE MODE##
        ##MANDATORY##
        - Combine the prior round blocks into one final conclusion.
        - Keep each round boundary visible and synthesize them intentionally.
        - Do not ignore prior reasoning; resolve it into one answer.

        ##CURRENT FOCUS##
        $focusPoint

        ##PRIOR ROUND BLOCKS##
        ${accumulatedReasoning.trim()}

        ##ORIGINAL USER PROMPT##
        $originalUserPrompt
    """.trimIndent()
}
