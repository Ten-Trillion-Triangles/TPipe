package com.TTT.Debug

/**
 * Converts a flat event stream into renderable trace nodes.
 */
data class TraceNode(
    val nodeId: String,
    val pipeName: String,
    val eventIds: List<String>,
    val status: NodeStatus
)

/**
 * High-level node classification used by the visualizer.
 */
enum class NodeStatus { SUCCESS, FAILURE, INFO, WARNING }

/**
 * Groups trace events by orchestration identity for graph rendering.
 */
object TraceNodeMapper 
{
    /**
     * Group a flat trace stream into visual nodes for the HTML and Mermaid renderers.
     *
     * @param trace The trace events to group.
     * @return Visual trace nodes keyed by their resolved orchestration identity.
     */
    fun mapEventsToNodes(trace: List<TraceEvent>): List<TraceNode> 
    {
        return trace.groupBy { resolveNodeKey(it) }
            .map { (nodeKey, events) ->
                TraceNode(
                    nodeId = "node-${kotlin.math.abs(nodeKey.hashCode())}",
                    pipeName = nodeKey,
                    eventIds = events.map { it.id },
                    status = determineNodeStatus(events)
                )
            }
    }

    /**
     * Resolve the stable grouping key for one trace event.
     *
     * @param event The trace event whose node key should be derived.
     * @return The node grouping key used by the visualizer.
     */
    fun resolveNodeKey(event: TraceEvent): String
    {
        // Junction and other harness events intentionally get their own node key suffix so their trace nodes
        // do not collapse into the generic pipe node while still staying grouped by the owning harness.
        return when {
            // PumpStation events are turn-keyed so the visualizer can group them into turn cards. Goal
            // validation events get a nested sub-loop key so the visualizer can render them as a sub-loop
            // within the turn card. Reserve path reveals cluster by path name so the same path revealed
            // across multiple turns groups cleanly.
            event.eventType.name.startsWith("PUMP_STATION_") -> resolvePumpStationNodeKey(event)
            event.eventType.name.startsWith("SPLITTER_") -> "${event.pipeName}-${event.eventType.name}"
            event.eventType.name.startsWith("MANIFOLD_") -> "${event.pipeName}-${event.eventType.name}"
            event.eventType.name.startsWith("JUNCTION_") -> "${event.pipeName}-${event.eventType.name}"
            event.eventType.name.startsWith("DISTRIBUTION_GRID_") ->
            {
                val taskId = event.metadata["taskId"]?.toString()?.takeIf { it.isNotBlank() }
                val peerKey = event.metadata["peerKey"]?.toString()?.takeIf { it.isNotBlank() }
                val targetNodeId = event.metadata["targetNodeId"]?.toString()?.takeIf { it.isNotBlank() }
                when(event.eventType)
                {
                    TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF,
                    TraceEventType.DISTRIBUTION_GRID_PEER_RESPONSE,
                    TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE ->
                        "DistributionGrid-Remote-${peerKey ?: targetNodeId ?: taskId ?: "unknown"}-${event.eventType.name}"

                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE,
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY ->
                        "DistributionGrid-Registry-${event.metadata["registryId"] ?: "unknown"}-${event.eventType.name}"

                    TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL ->
                        "DistributionGrid-Catalog-${event.metadata["sourceId"] ?: "unknown"}-${event.eventType.name}"

                    TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING,
                    TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW ->
                        "DistributionGrid-Listing-${event.metadata["listingId"] ?: event.metadata["renewalId"] ?: "unknown"}-${event.eventType.name}"

                    else -> "${event.pipeName}-${event.eventType.name}"
                }
            }
            event.eventType.name.startsWith("MANAGER_") -> "${event.pipeName}-${event.eventType.name}"
            event.eventType.name.startsWith("AGENT_") -> "${event.pipeName}-${event.eventType.name}-${event.metadata["agentName"] ?: "unknown"}"
            else -> event.pipeName
        }
    }
    
    private fun determineNodeStatus(events: List<TraceEvent>): NodeStatus
    {
        return when {
            events.any { it.eventType.name.contains("FAILURE") } -> NodeStatus.FAILURE
            events.any { it.eventType.name.contains("SUCCESS") } -> NodeStatus.SUCCESS
            else -> NodeStatus.INFO
        }
    }

    /**
     * Resolve the grouping key for a PumpStation event. Turn index is sourced from the event metadata
     * (populated by the PumpStation → TraceEvent conversion helper in PumpStationHelpers.kt). Goal
     * validation events get a nested sub-loop key, and reserve path reveals cluster by path name.
     */
    private fun resolvePumpStationNodeKey(event: TraceEvent): String
    {
        val turnIndex = event.metadata["turnIndex"]?.toString()?.toIntOrNull() ?: -1
        val isGoalEvent = event.eventType.name.startsWith("PUMP_STATION_GOAL_VALIDATION_")
        val isReserveReveal = event.eventType == TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED

        return when
        {
            isReserveReveal ->
            {
                val pathName = event.metadata["pathName"]?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
                "RESERVE_REVEAL-$pathName-${event.eventType.name}"
            }
            isGoalEvent -> "TURN_$turnIndex-GOAL_SUBLOOP-${event.eventType.name}"
            else -> "TURN_$turnIndex-${event.eventType.name}"
        }
    }
}
