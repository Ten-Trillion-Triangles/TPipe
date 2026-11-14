package com.TTT.Pipeline

import com.TTT.Enums.PumpStationForceTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe registry of armed one-shot force flags.
 *
 * A force flag lets a developer activate a harness subsystem that would otherwise be
 * dormant because its interval / threshold gate did not fire. This service holds the
 * armed flags; the harness loop consumes them at each subsystem's gate.
 *
 * Thread-safe: [arm] may be called from any thread or coroutine concurrently with a
 * running harness. Producers increment a per-target counter, and consumers decrement it
 * with a compare-and-set loop so a force is never consumed twice and the counter never
 * underflows.
 *
 * @see com.TTT.Pipeline.PumpStation.force
 */
class PumpStationForceService
{

//=========================================Properties===================================================================

    private val counters = ConcurrentHashMap<PumpStationForceTarget, AtomicInteger>()

//=========================================Public API===================================================================

    /**
     * Arm a one-shot force for [target].
     *
     * The subsystem runs at the next point the harness would have evaluated it, and the
     * force is consumed by that evaluation. Repeated arms queue: N arms produce N future
     * firings.
     *
     * @param target The subsystem to force-activate.
     */
    fun arm(target: PumpStationForceTarget)
    {
        counters.computeIfAbsent(target) { AtomicInteger() }.incrementAndGet()
    }

    /**
     * Consume one armed force for [target], if any.
     *
     * @param target The subsystem whose force is being claimed.
     * @return True when a force was armed and has now been consumed; false when none was armed.
     */
    fun consume(target: PumpStationForceTarget): Boolean
    {
        val counter = counters[target] ?: return false
        while(true)
        {
            val current = counter.get()
            if(current <= 0) return false
            if(counter.compareAndSet(current, current - 1)) return true
        }
    }

    /**
     * Whether a force is currently armed for [target].
     *
     * @param target The subsystem to inspect.
     * @return True when at least one force is armed for the target.
     */
    fun isArmed(target: PumpStationForceTarget): Boolean =
        counters[target]?.get()?.let { it > 0 } == true

    /**
     * Disarm the forces for a single target, or every target when [target] is null.
     *
     * @param target The subsystem to disarm, or null to disarm all of them.
     */
    fun clear(target: PumpStationForceTarget? = null)
    {
        if(target == null) clearAll() else counters.remove(target)
    }

    /** Disarm every target. */
    fun clearAll()
    {
        counters.clear()
    }
}
