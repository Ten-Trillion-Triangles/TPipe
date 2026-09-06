package com.TTT.Enums

/**
 * Selects which retained PumpStation history is supplied to goal validation.
 */
enum class PumpStationGoalHistorySource
{
    /** Supply the curated, context-managed turn history. */
    Curated,

    /** Supply the complete retained raw event history. */
    Full
}
