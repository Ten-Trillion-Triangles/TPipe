package com.TTT.Pipeline

import kotlinx.serialization.Serializable

/**
 * Declares how a Junction should schedule discussion turns across its moderator and participants.
 */
@Serializable
enum class DiscussionStrategy
{
    SIMULTANEOUS,
    CONVERSATIONAL,
    ROUND_ROBIN
}

/**
 * Captures one participant's contribution during a Junction round.
 *
 * @param participantName The participant that produced the opinion.
 * @param roundNumber The round in which the opinion was captured.
 * @param opinion The participant's textual position.
 * @param vote The vote value the participant prefers.
 * @param confidence The participant's confidence in the vote or opinion.
 * @param reasoning Supporting reasoning for the contribution.
 * @param rawOutput The unparsed raw output returned by the participant.
 */
@Serializable
data class ParticipantOpinion(
    var participantName: String = "",
    var roundNumber: Int = 0,
    var opinion: String = "",
    var vote: String = "",
    var confidence: Double = 0.0,
    var reasoning: String = "",
    var rawOutput: String = ""
)

/**
 * Stores the tally for one vote option in a Junction round.
 *
 * @param option The option being tallied.
 * @param weight The accumulated vote weight for the option.
 * @param percentage The percentage of the total vote weight.
 * @param supporters The participants that supported the option.
 * @param thresholdMet Whether the option met the configured consensus threshold.
 */
@Serializable
data class VotingResult(
    var option: String = "",
    var weight: Double = 0.0,
    var percentage: Double = 0.0,
    var supporters: MutableList<String> = mutableListOf(),
    var thresholdMet: Boolean = false
)

/**
 * Directive emitted by the moderator pipeline to control the next Junction step.
 *
 * @param continueDiscussion Whether the harness should keep iterating.
 * @param selectedParticipants The participants that should speak next. An empty list means use the configured strategy.
 * @param finalDecision Optional final decision text.
 * @param nextRoundPrompt Optional extra guidance for the next round.
 * @param notes Moderator reasoning or commentary.
 */
@Serializable
data class ModeratorDirective(
    var continueDiscussion: Boolean = true,
    var selectedParticipants: MutableList<String> = mutableListOf(),
    var finalDecision: String = "",
    var nextRoundPrompt: String = "",
    var notes: String = ""
)

/**
 * Mutable runtime state for a live Junction discussion.
 *
 * @param topic The active topic or proposal being discussed.
 * @param strategy The configured turn-taking strategy.
 * @param currentRound The current round number.
 * @param maxRounds The maximum number of rounds to execute.
 * @param consensusThreshold The minimum winning percentage needed to stop early.
 * @param maxNestedDepth Safety guard for nested P2P container dispatch.
 * @param consensusReached Whether the harness has already reached consensus.
 * @param finalDecision The final decision text selected for the discussion.
 * @param moderatorNotes Latest moderator commentary or override text.
 * @param selectedParticipants Participants selected for the next conversational turn.
 * @param participantOpinions Most recent opinion from each participant.
 * @param voteResults Current vote tally for the active round.
 * @param roundLog Human-readable notes captured during execution.
 */
@Serializable
data class DiscussionState(
    var topic: String = "",
    var strategy: DiscussionStrategy = DiscussionStrategy.SIMULTANEOUS,
    var currentRound: Int = 0,
    var maxRounds: Int = 3,
    var consensusThreshold: Double = 0.75,
    var maxNestedDepth: Int = 8,
    var consensusReached: Boolean = false,
    var finalDecision: String = "",
    var moderatorNotes: String = "",
    var selectedParticipants: MutableList<String> = mutableListOf(),
    var participantOpinions: MutableMap<String, ParticipantOpinion> = mutableMapOf(),
    var voteResults: MutableList<VotingResult> = mutableListOf(),
    var roundLog: MutableList<String> = mutableListOf()
)

/**
 * Final structured decision artifact emitted by the Junction harness.
 *
 * @param topic The topic that was discussed.
 * @param decision The final decision text.
 * @param consensusReached Whether the decision was supported by the configured threshold.
 * @param roundsExecuted How many rounds were executed before termination.
 * @param strategy The strategy that was used.
 * @param moderatorNotes Final moderator commentary or override reason.
 * @param participantOpinions Final opinions captured from participants.
 * @param voteResults Final vote tally.
 */
@Serializable
data class DiscussionDecision(
    var topic: String = "",
    var decision: String = "",
    var consensusReached: Boolean = false,
    var roundsExecuted: Int = 0,
    var strategy: DiscussionStrategy = DiscussionStrategy.SIMULTANEOUS,
    var moderatorNotes: String = "",
    var participantOpinions: MutableMap<String, ParticipantOpinion> = mutableMapOf(),
    var voteResults: MutableList<VotingResult> = mutableListOf()
)

