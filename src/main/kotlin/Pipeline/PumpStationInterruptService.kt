package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe interrupt queue used by the harness loop to receive out-of-band
 * messages that must stop the active turn and inject into turnHistory before
 * the turn re-enters from the top.
 *
 * Unlike [PumpStationSteeringService], interrupts are one-shot only — there is
 * no persistent overlay. An interrupt is a discrete event (a user pressing
 * the stop button, a framework sending a new instruction, a watchdog deciding
 * the path is looping).
 *
 * Combination semantics at drain time:
 *   - The FIRST entry in the per-phase queue is returned to the caller as
 *     the active interrupt. The caller (the harness loop) rewinds and re-enters
 *     the turn with this entry.
 *   - Any REMAINING entries in the queue are not lost. They are forwarded to
 *     [PumpStationSteeringService] as one-shot steering instructions for the
 *     same phase. If the steering service is not configured for that phase
 *     (no one-shot channel exists, no persistent overlay), the overflow entries
 *     are silently dropped and an [InterruptOverflowDropped] event is emitted
 *     for observability (operator-confirmed requirement, 2026-07-24).
 */
class PumpStationInterruptService
{
    private val mutex = Mutex()
    private val oneShotQueues: MutableMap<PumpStationPausePhase, Channel<MultimodalContent>> = ConcurrentHashMap()

    /**
     * Enqueue an interrupt for [phase]. Thread-safe. May be called from any
     * coroutine or thread concurrently with the running loop.
     */
    suspend fun enqueue(phase: PumpStationPausePhase, content: MultimodalContent)
    {
        mutex.withLock {
            val channel = oneShotQueues.getOrPut(phase) { Channel(capacity = Channel.UNLIMITED) }
            channel.send(content)
        }
    }

    /**
     * Drain the first entry for [phase] and return it. Returns null if the
     * queue is empty. Overflow entries beyond the first are NOT discarded
     * by this method — see [drainAllForPhase] for the full semantics.
     */
    suspend fun drainForPhase(phase: PumpStationPausePhase): MultimodalContent?
    {
        mutex.withLock {
            val channel = oneShotQueues[phase] ?: return null
            return channel.tryReceive().getOrNull()
        }
    }

    /**
     * Drain ALL pending entries for [phase]. Returns a list ordered by FIFO
     * queue position. Used by the harness loop's overflow handler to forward
     * extras to the steering service.
     */
    suspend fun drainAllForPhase(phase: PumpStationPausePhase): List<MultimodalContent>
    {
        val drained = mutableListOf<MultimodalContent>()
        mutex.withLock {
            val channel = oneShotQueues[phase] ?: return emptyList()
            while (true)
            {
                val result = channel.tryReceive()
                if (result.isSuccess)
                {
                    drained.add(result.getOrThrow())
                }
                else
                {
                    break
                }
            }
        }
        return drained
    }

    /**
     * Count pending entries for [phase]. Returns 0 if no channel exists.
     * Approximate — uses a non-destructive tryReceive round-trip. For test
     * and observability use only.
     */
    fun queueDepth(phase: PumpStationPausePhase): Int
    {
        val channel = oneShotQueues[phase] ?: return 0
        val temp = mutableListOf<MultimodalContent>()
        while (true)
        {
            val r = channel.tryReceive()
            if (r.isSuccess) temp.add(r.getOrThrow()) else break
        }
        temp.forEach { channel.trySend(it) }
        return temp.size
    }

    /**
     * Inspect whether at least one entry is queued for [phase]. Approximate
     * (uses a non-destructive tryReceive). For test purposes.
     */
    fun hasPending(phase: PumpStationPausePhase): Boolean
    {
        val channel = oneShotQueues[phase] ?: return false
        return channel.tryReceive().getOrNull()?.let { true } ?: false
    }
}
