package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import java.util.UUID

/**
 * One-shot steering instruction: enqueued by `steer(phase, content)` and
 * consumed on the next phase boundary. After consumption it is discarded.
 */
data class PumpStationSteeringOneShot(
    val phase: PumpStationPausePhase,
    val content: MultimodalContent,
    val injectionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Persistent-overlay steering instruction: set by `steerPersistent(phase, content)`,
 * fires on every occurrence of the phase until replaced or cleared.
 */
data class PumpStationSteeringPersistent(
    val phase: PumpStationPausePhase,
    val content: MultimodalContent,
    val injectionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Configuration block set via `pumpStation { steeringPolicy { ... } }`. Holds the
 * initial persistent overlays and one-shot instructions queued at construction time.
 */
data class PumpStationSteeringConfiguration(
    val initialPersistentOverlays: Map<PumpStationPausePhase, MultimodalContent> = emptyMap(),
    val initialOneShotInstructions: Map<PumpStationPausePhase, List<MultimodalContent>> = emptyMap()
)
