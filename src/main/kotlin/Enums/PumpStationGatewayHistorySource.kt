package com.TTT.Enums

/**
 * Selects which history the gateway agent reads when it evaluates a step.
 *
 * [Summarized] is the pruned, curated history the judge and dispatch agents see.
 * [Full] is the full retained event log, including entries the pruned view has dropped.
 */
enum class PumpStationGatewayHistorySource
{
    Summarized,
    Full
}
