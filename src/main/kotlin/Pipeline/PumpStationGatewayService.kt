package com.TTT.Pipeline

import java.util.Collections
import java.util.LinkedHashSet

/**
 * Thread-safe registry of pending gateway evaluation requests.
 *
 * A request names a gateway that should evaluate on the next gateway phase. Requests are
 * drained at most once per gateway per turn: asking twice for the same gateway in one turn
 * produces one evaluation, not two.
 *
 * Thread-safe: [request] may be called from any thread or coroutine concurrently with a
 * running harness, including from a path's execution function. The pending set is
 * insertion-ordered, so a drain returns the names in request order, and the read and the
 * clear happen under one lock, so a request that arrives mid-drain is never consumed
 * without being returned.
 *
 * Scope: the registry is owned by the station, outside [PumpStationTaskState] and
 * [PumpStationSnapshot], so it survives [PumpStation.P2PInit] and is not part of a resume.
 * A nested station keeps its own registry. This mirrors [PumpStationForceService].
 */
class PumpStationGatewayService
{
//=========================================Properties===================================================================

    private val pending = Collections.synchronizedSet(LinkedHashSet<String>())

//=========================================Public API=================================================================

    /**
     * Request an evaluation of the named gateway.
     *
     * The request is consumed by the next gateway phase. Requesting the same gateway twice
     * before that phase still produces a single evaluation.
     *
     * @param name The gateway to evaluate.
     */
    fun request(name: String)
    {
        if(name.isBlank()) return
        pending.add(name)
    }

    /**
     * Whether an evaluation of the named gateway is pending.
     *
     * @param name The gateway to inspect.
     * @return True when the gateway is queued for evaluation.
     */
    fun isRequested(name: String): Boolean = pending.contains(name)

    /**
     * Drain every pending request, returning the names in request order.
     *
     * The registry is emptied by the drain, so a request that is not acted on in this
     * phase is not replayed in the next one.
     *
     * @return The requested gateway names.
     */
    fun drain(): List<String>
    {
        // The read and the clear happen under one lock, so a request that arrives
        // while this drain is running is never consumed without being returned.
        synchronized(pending)
        {
            if(pending.isEmpty()) return emptyList()
            val drained = pending.toList()
            pending.clear()
            return drained
        }
    }

    /** Discard pending requests without evaluating them. */
    fun clear()
    {
        pending.clear()
    }
}
