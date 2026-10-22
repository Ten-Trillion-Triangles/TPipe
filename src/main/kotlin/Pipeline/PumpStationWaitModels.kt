package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Why a [PumpStationWait] settled. Emitted on [WaitCompleted] and returned by
 * [PumpStationWaitResult.reason] so the trace can distinguish a resolution, a
 * timeout, and a forced cancellation.
 */
enum class PumpStationWaitReason
{
    /**
     * The condition was satisfied: all tracked paths settled, the awaited
     * event fired, the polled predicate returned true, or the wait was
     * released manually.
     */
    Resolved,

    /**
     * The configured timeout elapsed before the condition was satisfied. The
     * harness does not halt on a timeout by default; it continues with the
     * warning recorded in the event stream.
     */
    Timeout,

    /**
     * The wait was cancelled by [PumpStation.cancelWait], a scope
     * cancellation ([PumpStation.cancelAsyncJobs]), or snapshot restore.
     */
    Cancelled,

    /**
     * The wait was force-halted through [PumpStation.forceHalt] while the
     * harness loop was suspended awaiting it.
     */
    ForceHalted
}

/**
 * A developer-declared condition the [PumpStation] harness loop can suspend
 * on until the condition is met. A [PumpStationWaitSpec] value is passed to
 * [PumpStation.requestWait] / [PumpStation.waitUntil] to make the main loop
 * "go to sleep" until the condition holds.
 *
 * A spec intentionally holds lambda fields ([PumpStationEvent],
 * [PumpStation] predicates), so it is NOT [kotlinx.serialization.Serializable]
 * — only its [PumpStationWaitSpec.label] is carried on the emitted
 * [WaitStarted] / [WaitCompleted] events.
 *
 * Subtypes:
 *  - [AllPathsSettled] — resolves when no in-flight async/background paths
 *    remain (the join-all-paths use case).
 *  - [OnEvent] — resolves when a matching [PumpStationEvent] is observed
 *    through the harness event funnel.
 *  - [OnCondition] — resolves when a polled developer predicate returns
 *    true.
 */
sealed interface PumpStationWaitSpec
{
    /**
     * A stable, human-readable label for the spec, used on emitted
     * [WaitStarted] / [WaitCompleted] events and in the trace metadata so the
     * visualizer can identify which condition a wait was waiting on without
     * deserializing the (non-serializable) spec itself.
     */
    fun label(): String
}

/**
 * Wait until all in-flight async / background [PathObject] legs launched by
 * the harness have settled (completed or failed). This is the join-all-paths
 * condition: it is tracked by the harness's in-flight path counter
 * ([PumpStation.inFlightPathCount]) and resolves when the counter returns to
 * zero.
 *
 * @property batchName Optional label identifying the batch of paths being
 *   joined. Surfaced on the emitted events for observability.
 */
data class AllPathsSettled(val batchName: String? = null) : PumpStationWaitSpec
{
    override fun label(): String = "AllPathsSettled"
}

/**
 * Wait until a [PumpStationEvent] matching [filter] is observed by the
 * harness event funnel. The developer supplies a predicate; the first
 * matching event releases the wait.
 *
 * @property filter Predicate over the observed [PumpStationEvent]; returns
 *   true when this event should release the wait.
 * @property wakeName Optional label for the wake source, surfaced on the
 *   emitted events.
 */
data class OnEvent(
    val filter: (PumpStationEvent) -> Boolean,
    val wakeName: String? = null
) : PumpStationWaitSpec
{
    override fun label(): String = "OnEvent"
}

/**
 * Wait until a developer [predicate] over the [PumpStation] returns true,
 * polled on a fixed interval. Useful for waiting on external state that the
 * harness does not itself emit events for.
 *
 * @property predicate Suspend predicate evaluated against the live station.
 * @property pollIntervalMs Milliseconds between predicate evaluations
 *   (default 50 ms). Must be positive.
 */
data class OnCondition(
    val predicate: (suspend (PumpStation) -> Boolean)? = null,
    val pollIntervalMs: Int = 50
) : PumpStationWaitSpec
{
    override fun label(): String = "OnCondition"
}

/**
 * The outcome of a [PumpStationWait] resolution. Carried back to the caller
 * by [PumpStationWaitHandle.awaitResolution] and by
 * [PumpStation.waitUntil].
 *
 * @property name The wait name this result was requested under.
 * @property satisfied True when the wait condition actually held before the
 *   result was returned; false when it timed out or was cancelled.
 * @property reason Why the wait resolved.
 * @property elapsedMs Wall-clock milliseconds from request to resolution.
 * @property specLabel The [PumpStationWaitSpec.label] of the spec that was
 *   waited on.
 */
data class PumpStationWaitResult(
    val name: String,
    val satisfied: Boolean,
    val reason: PumpStationWaitReason,
    val elapsedMs: Long,
    val specLabel: String
)
{
    companion object
    {
        /** A satisfied result for [name]. */
        fun resolved(name: String, elapsedMs: Long, specLabel: String) =
            PumpStationWaitResult(name, true, PumpStationWaitReason.Resolved, elapsedMs, specLabel)

        /** A timed-out result for [name]. */
        fun timedOut(name: String, elapsedMs: Long, specLabel: String) =
            PumpStationWaitResult(name, false, PumpStationWaitReason.Timeout, elapsedMs, specLabel)

        /** A cancelled result for [name]. */
        fun cancelled(name: String, elapsedMs: Long, specLabel: String, reason: PumpStationWaitReason) =
            PumpStationWaitResult(name, false, reason, elapsedMs, specLabel)
    }
}

/**
 * A live, named wait request owned by a [PumpStation]. Returned by
 * [PumpStation.requestWait]; holds the per-wait state (wake channel,
 * watchers) and exposes [awaitResolution] to suspend the caller until the
 * wait settles, plus [release] / [cancel] to settle it from outside.
 *
 * The handle is bound to a single [PumpStation] instance. [release] is
 * idempotent; a second release after the wait has settled is a no-op.
 */
class PumpStationWaitHandle internal constructor(
    private val name: String,
    private val spec: PumpStationWaitSpec,
    private val startedMs: Long,
    internal val service: PumpStationWaitService,
    private val settleCounter: AtomicInteger
)
{
    /** The wait name this handle was requested under. */
    val waitName: String get() = name

    /** The spec this handle is waiting on. */
    val waitSpec: PumpStationWaitSpec get() = spec

    /** Wall-clock millisecond timestamp the wait was requested at. */
    val requestedAtMs: Long get() = startedMs

    /**
     * Suspend the current coroutine until this wait settles. Returns
     * [PumpStationWaitResult] describing whether the condition was met or the
     * wait timed out / was cancelled. The caller must be in a coroutine
     * context.
     *
     * A timeout, when [PumpStationWaitService] was requested with a timeout,
     * bounds this suspension; without a timeout the suspension is unbounded
     * until [release] / [cancel] or a harness force-halt.
     */
    suspend fun awaitResolution(timeoutMs: Long?): PumpStationWaitResult
    {
        val result = service.await(this, timeoutMs)
        service.complete(name, settleCounter)
        return result
    }

    /**
     * Manually release this wait so any suspended [awaitResolution] returns
     * with [PumpStationWaitReason.Resolved]. Idempotent.
     */
    fun release()
    {
        service.release(name, reason = PumpStationWaitReason.Resolved)
    }

    /**
     * Cancel this wait so any suspended [awaitResolution] returns with
     * [PumpStationWaitReason.Cancelled]. Idempotent.
     */
    fun cancel()
    {
        service.release(name, reason = PumpStationWaitReason.Cancelled)
    }
}

/**
 * Configuration captured from the `waitPolicy { ... }` DSL block and applied
 * to a [PumpStation] at construction. Not serializable (it embeds a
 * non-serializable [PumpStationWaitSpec]).
 *
 * @property joinAllPathsOnMultiDispatch When true, a MultiPath dispatch that
 *   fans out a batch of async paths joins on [AllPathsSettled] before
 *   returning to the loop, making the batch a first-class "wait for all
 *   paths to return" mode. Defaults to false to preserve the existing
 *   fire-and-collect semantics.
 * @property defaultTimeoutMs Default timeout applied to joins and to
 *   [PumpStation.waitUntil] calls that do not pass an explicit timeout.
 * @property defaultPollIntervalMs Default poll interval for
 *   [OnCondition] specs that do not override it.
 * @property initialWaits Wait specs seeded at construction; each is
 *   immediately requested and, when it is an [OnCondition], begins polling.
 */
class PumpStationWaitConfiguration
{
    var joinAllPathsOnMultiDispatch: Boolean = false
    var defaultTimeoutMs: Long? = null
    var defaultPollIntervalMs: Int = 50
    var initialWaits: List<PumpStationWaitSpec> = emptyList()

    constructor()

    /**
     * Convenience constructor that adds a default timeout without changing the
     * original data-class ABI.
     */
    constructor(
        joinAllPathsOnMultiDispatch: Boolean,
        defaultTimeoutMs: Long?,
        defaultPollIntervalMs: Int,
        initialWaits: List<PumpStationWaitSpec>
    ) : this()
    {
        this.joinAllPathsOnMultiDispatch = joinAllPathsOnMultiDispatch
        this.defaultTimeoutMs = defaultTimeoutMs
        this.defaultPollIntervalMs = defaultPollIntervalMs
        this.initialWaits = initialWaits
    }
}
