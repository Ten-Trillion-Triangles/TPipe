package com.TTT.Debug

class TraceVisualizer {
    
    fun generateFlowChart(trace: List<TraceEvent>): String {
        val flowChart = StringBuilder()
        
        // Detect if this is a Manifold trace
        val isManifoldTrace = trace.any { it.eventType.name.startsWith("MANIFOLD_") }
        
        if (isManifoldTrace) {
            flowChart.append("=== Manifold Orchestration Flow ===\n")
        } else {
            flowChart.append("=== Pipeline Flow Chart ===\n")
        }
        
        trace.forEach { event ->
            val symbol = when (event.eventType) {
                // Existing pipe events
                TraceEventType.PIPE_START -> "▶️"
                TraceEventType.PIPE_SUCCESS -> "✅"
                TraceEventType.PIPE_FAILURE -> "❌"
                TraceEventType.API_CALL_START -> "🔄"
                TraceEventType.API_CALL_SUCCESS -> "✅"
                TraceEventType.API_CALL_FAILURE -> "❌"
                TraceEventType.VALIDATION_SUCCESS -> "✔️"
                TraceEventType.VALIDATION_FAILURE -> "❌"
                TraceEventType.TRANSFORMATION_SUCCESS -> "🔄"
                TraceEventType.PIPELINE_TERMINATION -> "🛑"
                
                // Manifold orchestration events
                TraceEventType.MANIFOLD_START -> "🎯"
                TraceEventType.MANIFOLD_END -> "🏁"
                TraceEventType.MANIFOLD_SUCCESS -> "✅"
                TraceEventType.MANIFOLD_FAILURE -> "❌"
                TraceEventType.MANIFOLD_LOOP_ITERATION -> "🔄"
                
                // Manager decision events
                TraceEventType.MANAGER_DECISION -> "🧠"
                TraceEventType.MANAGER_TASK_ANALYSIS -> "🔍"
                TraceEventType.MANAGER_AGENT_SELECTION -> "👆"
                
                // Agent communication events
                TraceEventType.AGENT_DISPATCH -> "📤"
                TraceEventType.AGENT_RESPONSE -> "📥"
                TraceEventType.AGENT_REQUEST_VALIDATION -> "✔️"
                TraceEventType.AGENT_RESPONSE_PROCESSING -> "⚙️"
                
                // P2P communication events
                TraceEventType.P2P_REQUEST_START -> "🔗"
                TraceEventType.P2P_REQUEST_SUCCESS -> "✅"
                TraceEventType.P2P_REQUEST_FAILURE -> "❌"
                TraceEventType.P2P_COMMUNICATION_FAILURE -> "💥"
                
                // Task management events
                TraceEventType.TASK_PROGRESS_UPDATE -> "📊"
                TraceEventType.CONVERSE_HISTORY_UPDATE -> "💬"
                
                else -> "ℹ️"
            }
            
            flowChart.append("$symbol ${event.pipeName} -> ${event.eventType}\n")
        }
        
        return flowChart.toString()
    }
    
    fun generateTimeline(trace: List<TraceEvent>): String {
        val timeline = StringBuilder()
        timeline.append("=== Execution Timeline ===\n")
        
        val startTime = trace.firstOrNull()?.timestamp ?: 0L
        
        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            timeline.append("[${elapsed}ms] ${event.pipeName}: ${event.eventType} (${event.phase})\n")
            
            if (event.error != null) {
                timeline.append("    ERROR: ${event.error.message}\n")
            }
        }
        
        return timeline.toString()
    }
    
    fun generateConsoleOutput(trace: List<TraceEvent>): String {
        val output = StringBuilder()
        output.append("=== TPipe Execution Trace ===\n")
        
        trace.forEach { event ->
            val status = when (event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "[SUCCESS]"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "[FAILURE]"
                else -> "[INFO]"
            }
            
            output.append("$status ${event.pipeName} - ${event.eventType} (${event.phase})\n")
            
            if (event.error != null) {
                output.append("  Error: ${event.error.message}\n")
            }
            
            if (event.metadata.isNotEmpty()) {
                output.append("  Metadata: ${event.metadata}\n")
            }
        }
        
        return output.toString()
    }
    
    fun generateHtmlReport(trace: List<TraceEvent>): String {
        val isManifoldTrace = trace.any { it.eventType.name.startsWith("MANIFOLD_") }
        
        return if (isManifoldTrace) {
            generateManifoldHtmlReport(trace)
        } else {
            generateStandardHtmlReport(trace)
        }
    }
    
    private fun generateMermaidFlowGraph(trace: List<TraceEvent>, nodes: List<TraceNode>): String {
        val graph = StringBuilder()
        graph.append("graph TD\n")
        
        val nodeMap = nodes.associate { it.pipeName to it.nodeId }
        
        // Create nodes for each pipe
        nodes.forEach { node ->
            val label = formatNodeLabel(node.pipeName).replace("\n", "<br/>")
            graph.append("    ${node.nodeId}[\"$label\"]\n")
            graph.append("    click ${node.nodeId} scrollToEvent\n")  // ADD: Click handler
        }
        
        // Add connections and styling based on events
        var prevNode: String? = null
        trace.forEach { event ->
            val nodeKey = TraceNodeMapper.resolveNodeKey(event)
            val currentNode = nodeMap[nodeKey]
            
            if (prevNode != null && currentNode != null && prevNode != currentNode) {
                graph.append("    $prevNode --> $currentNode\n")
            }
            
            // Add styling based on event type
            when (event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS -> {
                    currentNode?.let { graph.append("    $it:::success\n") }
                }
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE -> {
                    currentNode?.let { graph.append("    $it:::failure\n") }
                }
                else -> {
                    currentNode?.let { graph.append("    $it:::info\n") }
                }
            }
            
            if (currentNode != null) {
                prevNode = currentNode
            }
        }
        
        // Add CSS classes
        graph.append("\n    classDef success fill:#d4edda,stroke:#28a745,stroke-width:2px\n")
        graph.append("    classDef failure fill:#f8d7da,stroke:#dc3545,stroke-width:2px\n")
        graph.append("    classDef info fill:#d1ecf1,stroke:#007bff,stroke-width:2px\n")
        
        return graph.toString()
    }
    
    private fun generateDetailsTable(trace: List<TraceEvent>): String {
        val table = StringBuilder()
        table.append("""
            <table id="trace-details-table">
                <tr>
                    <th>⏱️ Time</th>
                    <th>🔧 Pipe</th>
                    <th>📝 Event</th>
                    <th>🔄 Phase</th>
                    <th>✅ Status</th>
                    <th>📊 Metadata</th>
                </tr>
        """.trimIndent())
        
        val startTime = trace.firstOrNull()?.timestamp ?: 0L
        
        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            val statusClass = when (event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "success"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "failure"
                else -> "info"
            }
            
            val status = when (event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "✅ SUCCESS"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "❌ FAILURE"
                else -> "ℹ️ INFO"
            }
            
            val metadata = if (event.error != null) {
                "<strong>Error:</strong> ${event.error.message}"
            } else if (event.metadata.isNotEmpty()) {
                event.metadata.entries.joinToString("<br>") { "<strong>${it.key}:</strong> ${it.value}" }
            } else {
                "-"
            }
            
            val nodeKey = TraceNodeMapper.resolveNodeKey(event)
            table.append("""
                <tr id="${event.id}" class="trace-row" data-pipe="$nodeKey">
                    <td>+${elapsed}ms</td>
                    <td>${event.pipeName}</td>
                    <td>${event.eventType}</td>
                    <td>${event.phase}</td>
                    <td class="$statusClass">$status</td>
                    <td class="metadata">$metadata</td>
                </tr>
            """.trimIndent())
        }
        
        table.append("</table>")
        return table.toString()
    }
    
    /**
     * Generates Manifold-specific HTML report with orchestration visualization.
     */
    private fun generateManifoldHtmlReport(trace: List<TraceEvent>): String {
        val nodes = buildManifoldNodes(trace)
        val mermaidGraph = generateManifoldMermaidGraph(nodes, trace)
        val orchestrationTable = generateOrchestrationTable(trace)
        val agentInteractionTable = generateAgentInteractionTable(trace)
        val javascript = TraceInteractivity.generateJavaScript(nodes)
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>TPipe Manifold Execution Flow</title>
                <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 20px; background: #f5f5f5; }
                    .container { max-width: 1200px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    h1 { color: #333; text-align: center; margin-bottom: 30px; }
                    .manifold-section { margin: 20px 0; padding: 15px; border-radius: 8px; }
                    .orchestration { background: #f8f9fa; border-left: 4px solid #007bff; }
                    .agent-interaction { background: #e8f5e8; border-left: 4px solid #28a745; }
                    .success { color: #28a745; font-weight: bold; }
                    .failure { color: #dc3545; font-weight: bold; }
                    table { border-collapse: collapse; width: 100%; margin-top: 20px; }
                    th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
                    th { background-color: #f8f9fa; font-weight: 600; }
                    tr:nth-child(even) { background-color: #f8f9fa; }
                    .mermaid { text-align: center; background: white; padding: 20px; border-radius: 8px; }
                    .trace-row { transition: background-color 0.3s ease; cursor: pointer; }
                    .trace-row.highlighted { background-color: #fff3cd !important; border-left: 4px solid #ffc107; }
                    .flash-highlight { animation: flashEffect 2s ease-in-out; }
                    @keyframes flashEffect { 0%, 100% { background-color: inherit; } 50% { background-color: #ffeb3b; } }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>🎯 TPipe Manifold Execution Analysis</h1>
                    
                    <div class="manifold-section orchestration">
                        <h2>📊 Orchestration Flow</h2>
                        <div class="mermaid">$mermaidGraph</div>
                    </div>
                    
                    <div class="manifold-section orchestration">
                        <h2>🎯 Orchestration Timeline</h2>
                        $orchestrationTable
                    </div>
                    
                    <div class="manifold-section agent-interaction">
                        <h2>🤖 Agent Interactions</h2>
                        $agentInteractionTable
                    </div>
                </div>
                
                <script>
                    mermaid.initialize({ 
                        startOnLoad: true,
                        theme: 'default',
                        flowchart: { useMaxWidth: true, htmlLabels: true }
                    });
                </script>
                $javascript
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Generates standard HTML report for non-Manifold traces.
     */
    private fun generateStandardHtmlReport(trace: List<TraceEvent>): String 
    {
        val nodes = TraceNodeMapper.mapEventsToNodes(trace)
        val mermaidGraph = generateMermaidFlowGraph(trace, nodes)
        val detailsTable = generateDetailsTable(trace)
        val javascript = TraceInteractivity.generateJavaScript(nodes)
        val enhancedCSS = generateEnhancedCSS()
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>TPipe Pipeline Flow Visualization</title>
                <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
                <style>$enhancedCSS</style>
            </head>
            <body>
                <div class="container">
                    <h1>🔍 TPipe Pipeline Execution Flow</h1>
                    
                    <div class="flow-section">
                        <h2>📊 Interactive Flow Graph</h2>
                        <p class="instruction">💡 Click on any node to jump to its events in the table below</p>
                        <div class="mermaid">$mermaidGraph</div>
                    </div>
                    
                    <div class="details-section">
                        <h2>📋 Execution Details</h2>
                        $detailsTable
                    </div>
                </div>
                
                $javascript
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Generates Mermaid flow graph for Manifold orchestration.
     */
    private fun generateManifoldMermaidGraph(nodes: List<TraceNode>, trace: List<TraceEvent>): String {
        val graph = StringBuilder()
        graph.append("graph TD\n")

        val nodeMap = nodes.associateBy { it.pipeName }

        nodes.forEach { node ->
            graph.append("    ${node.nodeId}[\"${escapeHtml(node.pipeName)}\"]\n")
            graph.append("    click ${node.nodeId} scrollToEvent\n")
            val styleClass = when (node.status) {
                NodeStatus.SUCCESS -> "success"
                NodeStatus.FAILURE -> "failure"
                NodeStatus.INFO -> "info"
                NodeStatus.WARNING -> "info"
            }
            graph.append("    ${node.nodeId}:::${styleClass}\n")
        }

        var previousNodeId: String? = null
        trace.forEach { event ->
            val label = mapManifoldNodeName(event)
            val node = nodeMap[label] ?: return@forEach
            if (previousNodeId != null && previousNodeId != node.nodeId) {
                graph.append("    $previousNodeId --> ${node.nodeId}\n")
            }
            previousNodeId = node.nodeId
        }

        graph.append("\n    classDef success fill:#d4edda,stroke:#28a745,stroke-width:2px\n")
        graph.append("    classDef failure fill:#f8d7da,stroke:#dc3545,stroke-width:2px\n")
        graph.append("    classDef info fill:#d1ecf1,stroke:#007bff,stroke-width:2px\n")

        return graph.toString()
    }
    
    /**
     * Generates orchestration timeline table.
     */
    private fun generateOrchestrationTable(trace: List<TraceEvent>): String {
        val table = StringBuilder()
        table.append("<table><tr><th>⏱️ Time</th><th>🧵 Node</th><th>📝 Event</th><th>📊 Metadata</th><th>🗒️ Content</th><th>⚠️ Error</th></tr>")
        
        val startTime = trace.firstOrNull()?.timestamp ?: 0L
        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            val pipeName = mapManifoldNodeName(event)
            val metadata = formatMetadata(event.metadata)
            val contentSummary = formatContentSummary(event)
            val errorSummary = formatError(event.error)
            table.append(
                "<tr id=\"${event.id}\" class=\"trace-row\" data-pipe=\"${escapeHtml(pipeName)}\">" +
                        "<td>+${elapsed}ms</td>" +
                        "<td>${escapeHtml(pipeName)}</td>" +
                        "<td>${escapeHtml(event.eventType.name)}</td>" +
                        "<td>$metadata</td>" +
                        "<td>$contentSummary</td>" +
                        "<td>$errorSummary</td>" +
                        "</tr>"
            )
        }
        table.append("</table>")
        return table.toString()
    }
    
    /**
     * Generates agent interaction summary table.
     */
    private fun generateAgentInteractionTable(trace: List<TraceEvent>): String {
        val agentStats = mutableMapOf<String, MutableMap<String, Int>>()
        
        trace.filter { it.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) }
            .forEach { event ->
                val agentName = event.metadata["agentName"] as? String ?: "Unknown"
                val stats = agentStats.getOrPut(agentName) { mutableMapOf() }
                when (event.eventType) {
                    TraceEventType.AGENT_DISPATCH -> stats["dispatches"] = stats.getOrDefault("dispatches", 0) + 1
                    TraceEventType.AGENT_RESPONSE -> stats["responses"] = stats.getOrDefault("responses", 0) + 1
                    else -> {} // Ignore other event types
                }
            }
        
        val table = StringBuilder()
        table.append("<table><tr><th>🤖 Agent</th><th>📤 Dispatches</th><th>📥 Responses</th><th>✅ Success Rate</th></tr>")
        
        agentStats.forEach { (agentName, stats) ->
            val dispatches = stats["dispatches"] ?: 0
            val responses = stats["responses"] ?: 0
            val successRate = if (dispatches > 0) (responses * 100 / dispatches) else 0
            val pipeName = "$AGENT_NODE_PREFIX$agentName"
            table.append("<tr class=\"trace-row\" data-pipe=\"$pipeName\"><td>$agentName</td><td>$dispatches</td><td>$responses</td><td>$successRate%</td></tr>")
        }
        table.append("</table>")
        return table.toString()
    }

    private fun buildManifoldNodes(trace: List<TraceEvent>): List<TraceNode> {
        val nodeGroups = linkedMapOf<String, MutableList<TraceEvent>>()

        trace.forEach { event ->
            val label = mapManifoldNodeName(event)
            val events = nodeGroups.getOrPut(label) { mutableListOf() }
            events.add(event)
        }

        return nodeGroups.entries.mapIndexed { index, (label, events) ->
            createManifoldNode("node-${sanitizeNodeId(label)}-$index", label, events)
        }
    }

    private fun createManifoldNode(nodeId: String, label: String, events: List<TraceEvent>): TraceNode {
        val status = when {
            events.any { it.eventType.name.contains("FAILURE") } -> NodeStatus.FAILURE
            events.any { it.eventType.name.contains("SUCCESS") } -> NodeStatus.SUCCESS
            events.isNotEmpty() -> NodeStatus.INFO
            else -> NodeStatus.INFO
        }
        return TraceNode(nodeId, label, events.map { it.id }, status)
    }

    private fun mapManifoldNodeName(event: TraceEvent): String {
        return when {
            event.eventType.name.startsWith("MANIFOLD_") -> MANIFOLD_NODE_NAME
            event.eventType.name.startsWith("MANAGER_") -> MANAGER_NODE_NAME
            event.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) ->
                "$AGENT_NODE_PREFIX${event.metadata["agentName"] ?: "Unknown"}"
            else -> if (event.pipeName.isNotBlank()) event.pipeName else MANIFOLD_NODE_NAME
        }
    }

    private fun sanitizeNodeId(name: String): String {
        return name.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')
    }

    private fun formatMetadata(metadata: Map<String, Any>): String {
        if (metadata.isEmpty()) return "—"
        return metadata.entries.joinToString("<br/>") { (key, value) ->
            "${escapeHtml(key)}: ${escapeHtml(value.toString())}"
        }
    }

    private fun formatContentSummary(event: TraceEvent): String {
        val parts = mutableListOf<String>()
        event.content?.text?.takeIf { it.isNotBlank() }?.let { text ->
            val preview = if (text.length > 160) "${text.take(160)}…" else text
            parts.add("text=\"${escapeHtml(preview)}\"")
        }
        event.contextSnapshot?.let { snapshot ->
            parts.add("context=${escapeHtml(snapshot.toString())}")
        }
        return if (parts.isEmpty()) "—" else parts.joinToString("<br/>")
    }

    private fun formatError(error: Throwable?): String {
        return error?.let { escapeHtml(it.message ?: it.toString()) } ?: "—"
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Generates basic details table for standard traces.
     */
    private fun generateBasicDetailsTable(trace: List<TraceEvent>): String {
        val table = StringBuilder()
        table.append("<table><tr><th>Time</th><th>Pipe</th><th>Event</th><th>Status</th></tr>")
        
        val startTime = trace.firstOrNull()?.timestamp ?: 0L
        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            val status = when {
                event.eventType.name.contains("SUCCESS") -> "✅ SUCCESS"
                event.eventType.name.contains("FAILURE") -> "❌ FAILURE"
                else -> "ℹ️ INFO"
            }
            val nodeKey = TraceNodeMapper.resolveNodeKey(event)
            table.append("<tr class=\"trace-row\" data-pipe=\"$nodeKey\"><td>+${elapsed}ms</td><td>${event.pipeName}</td><td>${event.eventType}</td><td>$status</td></tr>")
        }
        table.append("</table>")
        return table.toString()
    }

    private fun formatNodeLabel(nodeKey: String): String {
        fun splitLabel(marker: String, key: String): String {
            val index = key.indexOf(marker)
            if (index == -1) return key
            val prefix = key.substring(0, index)
            val suffix = key.substring(index + marker.length)
            return "$prefix\n${suffix.replace('_', ' ')}"
        }

        return when {
            nodeKey.contains("-SPLITTER_") -> splitLabel("-SPLITTER_", nodeKey)
            nodeKey.contains("-MANIFOLD_") -> splitLabel("-MANIFOLD_", nodeKey)
            nodeKey.contains("-MANAGER_") -> splitLabel("-MANAGER_", nodeKey)
            nodeKey.contains("-AGENT_") -> {
                val index = nodeKey.indexOf("-AGENT_")
                val suffix = nodeKey.substring(index + "-AGENT_".length)
                val parts = suffix.split('-')
                val eventLabel = parts.firstOrNull()?.replace('_', ' ') ?: "Agent"
                val agentName = parts.drop(1).joinToString("-").ifBlank { "Agent" }
                "$agentName\n$eventLabel"
            }
            else -> nodeKey
        }
    }
    
    private fun addNodeConnections(graph: StringBuilder, nodes: List<TraceNode>, trace: List<TraceEvent>) 
    {
        val nodeMap = nodes.associate { it.pipeName to it.nodeId }
        var prevNode: String? = null
        trace.forEach { event ->
            val nodeKey = TraceNodeMapper.resolveNodeKey(event)
            val currentNode = nodeMap[nodeKey]
            if (prevNode != null && currentNode != null && prevNode != currentNode) {
                graph.append("    $prevNode --> $currentNode\n")
            }
            if (currentNode != null) {
                prevNode = currentNode
            }
        }
    }
    
    private fun addNodeStyling(graph: StringBuilder, nodes: List<TraceNode>) 
    {
        nodes.forEach { node ->
            val cssClass = when (node.status) {
                NodeStatus.SUCCESS -> "success"
                NodeStatus.FAILURE -> "failure"
                NodeStatus.WARNING -> "warning"
                NodeStatus.INFO -> "info"
            }
            graph.append("    ${node.nodeId}:::$cssClass\n")
        }
        
        graph.append("\n    classDef success fill:#d4edda,stroke:#28a745,stroke-width:2px\n")
        graph.append("    classDef failure fill:#f8d7da,stroke:#dc3545,stroke-width:2px\n")
        graph.append("    classDef warning fill:#fff3cd,stroke:#ffc107,stroke-width:2px\n")
        graph.append("    classDef info fill:#d1ecf1,stroke:#007bff,stroke-width:2px\n")
    }
    
    private fun generateEnhancedCSS(): String 
    {
        return """
            body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 20px; background: #f5f5f5; }
            .container { max-width: 1200px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
            h1 { color: #333; text-align: center; margin-bottom: 30px; }
            .flow-section { margin-bottom: 40px; }
            .details-section { margin-top: 40px; }
            .instruction { text-align: center; color: #666; font-style: italic; margin-bottom: 20px; }
            .success { color: #28a745; font-weight: bold; }
            .failure { color: #dc3545; font-weight: bold; }
            .info { color: #007bff; }
            table { border-collapse: collapse; width: 100%; margin-top: 20px; }
            th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
            th { background-color: #f8f9fa; font-weight: 600; }
            tr:nth-child(even) { background-color: #f8f9fa; }
            .metadata { font-size: 0.9em; color: #666; max-width: 300px; word-wrap: break-word; }
            .mermaid { text-align: center; background: white; padding: 20px; border-radius: 8px; }
            
            .trace-row.highlighted {
                background-color: #fff3cd !important;
                border-left: 4px solid #ffc107;
            }
            
            .flash-highlight {
                animation: flashEffect 2s ease-in-out;
            }
            
            @keyframes flashEffect {
                0%, 100% { background-color: inherit; }
                50% { background-color: #ffeb3b; }
            }
            
            .trace-row {
                transition: background-color 0.3s ease;
                cursor: pointer;
            }
            
            .trace-row:hover {
                background-color: #f8f9fa;
            }
            
            #trace-details-table {
                scroll-margin-top: 20px;
            }
            
            .node rect {
                cursor: pointer;
                transition: stroke-width 0.2s ease;
            }
            
            .node:hover rect {
                stroke-width: 3px !important;
            }
        """.trimIndent()
    }

    companion object {
        private const val MANIFOLD_NODE_NAME = "Manifold"
        private const val MANAGER_NODE_NAME = "Manager"
        private const val AGENT_NODE_PREFIX = "Agent: "
    }
}
