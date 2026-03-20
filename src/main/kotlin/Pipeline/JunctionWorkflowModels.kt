package com.TTT.Pipeline

import kotlinx.serialization.Serializable

/**
 * Declares the supported workflow recipes for a Junction harness.
 *
 * The discussion harness remains the default, while the remaining recipes layer plan, vote, act, verify, adjust,
 * and output handoff phases on top of the same harness runtime.
 */
@Serializable
enum class JunctionWorkflowRecipe
{
    DISCUSSION_ONLY,
    VOTE_ACT_VERIFY_REPEAT,
    ACT_VOTE_VERIFY_REPEAT,
    VOTE_PLAN_ACT_VERIFY_REPEAT,
    PLAN_VOTE_ACT_VERIFY_REPEAT,
    VOTE_PLAN_OUTPUT_EXIT,
    PLAN_VOTE_ADJUST_OUTPUT_EXIT;

    /**
     * Get the ordered phases executed by this recipe in each cycle.
     *
     * @return Ordered workflow phases for one cycle.
     */
    fun phases(): List<JunctionWorkflowPhase>
    {
        return when(this)
        {
            DISCUSSION_ONLY -> listOf()
            VOTE_ACT_VERIFY_REPEAT -> listOf(
                JunctionWorkflowPhase.VOTE,
                JunctionWorkflowPhase.ACT,
                JunctionWorkflowPhase.VERIFY
            )

            ACT_VOTE_VERIFY_REPEAT -> listOf(
                JunctionWorkflowPhase.ACT,
                JunctionWorkflowPhase.VOTE,
                JunctionWorkflowPhase.VERIFY
            )

            VOTE_PLAN_ACT_VERIFY_REPEAT -> listOf(
                JunctionWorkflowPhase.VOTE,
                JunctionWorkflowPhase.PLAN,
                JunctionWorkflowPhase.ACT,
                JunctionWorkflowPhase.VERIFY
            )

            PLAN_VOTE_ACT_VERIFY_REPEAT -> listOf(
                JunctionWorkflowPhase.PLAN,
                JunctionWorkflowPhase.VOTE,
                JunctionWorkflowPhase.ACT,
                JunctionWorkflowPhase.VERIFY
            )

            VOTE_PLAN_OUTPUT_EXIT -> listOf(
                JunctionWorkflowPhase.VOTE,
                JunctionWorkflowPhase.PLAN,
                JunctionWorkflowPhase.OUTPUT
            )

            PLAN_VOTE_ADJUST_OUTPUT_EXIT -> listOf(
                JunctionWorkflowPhase.PLAN,
                JunctionWorkflowPhase.VOTE,
                JunctionWorkflowPhase.ADJUST,
                JunctionWorkflowPhase.OUTPUT
            )
        }
    }

    /**
     * Check whether this recipe is intended to repeat until a later phase says to stop.
     *
     * @return True when the recipe should continue cycling until a condition is met.
     */
    fun repeats(): Boolean
    {
        return this in listOf(
            VOTE_ACT_VERIFY_REPEAT,
            ACT_VOTE_VERIFY_REPEAT,
            VOTE_PLAN_ACT_VERIFY_REPEAT,
            PLAN_VOTE_ACT_VERIFY_REPEAT
        )
    }

    /**
     * Check whether this recipe is designed to end by emitting handoff instructions.
     *
     * @return True when the recipe should stop after output preparation.
     */
    fun endsWithHandoff(): Boolean
    {
        return this in listOf(
            VOTE_PLAN_OUTPUT_EXIT,
            PLAN_VOTE_ADJUST_OUTPUT_EXIT
        )
    }
}

/**
 * Declares the reusable workflow phases that Junction can orchestrate.
 */
@Serializable
enum class JunctionWorkflowPhase
{
    PLAN,
    VOTE,
    ACT,
    VERIFY,
    ADJUST,
    OUTPUT
}

/**
 * Captures one workflow phase result from a Junction harness execution.
 *
 * @param phase The phase that produced this result.
 * @param cycleNumber The workflow cycle in which the result was produced.
 * @param participantName The participant or fallback controller that produced the result.
 * @param text Primary phase output text.
 * @param instructions Optional execution instructions or handoff text.
 * @param passed Whether the phase succeeded.
 * @param repeatRequested Whether the phase asked Junction to run another cycle.
 * @param terminateRequested Whether the phase asked Junction to stop immediately.
 * @param notes Human-readable commentary or decision notes.
 * @param rawOutput The unparsed raw output returned by the participant.
 */
@Serializable
data class JunctionWorkflowPhaseResult(
    var phase: JunctionWorkflowPhase = JunctionWorkflowPhase.PLAN,
    var cycleNumber: Int = 0,
    var participantName: String = "",
    var text: String = "",
    var instructions: String = "",
    var passed: Boolean = true,
    var repeatRequested: Boolean = false,
    var terminateRequested: Boolean = false,
    var notes: String = "",
    var rawOutput: String = ""
)

/**
 * Mutable workflow state for a live Junction workflow execution.
 *
 * @param recipe The workflow recipe being executed.
 * @param currentCycle The current workflow cycle number.
 * @param maxCycles The maximum number of workflow cycles.
 * @param phaseOrder The phase order for the selected recipe.
 * @param completed Whether the workflow completed successfully.
 * @param handoffOnly Whether the workflow is currently producing handoff instructions instead of in-process actions.
 * @param repeatRequested Whether another cycle has been requested.
 * @param verificationPassed Whether the most recent verification succeeded.
 * @param planText Cached planning output.
 * @param voteText Cached voting summary output.
 * @param actText Cached action output.
 * @param verifyText Cached verification output.
 * @param adjustText Cached adjustment output.
 * @param outputText Cached output or handoff text.
 * @param phaseResults Ordered results for each phase execution.
 * @param roundLog Human-readable workflow notes.
 * @param discussionDecision The latest discussion decision produced during voting.
 */
@Serializable
data class JunctionWorkflowState(
    var recipe: JunctionWorkflowRecipe = JunctionWorkflowRecipe.DISCUSSION_ONLY,
    var currentCycle: Int = 0,
    var maxCycles: Int = 3,
    var phaseOrder: MutableList<JunctionWorkflowPhase> = mutableListOf(),
    var completed: Boolean = false,
    var handoffOnly: Boolean = false,
    var repeatRequested: Boolean = false,
    var verificationPassed: Boolean = true,
    var planText: String = "",
    var voteText: String = "",
    var actText: String = "",
    var verifyText: String = "",
    var adjustText: String = "",
    var outputText: String = "",
    var phaseResults: MutableList<JunctionWorkflowPhaseResult> = mutableListOf(),
    var roundLog: MutableList<String> = mutableListOf(),
    var discussionDecision: DiscussionDecision = DiscussionDecision()
)

/**
 * Final structured workflow artifact emitted by the Junction harness.
 *
 * @param topic The active topic that the workflow operated on.
 * @param recipe The workflow recipe that was used.
 * @param completed Whether the workflow completed successfully.
 * @param cyclesExecuted How many workflow cycles were executed.
 * @param handoffOnly Whether the workflow stopped at instructions rather than doing in-process side effects.
 * @param finalText The final human-readable output or handoff instructions.
 * @param discussionDecision The latest discussion decision produced during voting.
 * @param phaseResults All recorded workflow phase results.
 * @param notes Additional workflow commentary or failure details.
 */
@Serializable
data class JunctionWorkflowOutcome(
    var topic: String = "",
    var recipe: JunctionWorkflowRecipe = JunctionWorkflowRecipe.DISCUSSION_ONLY,
    var completed: Boolean = false,
    var cyclesExecuted: Int = 0,
    var handoffOnly: Boolean = false,
    var finalText: String = "",
    var discussionDecision: DiscussionDecision = DiscussionDecision(),
    var phaseResults: MutableList<JunctionWorkflowPhaseResult> = mutableListOf(),
    var notes: String = ""
)
