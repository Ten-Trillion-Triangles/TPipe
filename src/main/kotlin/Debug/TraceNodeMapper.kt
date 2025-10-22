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
        return trace.groupBy { it.pipeName }
            .map { (pipeName, events) ->
                TraceNode(
                    nodeId = "node-${kotlin.math.abs(pipeName.hashCode())}",
                    pipeName = pipeName,
                    eventIds = events.map { it.id },
                    status = determineNodeStatus(events)
                )
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
