package com.TTT.Pipeline

/** Selects the PumpStation that receives momentary runtime controls. */
enum class PumpStationControlTargetMode
{
    /** Keep the control on the station where the API was called. */
    Local,

    /** Follow the active blocking foreground child chain to its deepest station. */
    DeepestActive
}

/**
 * Immutable value-only description of the active foreground control route.
 * Live PumpStation references are intentionally not exposed.
 */
data class PumpStationControlRoute(
    val targetRunId: String,
    val depth: Int,
    val pathChain: List<String>,
    val cycleDetected: Boolean = false
)
