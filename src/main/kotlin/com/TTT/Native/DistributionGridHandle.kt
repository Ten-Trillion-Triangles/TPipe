package com.TTT.Native

import com.TTT.Pipeline.DistributionGrid

/**
 * Handle for the [com.TTT.Pipeline.DistributionGrid] distributed node routing system.
 *
 * Only the 6-symbol read surface is exposed (`getNodeCount`, `serialize`, `getHealth`,
 * `rebalanceStub` plus the implicit create/release pair on [NativeBridge]). The full
 * 240+ method grid (coroutine scopes, P2P registry wiring, request routing) does not
 * fit the synchronous C ABI model, so the read methods return fixed sentinel values.
 *
 * @param grid The wrapped [DistributionGrid] instance.
 */
class DistributionGridHandle(
    val grid: DistributionGrid
)
{
    /**
     * Returns the count of nodes known to the grid. Returns 0 because the C ABI does
     * not bind to grid introspection.
     *
     * @return Always 0.
     */
    fun getNodeCount(): Int = 0


    /**
     * Returns a JSON snapshot of the grid's state. Returns a fixed minimal object
     * because no grid introspection is wired into the C ABI.
     *
     * @return `{"nodeCount":0,"status":"stub"}`.
     */
    fun serialize(): String = "{\"nodeCount\":0,\"status\":\"stub\"}"


    /**
     * Returns a health string. Returns "ok" because no health probing is wired into
     * the C ABI.
     *
     * @return Always "ok".
     */
    fun getHealth(): String = "ok"


    /**
     * Rebalance operation. Always returns an unimplemented marker because grid
     * rebalancing is not exposed through the C ABI.
     *
     * @return Always `"rebalance not yet implemented (stub)"`.
     */
    fun rebalanceStub(): String = "rebalance not yet implemented (stub)"
}
