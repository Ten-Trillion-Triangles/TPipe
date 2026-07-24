package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent

/**
 * Thrown by [PumpStation.injectInterruptForPhase] when the [PumpStationInterruptService]
 * has a queued interrupt for the polled phase. Carries the content to inject into
 * turnHistory and the [PumpStationInterruptSnapshot] taken at the most recent
 * BeforeJudge of the current turn, used to rewind the harness state before the
 * turn re-enters from the top of its loop.
 *
 * Caught at the top of [runHarnessLoop] around the [runTurn] invocation. The
 * catch handler restores the snapshot, appends [content] to turnHistory with
 * the canonical `metadata["interrupt"]` envelope, and re-invokes [runTurn]
 * without incrementing `taskState.turnIndex`.
 */
class PumpStationInterruptException(
    val content: MultimodalContent,
    val snapshot: PumpStationInterruptSnapshot
) : RuntimeException("PumpStation interrupt fired at turnIndex=${snapshot.turnIndex}")
