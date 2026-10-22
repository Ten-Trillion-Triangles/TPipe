package com.TTT.Pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-station wait / join substrate. The harness main loop uses this service to
 * "go to sleep" until a [PumpStationWaitSpec] condition is met, then resume
 * forward. It is the backing store for [PumpStation.requestWait] /
 * [PumpStation.waitUntil].
 *
 * Three spec shapes are supported (see [PumpStationWaitSpec]):
 *  - [AllPathsSettled] — the join-all-paths mode. The harness loop calls
 *    [notifyPathSettled] on every in-flight path leg completion; this service
 *    releases every active `AllPathsSettled` wait once
 *    [PumpStation.inFlightPathCount] returns to zero.
 *  - [OnEvent] — released when a matching [PumpStationEvent] flows through the
 *    station's event funnel. The station calls [eventObserved] on every
 *    emitted event, gated by [hasEventFilters] so the hot path stays zero-cost
 *    when no `OnEvent` wait is active.
 *  - [OnCondition] — resolved by polling a developer predicate inline on the
 *    awaiting coroutine (no background job), bounded by the wait's timeout.
 *
 * Thread-safety model: all state is guarded by a single lock via
 * [synchronized]; producers ([release], [cancelAll], [notifyPathSettled],
 * [eventObserved]) are safe to call from any thread or coroutine. Each wait's
 * wake channel is buffered (capacity 1), so a release that lands *before* the
 * awaiting coroutine suspends is still observed when it eventually suspends;
 * an early release is additionally recorded via the per-wait `settled` flag
 * so a later [PumpStationWaitHandle.awaitResolution] returns the settled reason
 * without re-suspending.
 *
 * The service does NOT emit events. Event publication ([WaitStarted] /
 * [WaitCompleted]) happens in the station's loop so the correct
 * [PumpStationTaskState] `runId` / `turnIndex` / `phase` are stamped.
 */
class PumpStationWaitService
{
    private data class ActiveWait(
        val name: String,
        val spec: PumpStationWaitSpec,
        val station: PumpStation,
        val startedMs: Long,
        val timeoutMs: Long?,
        val wake: Channel<PumpStationWaitReason>
    )
    {
        @Volatile
        var settled: Boolean = false
        @Volatile
        var settledReason: PumpStationWaitReason? = null
    }

    private val lock = Any()
    private val activeWaits: MutableMap<String, ActiveWait> = ConcurrentHashMap()

    /**
     * Register a wait for [spec] under [name]. If [timeoutMs] is non-null the
     * wait is bounded to that many milliseconds on
     * [PumpStationWaitHandle.awaitResolution]; otherwise the wait is unbounded
     * until released, cancelled, or force-halted.
     *
     * @return A [PumpStationWaitHandle] the caller can suspend on, release, or
     *   cancel.
     */
    fun request(
        spec: PumpStationWaitSpec,
        name: String,
        timeoutMs: Long?,
        station: PumpStation
    ): PumpStationWaitHandle
    {
        // Capacity-1 buffered channel: a release that lands *before* the
        // awaiting coroutine suspends is buffered (never dropped, as a
        // rendezvous channel would drop it), so an early settle is always
        // observed by a later awaitResolution.
        val wake = Channel<PumpStationWaitReason>(capacity = 1)
        val settleCounter = AtomicInteger(0)
        val startedMs = System.currentTimeMillis()
        val handle = PumpStationWaitHandle(name, spec, startedMs, this, settleCounter)
        synchronized(lock)
        {
            activeWaits[name] = ActiveWait(name, spec, station, startedMs, timeoutMs, wake)
        }
        return handle
    }

    /**
     * Settle the wait named [name] with [reason]. Idempotent: a second release
     * after the wait has already settled is a no-op. Wakes any coroutine
     * suspended in [PumpStationWaitHandle.awaitResolution].
     */
    fun release(name: String, reason: PumpStationWaitReason)
    {
        val w = synchronized(lock) { activeWaits[name]?.takeIf { !it.settled } } ?: return
        w.settled = true
        w.settledReason = reason
        w.wake.trySend(reason)
    }

    /**
     * Settle every active wait with [PumpStationWaitReason.Cancelled]. Used by
     * [PumpStation.forceHalt], [PumpStation.cancelAsyncJobs], and
     * [PumpStation.restoreSnapshot] so no suspended loop is left waiting on a
     * stale condition.
     */
    fun cancelAll()
    {
        val names: List<String> = synchronized(lock) { activeWaits.keys.toList() }
        names.forEach { release(it, PumpStationWaitReason.Cancelled) }
    }

    /**
     * Called by the harness loop after each in-flight async path leg settles
     * (success or isolated failure). For every active [AllPathsSettled] wait,
     * release it once the station's in-flight path counter is zero.
     */
    fun notifyPathSettled(station: PumpStation)
    {
        if (station.inFlightPathCount() > 0) return
        val names: List<String> = synchronized(lock)
        {
            activeWaits.entries
                .filter { !it.value.settled && it.value.spec is AllPathsSettled }
                .map { it.key }
        }
        names.forEach { release(it, PumpStationWaitReason.Resolved) }
    }

    /**
     * Called by the station's event funnel on every emitted event. For every
     * active [OnEvent] wait whose filter matches [event], release it. Gated
     * by [hasEventFilters] so the funnel short-circuits with no allocation
     * when no `OnEvent` wait is in flight.
     */
    fun eventObserved(event: PumpStationEvent)
    {
        val names: List<String> = synchronized(lock)
        {
            activeWaits.entries
                .filter { !it.value.settled }
                .mapNotNull { (key, w) ->
                    val spec = w.spec
                    if (spec is OnEvent && spec.filter(event)) key else null
                }
        }
        names.forEach { release(it, PumpStationWaitReason.Resolved) }
    }

    /**
     * True when at least one active wait is an [OnEvent] spec. The station's
     * event funnel consults this before calling [eventObserved] to keep the
     * default (no-`OnEvent`) run allocation-free.
     */
    fun hasEventFilters(): Boolean
    {
        return synchronized(lock) { activeWaits.values.any { !it.settled && it.spec is OnEvent } }
    }

    /**
     * Number of active (unsettled) waits. Useful for tests and observability.
     */
    fun activeWaitCount(): Int
    {
        return synchronized(lock) { activeWaits.values.count { !it.settled } }
    }

    /**
     * Suspend the current coroutine until the wait [handle] settles, bounded
     * by [timeoutMs] when it is non-null (otherwise the wait's stored
     * [ActiveWait.timeoutMs]). Returns the [PumpStationWaitResult]. This is
     * the backing call for [PumpStationWaitHandle.awaitResolution].
     */
    internal suspend fun await(
        handle: PumpStationWaitHandle,
        timeoutMs: Long?
    ): PumpStationWaitResult
    {
        val name = handle.waitName
        val w = synchronized(lock) { activeWaits[name] }
            ?: return PumpStationWaitResult.cancelled(name, 0L, handle.waitSpec.label(), PumpStationWaitReason.Cancelled)
        val effectiveTimeout = timeoutMs ?: w.timeoutMs
        val station = w.station

        val received: PumpStationWaitReason? = when (val spec = w.spec)
        {
            is OnCondition -> pollCondition(w, spec, station, effectiveTimeout)
            is AllPathsSettled -> awaitAllPathsSettled(w, station, effectiveTimeout)
            else -> awaitWake(w, effectiveTimeout)
        }

        val reason = received ?: w.settledReason ?: PumpStationWaitReason.Timeout
        if (!w.settled)
        {
            w.settled = true
            w.settledReason = reason
        }
        val satisfied = reason == PumpStationWaitReason.Resolved
        val elapsed = System.currentTimeMillis() - w.startedMs
        return PumpStationWaitResult(name, satisfied, reason, elapsed, w.spec.label())
    }

    /**
     * Suspend on the per-wait wake channel until it is released (by
     * [notifyPathSettled], [eventObserved], or a manual [PumpStationWaitHandle.release]),
     * bounded by [timeoutMs]. Returns the reason that woke it, or null when
     * the bound elapsed first.
     */
    private suspend fun awaitWake(w: ActiveWait, timeoutMs: Long?): PumpStationWaitReason?
    {
        val bound: Long? = timeoutMs?.takeIf { it > 0 }
        return if (bound == null)
        {
            w.wake.receive()
        }
        else
        {
            withTimeoutOrNull(bound) { w.wake.receive() }
        }
    }

    /**
     * Resolve an [AllPathsSettled] wait. First checks whether every in-flight
     * path leg has ALREADY settled before this await began — if the counter is
     * zero we resolve without suspending (closing the "all legs finished
     * before the join was requested" race, since [notifyPathSettled] only
     * fires on settle and would have found no active wait at that moment).
     * Otherwise suspend on the wake channel as set by [notifyPathSettled]
     * when the last leg settles, bounded by [timeoutMs].
     */
    private suspend fun awaitAllPathsSettled(
        w: ActiveWait,
        station: PumpStation,
        timeoutMs: Long?
    ): PumpStationWaitReason?
    {
        if (station.inFlightPathCount() == 0)
        {
            return settleCondition(w)
        }
        return awaitWake(w, timeoutMs)
    }

    /**
     * Resolve an [OnCondition] wait. When the spec carries a non-null
     * [OnCondition.predicate], poll it on the awaiting coroutine every
     * `pollIntervalMs` until it returns true, the wait is released
     * externally, the harness has an exit reason, or the timeout bound
     * elapses. When the predicate is null the wait behaves like a
     * manual-release wait (it only settles via [PumpStationWaitHandle.release]
     * or its timeout).
     */
    private suspend fun pollCondition(
        w: ActiveWait,
        spec: OnCondition,
        station: PumpStation,
        timeoutMs: Long?
    ): PumpStationWaitReason?
    {
        val predicate = spec.predicate
        if (predicate == null) return awaitWake(w, timeoutMs)

        val intervalMs = spec.pollIntervalMs.coerceAtLeast(1).toLong()
        val bound: Long? = timeoutMs?.takeIf { it > 0 }
        return if (bound == null)
        {
            pollOnCondition(w, predicate, station, intervalMs)
        }
        else
        {
            withTimeoutOrNull(bound) { pollOnCondition(w, predicate, station, intervalMs) }
        }
    }

    /**
     * Poll [predicate] on the awaiting coroutine every [intervalMs] until it
     * returns true (settling the wait as [PumpStationWaitReason.Resolved]), the
     * wait was released externally, or the harness has an exit reason. Returns
     * the reason that ended the poll, or null when the timeout bound elapsed
     * first (the caller's [withTimeoutOrNull] surfaces that as a null result).
     */
    private suspend fun pollOnCondition(
        w: ActiveWait,
        predicate: suspend (PumpStation) -> Boolean,
        station: PumpStation,
        intervalMs: Long
    ): PumpStationWaitReason?
    {
        while (true)
        {
            if (w.settled) return w.settledReason
            if (evaluateCondition(predicate, station)) return settleCondition(w)
            if (station.taskState.exitReason != null) return null
            w.wake.tryReceive().getOrNull()?.let { return it }
            delay(intervalMs)
        }
    }

    /**
     * Evaluate an [OnCondition] predicate, treating any non-cancellation
     * failure as "not yet".
     */
    private suspend fun evaluateCondition(
        predicate: suspend (PumpStation) -> Boolean,
        station: PumpStation
    ): Boolean
    {
        return try
        {
            predicate(station)
        }
        catch (e: CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            false
        }
    }

    /** Record an `OnCondition` / `AllPathsSettled` resolution as settled + Resolved on [w]. */
    private fun settleCondition(w: ActiveWait): PumpStationWaitReason
    {
        w.settled = true
        w.settledReason = PumpStationWaitReason.Resolved
        return PumpStationWaitReason.Resolved
    }

    /**
     * Called by a handle's [PumpStationWaitHandle.awaitResolution] after
     * [await] returns, to remove the wait from the active map exactly once.
     * The settle-counter guard makes a second await on the same handle a
     * no-op for bookkeeping.
     */
    internal fun complete(name: String, settleCounter: AtomicInteger)
    {
        if (settleCounter.compareAndSet(0, 1))
        {
            synchronized(lock) { activeWaits.remove(name) }
        }
    }
}
