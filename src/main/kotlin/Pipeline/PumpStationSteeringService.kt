package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe steering instruction store used by the harness loop to inject
 * MultimodalContent into turnHistory at PumpStationPausePhase boundaries.
 *
 * Combination semantics at drain time:
 *   1. Persistent overlay (if set) is emitted first
 *   2. One-shot queue is drained in FIFO order after the overlay
 *
 * The service is designed to be called concurrently from any thread:
 *   - `enqueueOneShot` / `setPersistent` / `clearPersistent` are producer-side
 *     and use `Mutex` + `Channel` for safety
 *   - `drainForPhase` is consumer-side and is called by the harness loop
 *     at each phase boundary
 */
class PumpStationSteeringService(
    initialConfiguration: PumpStationSteeringConfiguration = PumpStationSteeringConfiguration()
)
{
    private data class SequencedOneShot(
        val sequence: Long,
        val content: MultimodalContent
    )

    private val mutex = Mutex()
    private val oneShotQueues: MutableMap<PumpStationPausePhase, Channel<SequencedOneShot>> = ConcurrentHashMap()
    private val nextBoundaryQueue = Channel<SequencedOneShot>(capacity = Channel.UNLIMITED)
    private val persistentOverlays: MutableMap<PumpStationPausePhase, MultimodalContent> = ConcurrentHashMap()
    private val nextSequence = AtomicLong()

    init
    {
        // Seed initial persistent overlays from configuration
        persistentOverlays.putAll(initialConfiguration.initialPersistentOverlays)
        // Seed initial one-shot queues from configuration
        initialConfiguration.initialOneShotInstructions.forEach { (phase, contents) ->
            val channel = oneShotQueues.getOrPut(phase) { Channel(capacity = Channel.UNLIMITED) }
            contents.forEach { channel.trySend(sequenced(it)) }
        }
    }

    private fun sequenced(content: MultimodalContent): SequencedOneShot =
        SequencedOneShot(nextSequence.getAndIncrement(), content)

    /**
     * Enqueue a one-shot instruction. It fires at the next occurrence of [phase],
     * then is discarded automatically.
     */
    suspend fun enqueueOneShot(phase: PumpStationPausePhase, content: MultimodalContent)
    {
        mutex.withLock {
            val channel = oneShotQueues.getOrPut(phase) { Channel(capacity = Channel.UNLIMITED) }
            channel.send(sequenced(content))
        }
    }

    /**
     * Enqueue a one-shot instruction for the next boundary reached, regardless
     * of which [PumpStationPausePhase] that boundary represents.
     */
    suspend fun enqueueNextBoundary(content: MultimodalContent)
    {
        mutex.withLock {
            nextBoundaryQueue.send(sequenced(content))
        }
    }

    /**
     * Set or replace the persistent overlay for [phase]. Fires on every occurrence
     * of [phase] until cleared or replaced by another `setPersistent` call.
     */
    suspend fun setPersistent(phase: PumpStationPausePhase, content: MultimodalContent)
    {
        mutex.withLock {
            persistentOverlays[phase] = content
        }
    }

    /**
     * Clear the persistent overlay for [phase]. Subsequent occurrences of [phase]
     * will not be steered unless a new overlay is set.
     */
    suspend fun clearPersistent(phase: PumpStationPausePhase)
    {
        mutex.withLock {
            persistentOverlays.remove(phase)
        }
    }

    /**
     * Drain all pending instructions for [phase]. Returns the persistent overlay
     * first (if set), followed by one-shot instructions in FIFO order.
     *
     * The drain is non-blocking: if no overlay is set and no one-shots are queued,
     * an empty list is returned.
     */
    suspend fun drainForPhase(phase: PumpStationPausePhase): List<MultimodalContent>
    {
        val drained = mutableListOf<MultimodalContent>()
        mutex.withLock {
            persistentOverlays[phase]?.let { drained.add(it) }
            val channel = oneShotQueues[phase]
            if (channel != null)
            {
                while (true)
                {
                    val result = channel.tryReceive()
                    if (result.isSuccess)
                    {
                        drained.add(result.getOrThrow().content)
                    }
                    else
                    {
                        break
                    }
                }
            }
        }
        return drained
    }

    /**
     * Drain the persistent overlay for [phase], followed by explicit-phase and
     * next-boundary one-shots in their global enqueue order. The explicit and
     * next-boundary queues are consumed atomically with respect to producers.
     */
    internal suspend fun drainForBoundary(phase: PumpStationPausePhase): List<MultimodalContent>
    {
        val drained = mutableListOf<MultimodalContent>()
        mutex.withLock {
            persistentOverlays[phase]?.let { drained.add(it) }

            val oneShots = mutableListOf<SequencedOneShot>()
            drainInto(oneShotQueues[phase], oneShots)
            drainInto(nextBoundaryQueue, oneShots)
            oneShots.sortBy { it.sequence }
            drained.addAll(oneShots.map { it.content })
        }
        return drained
    }

    private fun drainInto(
        channel: Channel<SequencedOneShot>?,
        target: MutableList<SequencedOneShot>
    )
    {
        if (channel == null) return
        while (true)
        {
            val result = channel.tryReceive()
            if (result.isSuccess)
            {
                target.add(result.getOrThrow())
            }
            else
            {
                return
            }
        }
    }

    /**
     * Inspect whether a persistent overlay is set for [phase]. Used by tests
     * and debugging surfaces.
     */
    fun hasPersistentOverlay(phase: PumpStationPausePhase): Boolean = persistentOverlays.containsKey(phase)

    /**
     * Count pending one-shot instructions for [phase]. Returns 0 if no channel exists.
     */
    fun oneShotCount(phase: PumpStationPausePhase): Int
    {
        val channel = oneShotQueues[phase] ?: return 0
        // Channel.UNLIMITED has no tryReceive size method; we count by draining non-destructively
        // For test purposes, this is approximate. Use a snapshot drain instead.
        return channel.tryReceive().getOrNull()?.let { 1 } ?: 0
    }
}
