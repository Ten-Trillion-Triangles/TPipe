package com.TTT.Enums

/**
 * A harness subsystem that a developer can force-activate at runtime.
 *
 * Most PumpStation subsystems are dormant on any given turn: the health agent only
 * fires on its interval or error ratio, the lorebook and summary agents only fire on
 * their interval or on context pressure, compaction only fires past the fill
 * threshold, and the harness agent slots only fire on their turn interval.
 *
 * A force flag overrides the timing gate so the subsystem runs at the next point the
 * harness would have evaluated it. It never conjures a subsystem that was never
 * configured - an unconfigured target reports a skipped force instead.
 *
 * @see com.TTT.Pipeline.PumpStation.force
 */
enum class PumpStationForceTarget
{
    /** The judge agent. Overrides the FlagTriggered skip and the first-turn skip. */
    Judge,

    /** The health agent. Overrides its turn interval and error-ratio thresholds. */
    HealthCheck,

    /** The lorebook agent. Overrides the shared memory-update interval and context pressure. */
    Lorebook,

    /** The summary agent. Overrides the shared memory-update interval and context pressure. */
    Summary,

    /** The compaction pipeline. Overrides the context fill-ratio threshold. */
    Compaction,

    /** The blocking (foreground) harness agent slots. Overrides their turn interval. */
    ForegroundAgents,

    /** The async (background) harness agent slots. Overrides their turn interval. */
    BackgroundAgents,

    /** The safe-prune pipeline. Overrides the history size threshold. */
    SafePrune
}
