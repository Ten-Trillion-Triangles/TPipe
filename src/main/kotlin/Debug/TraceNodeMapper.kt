package com.TTT.Debug

data class TraceNode(
    val nodeId: String,
    val pipeName: String,
    val eventIds: List<String>,
    val status: NodeStatus
)

enum class NodeStatus { SUCCESS, FAILURE, INFO, WARNING }

object TraceNodeMapper 
{
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

    fun resolveNodeKey(event: TraceEvent): String 
    {
        return when {
            event.eventType.name.startsWith("SPLITTER_") -> "${event.pipeName}-${event.eventType.name}"
            event.eventType.name.startsWith("MANIFOLD_") -> "${event.pipeName}-${event.eventType.name}"
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
}
