package com.TTT.Pipeline

/** Value-only attribution for a PumpStation session update. */
data class PumpStationSessionSource(
    val runId: String,
    val depth: Int,
    val pathChain: List<String>
)

/** Common metadata carried by every session update. */
sealed interface PumpStationSessionUpdate
{
    val sequence: Long
    val sessionId: String
    val source: PumpStationSessionSource
}

/** A PumpStation event observed by a session. */
data class PumpStationSessionEventUpdate(
    override val sequence: Long,
    override val sessionId: String,
    override val source: PumpStationSessionSource,
    val event: PumpStationEvent
) : PumpStationSessionUpdate

/** A streamed text chunk observed by a session. */
data class PumpStationSessionStreamUpdate(
    override val sequence: Long,
    override val sessionId: String,
    override val source: PumpStationSessionSource,
    val chunk: String
) : PumpStationSessionUpdate
