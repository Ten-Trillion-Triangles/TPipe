package com.TTT.AgentCore.evaluations

import aws.smithy.kotlin.runtime.content.Document

/** State supplied to a provider-neutral user simulator for each turn. */
data class AgentCoreSimulationContext(
    val scenario: Document,
    val turnIndex: Int,
    val priorTurns: List<Document>
)

/** One generated user turn and optional stop metadata. */
data class AgentCoreSimulationTurn(
    val input: Document,
    val stop: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

/** Result of a provider-neutral simulation. */
data class AgentCoreSimulationResult(
    val scenario: Document,
    val turns: List<AgentCoreSimulationTurn>,
    val stoppedBy: String
)

/** Caller-owned simulator; it may be backed by any TPipe Pipe provider. */
fun interface AgentCoreUserSimulator
{
    /** Generate the next turn, or return null to stop. */
    suspend fun nextTurn(context: AgentCoreSimulationContext): AgentCoreSimulationTurn?
}

/** Execute bounded multi-turn user simulation without a provider dependency. */
suspend fun AgentCoreUserSimulator.simulate(
    scenario: Document,
    maxTurns: Int = 20
): AgentCoreSimulationResult
{
    require(maxTurns > 0) { "maxTurns must be positive." }
    val turns = mutableListOf<AgentCoreSimulationTurn>()
    var stopReason = "simulator"
    while(turns.size < maxTurns)
    {
        val turn = nextTurn(AgentCoreSimulationContext(scenario, turns.size, turns.map { it.input }))
        if(turn == null)
        {
            stopReason = "simulator-null"
            break
        }
        turns += turn
        if(turn.stop)
        {
            stopReason = turn.metadata["stopReason"] ?: "turn"
            break
        }
    }
    if(turns.size >= maxTurns && turns.none { it.stop }) stopReason = "max-turns"
    return AgentCoreSimulationResult(scenario, turns, stopReason)
}
