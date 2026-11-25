package com.TTT.Enums

/**
 * Station settings a gateway stage may reconfigure.
 *
 * The set is deliberately narrow: only knobs that change what the agent can reach or how
 * the harness behaves for the next step. Adding a value here is a source-compatible change.
 */
enum class PumpStationGatewaySetting
{
    ForegroundTurnInterval,
    BackgroundTurnInterval,
    MaxConcurrentForegroundAgents,
    MaxConcurrentBackgroundAgents,
    CompactionThreshold,
    MaxTurnHistorySize,
    MaxTurns
}
