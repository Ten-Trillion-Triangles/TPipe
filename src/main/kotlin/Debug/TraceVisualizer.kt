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
            } else if (event.metadata.isNotEmpty() || event.content?.text?.isNotBlank() == true) {
                // Separate reasoning content, inputText, and outputText from other metadata for better display
                val reasoningKeys = listOf("modelReasoning", "reasoningPipeContent", "reasoningContent")
                val reasoningKey = event.metadata.keys.find { it in reasoningKeys }
                val inputKey = event.metadata.keys.find { it == "inputText" }
                val outputKey = event.metadata.keys.find { it == "outputText" }
                val requestObjectKey = event.metadata.keys.find { it == "requestObject" }
                val generatedContentKey = event.metadata.keys.find { it == "generatedContent" }
                val fullPromptKey = event.metadata.keys.find { it == "fullPrompt" }
                val contentTextKey = event.metadata.keys.find { it == "contentText" }
                val pageKeyKey = event.metadata.keys.find { it == "pageKey" }
                val contextWindowKey = event.metadata.keys.find { it == "contextWindow" }
                val miniBankKey = event.metadata.keys.find { it == "miniBank" }

                val keysToExtract = setOfNotNull(reasoningKey, inputKey, outputKey, requestObjectKey, generatedContentKey, fullPromptKey, contentTextKey, pageKeyKey, contextWindowKey, miniBankKey)
                val otherMetadata = event.metadata.filterKeys { it !in keysToExtract }
                
                val metadataHtml = if (otherMetadata.isNotEmpty()) {
                    otherMetadata.entries.joinToString("<br>") { "<strong>${it.key}:</strong> ${it.value}" }
                } else {
                    ""
                }
                
                // Helper to create an expandable section
                fun createExpandableSection(label: String, content: String, icon: String, color: String): String {
                    if (content.isBlank() || content == "N/A" || content == "null") return ""
                    return """
                        <details style="margin-top: 8px;">
                            <summary style="cursor: pointer; color: ${color}; font-weight: bold;">
                                ${icon} ${label}
                                (${content.length} chars)
                            </summary>
                            <pre style="background: #f8f9fa; padding: 10px; border-radius: 4px; margin-top: 8px; white-space: pre-wrap; max-height: 400px; overflow-y: auto;">${escapeHtml(content)}</pre>
                        </details>
                    """.trimIndent()
                }

                val sections = mutableListOf<String>()
                if (metadataHtml.isNotEmpty()) {
                    sections.add(metadataHtml)
                }

                // Add inputText
                val inputText = inputKey?.let { event.metadata[it]?.toString() } ?:
                    if (event.eventType == TraceEventType.PIPE_START || event.eventType == TraceEventType.CONTEXT_PULL)
                        event.content?.text
                    else null

                if (!inputText.isNullOrBlank() && inputText != "N/A" && inputText != "null") {
                    sections.add(createExpandableSection("Input Content", inputText, "📥", "#28a745"))
                }

                // Add outputText
                val outputText = outputKey?.let { event.metadata[it]?.toString() } ?:
                    if (event.eventType == TraceEventType.PIPE_SUCCESS || event.eventType == TraceEventType.API_CALL_SUCCESS)
                        event.content?.text
                    else null

                if (!outputText.isNullOrBlank() && outputText != "N/A" && outputText != "null") {
                    sections.add(createExpandableSection("Output Content", outputText, "📤", "#17a2b8"))
                }

                // Add requestObject
                val requestObject = requestObjectKey?.let { event.metadata[it]?.toString() }
                if (!requestObject.isNullOrBlank() && requestObject != "N/A" && requestObject != "null") {
                    sections.add(createExpandableSection("Request Object", requestObject, "📦", "#6c757d"))
                }

                // Add generatedContent
                val generatedContent = generatedContentKey?.let { event.metadata[it]?.toString() }
                if (!generatedContent.isNullOrBlank() && generatedContent != "N/A" && generatedContent != "null") {
                    sections.add(createExpandableSection("Generated Content", generatedContent, "✨", "#fd7e14"))
                }

                // Add fullPrompt
                val fullPrompt = fullPromptKey?.let { event.metadata[it]?.toString() }
                if (!fullPrompt.isNullOrBlank() && fullPrompt != "N/A" && fullPrompt != "null") {
                    sections.add(createExpandableSection("Full Prompt", fullPrompt, "📝", "#000000"))
                }

                // Add contentText
                val contentText = contentTextKey?.let { event.metadata[it]?.toString() }
                if (!contentText.isNullOrBlank() && contentText != "N/A" && contentText != "null") {
                    sections.add(createExpandableSection("Content Text", contentText, "📄", "#000000"))
                }

                // Add pageKey
                val pageKey = pageKeyKey?.let { event.metadata[it]?.toString() }
                if (!pageKey.isNullOrBlank() && pageKey != "N/A" && pageKey != "null") {
                    sections.add(createExpandableSection("Page Key", pageKey, "🔑", "#ffc107"))
                }

                // Add contextWindow
                val contextWindow = contextWindowKey?.let { event.metadata[it]?.toString() }
                if (!contextWindow.isNullOrBlank() && contextWindow != "N/A" && contextWindow != "null") {
                    sections.add(createExpandableSection("Context Window", contextWindow, "🪟", "#6f42c1"))
                }

                // Add miniBank
                val miniBank = miniBankKey?.let { event.metadata[it]?.toString() }
                if (!miniBank.isNullOrBlank() && miniBank != "N/A" && miniBank != "null") {
                    sections.add(createExpandableSection("Mini Bank", miniBank, "🏦", "#e83e8c"))
                }

                // Add reasoning content in an expandable section
                if (reasoningKey != null) {
                    val reasoningContent = event.metadata[reasoningKey].toString()
                    if (reasoningContent.isNotBlank() && reasoningContent != "N/A" && reasoningContent != "null") {
                        sections.add(createExpandableSection("reasoningContent", reasoningContent, "🧠", "#007bff"))
                    }
                }

                if (sections.isNotEmpty()) {
                    sections.joinToString("")
                } else {
                    "-"
                }
            } else {
                "-"
            }
            
            val nodeKey = TraceNodeMapper.resolveNodeKey(event)
            table.append("""
                <tr id="${event.id}" class="trace-item" data-pipe="$nodeKey">
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
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 24px; background: #f1f5f9; color: #1e293b; }
                    .container { max-width: 1200px; margin: 0 auto; background: white; padding: 28px; border-radius: 14px; box-shadow: 0 22px 50px rgba(15,23,42,0.16); }
                    h1 { color: #0f172a; text-align: center; margin-bottom: 28px; font-size: 2rem; letter-spacing: -0.02em; }
                    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 18px; margin: 18px 0 34px; }
                    .summary-card { background: linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%); border-radius: 14px; padding: 18px 20px; border: 1px solid rgba(99,102,241,0.18); box-shadow: inset 0 1px 0 rgba(255,255,255,0.7); }
                    .summary-card h3 { margin: 0 0 8px; font-size: 0.8rem; letter-spacing: 0.12em; color: #475569; text-transform: uppercase; }
                    .summary-card .value { font-size: 1.75rem; font-weight: 600; color: #0f172a; }
                    .summary-card .subtext { font-size: 0.92rem; color: #64748b; margin-top: 8px; line-height: 1.4; }
                    .manifold-section { margin: 28px 0; padding: 22px; border-radius: 14px; border: 1px solid rgba(148,163,184,0.22); background: #f8fafc; box-shadow: inset 0 1px 0 rgba(255,255,255,0.9); }
                    .manifold-section h2 { margin-top: 0; margin-bottom: 18px; font-size: 1.25rem; color: #1e293b; }
                    .orchestration { border-left: 5px solid #6366f1; }
                    .agent-interaction { border-left: 5px solid #10b981; }
                    .mermaid { text-align: center; background: white; padding: 24px; border-radius: 12px; border: 1px solid rgba(148,163,184,0.25); box-shadow: 0 10px 20px rgba(15,23,42,0.08); }
                    .event-feed { display: flex; flex-direction: column; gap: 18px; }
                    .event-card { position: relative; padding: 20px 22px; border-radius: 14px; border: 1px solid rgba(148,163,184,0.25); background: white; box-shadow: 0 8px 18px rgba(15,23,42,0.08); transition: transform 0.18s ease, box-shadow 0.18s ease; }
                    .event-card:hover { transform: translateY(-2px); box-shadow: 0 14px 26px rgba(15,23,42,0.12); }
                    .event-card.highlighted { border-color: #facc15; box-shadow: 0 0 0 3px rgba(250,204,21,0.35); }
                    .event-card.success { border-left: 4px solid rgba(16,185,129,0.8); }
                    .event-card.failure { border-left: 4px solid rgba(239,68,68,0.85); }
                    .event-card.warning { border-left: 4px solid rgba(251,191,36,0.9); }
                    .event-card.info { border-left: 4px solid rgba(79,70,229,0.8); }
                    .event-header { display: flex; flex-wrap: wrap; gap: 12px 16px; align-items: center; margin-bottom: 16px; }
                    .event-time { font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; color: #64748b; padding: 4px 10px; border-radius: 9999px; background: rgba(226,232,240,0.6); border: 1px solid rgba(148,163,184,0.35); }
                    .event-type-badge { display: inline-flex; align-items: center; gap: 8px; padding: 7px 12px; border-radius: 9999px; font-weight: 600; font-size: 0.88rem; text-transform: capitalize; }
                    .event-type-badge.success { background: rgba(220,252,231,0.9); color: #166534; }
                    .event-type-badge.failure { background: rgba(254,226,226,0.9); color: #991b1b; }
                    .event-type-badge.warning { background: rgba(254,243,199,0.9); color: #92400e; }
                    .event-type-badge.info { background: rgba(224,231,255,0.95); color: #3730a3; }
                    .badge-icon { font-size: 1rem; }
                    .phase-pill { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 9999px; border: 1px solid rgba(148,163,184,0.35); background: rgba(148,163,184,0.15); font-size: 0.85rem; color: #475569; letter-spacing: 0.02em; }
                    .node-tag { padding: 6px 11px; border-radius: 999px; background: rgba(59,130,246,0.12); color: #1d4ed8; font-size: 0.88rem; font-weight: 500; }
                    .event-body { display: grid; gap: 18px; }
                    .event-section h4 { margin: 0 0 8px; font-size: 0.82rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.12em; color: #475569; }
                    .metadata-grid { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
                    .metadata-item { padding: 10px 12px; border-radius: 10px; background: rgba(148,163,184,0.1); border: 1px solid rgba(148,163,184,0.18); }
                    .metadata-item strong { display: block; font-size: 0.75rem; color: #475569; text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 4px; }
                    .metadata-item span { color: #0f172a; font-weight: 500; word-break: break-word; font-size: 0.92rem; }
                    .empty-state { margin: 0; color: #94a3b8; font-size: 0.9rem; font-style: italic; }
                    details.event-details { border: 1px solid rgba(148,163,184,0.25); border-radius: 10px; background: rgba(248,250,252,0.8); padding: 12px 14px; }
                    details.event-details summary { cursor: pointer; font-weight: 600; color: #334155; font-size: 0.95rem; list-style: none; display: flex; align-items: center; gap: 8px; }
                    details.event-details summary::before { content: "⤵"; transition: transform 0.2s ease; font-size: 0.9rem; }
                    details.event-details[open] summary::before { transform: rotate(-180deg); }
                    .content-preview { margin: 14px 4px 6px; border-radius: 10px; background: white; border: 1px solid rgba(148,163,184,0.25); padding: 14px; box-shadow: inset 0 1px 0 rgba(255,255,255,0.6); }
                    .content-preview pre { margin: 0; font-size: 0.85rem; line-height: 1.5; white-space: pre-wrap; word-break: break-word; font-family: 'JetBrains Mono', 'Fira Code', monospace; color: #1f2937; }
                    .context-chip { display: inline-flex; align-items: center; gap: 8px; padding: 5px 12px; margin: 6px 6px 0 0; border-radius: 999px; background: rgba(59,130,246,0.1); color: #1d4ed8; font-size: 0.85rem; font-weight: 500; }
                    .error-block { padding: 10px 12px; border-radius: 10px; background: rgba(239,68,68,0.08); border: 1px solid rgba(239,68,68,0.3); color: #b91c1c; font-weight: 500; font-size: 0.92rem; }
                    table { border-collapse: separate; border-spacing: 0; width: 100%; margin-top: 16px; border-radius: 12px; overflow: hidden; }
                    th { background-color: #0f172a; color: #e2e8f0; padding: 12px 18px; font-weight: 600; font-size: 0.92rem; text-align: left; letter-spacing: 0.04em; }
                    td { background: white; padding: 14px 18px; border-bottom: 1px solid rgba(148,163,184,0.25); font-size: 0.92rem; vertical-align: top; }
                    tr:last-child td { border-bottom: none; }
                    .trace-item { cursor: pointer; }
                    .trace-item.highlighted { background-color: rgba(250,204,21,0.18) !important; }
                    .flash-highlight { animation: flashEffect 2s ease-in-out; }
                    @keyframes flashEffect { 0%, 100% { background-color: inherit; } 50% { background-color: rgba(250,204,21,0.35); } }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>🎯 TPipe Manifold Execution Analysis</h1>

                    ${buildManifoldSummary(trace)}
                    
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
        val feed = StringBuilder()
        feed.append("<div class=\"event-feed\">")

        val startTime = trace.firstOrNull()?.timestamp ?: 0L
        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            val pipeName = mapManifoldNodeName(event)
            val severity = classifyEventSeverity(event)
            val phaseHtml = formatPhase(event.phase)
            val eventBadge = formatEventBadge(event, severity)
            val metadataSection = buildMetadataSection(event)
            val contentSection = buildContentSection(event)
            val errorSection = buildErrorSection(event)
            val elapsedHtml = "<span class=\"event-time\">+${elapsed}ms</span>"

            feed.append(
                """
                <article id="${event.id}" class="trace-item event-card ${severity.cssClass}" data-pipe="${escapeHtml(pipeName)}">
                    <header class="event-header">
                        $elapsedHtml
                        $eventBadge
                        $phaseHtml
                        <span class="node-tag">Node: ${escapeHtml(pipeName)}</span>
                    </header>
                    <div class="event-body">
                        $metadataSection
                        $contentSection
                        $errorSection
                    </div>
                </article>
                """.trimIndent()
            )
        }
        feed.append("</div>")
        return feed.toString()
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
            table.append("<tr class=\"trace-item agent-row\" data-pipe=\"$pipeName\"><td>$agentName</td><td>$dispatches</td><td>$responses</td><td>$successRate%</td></tr>")
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
        if (metadata.isEmpty()) return "<p class=\"empty-state\">No metadata recorded for this event.</p>"
        val items = metadata.entries.joinToString("") { (key, value) ->
            "<div class=\"metadata-item\"><strong>${escapeHtml(key)}</strong><span>${escapeHtml(value.toString())}</span></div>"
        }
        return "<div class=\"metadata-grid\">$items</div>"
    }

    private fun formatContentSummary(event: TraceEvent): String {
        val parts = mutableListOf<String>()
        event.content?.text?.takeIf { it.isNotBlank() }?.let { text ->
            val preview = if (text.length > 220) "${text.take(220)}…" else text
            parts.add("<div class=\"content-preview\"><pre>${escapeHtml(preview)}</pre></div>")
        }
        event.contextSnapshot?.let { snapshot ->
            parts.add("<span class=\"context-chip\">Context: ${escapeHtml(snapshot.toString())}</span>")
        }
        if (parts.isEmpty()) return "<p class=\"empty-state\">No content captured for this event.</p>"
        val inner = parts.joinToString("")
        return "<details class=\"event-details\"><summary>Content &amp; Context</summary>$inner</details>"
    }

    private fun buildMetadataSection(event: TraceEvent): String {
        val body = formatMetadata(event.metadata)
        return """
            <section class="event-section">
                <h4>Metadata</h4>
                $body
            </section>
        """.trimIndent()
    }

    private fun buildContentSection(event: TraceEvent): String {
        val body = formatContentSummary(event)
        return """
            <section class="event-section">
                <h4>Content &amp; Context</h4>
                $body
            </section>
        """.trimIndent()
    }

    private fun buildErrorSection(event: TraceEvent): String {
        val message = formatError(event.error) ?: return ""
        return """
            <section class="event-section">
                <h4>Error</h4>
                <div class="error-block">$message</div>
            </section>
        """.trimIndent()
    }

    private fun formatError(error: Throwable?): String? {
        error ?: return null
        return escapeHtml(error.message ?: error.toString())
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun formatPhase(phase: TracePhase): String {
        return "<span class=\"phase-pill\">${escapeHtml(phase.name.lowercase().replaceFirstChar { it.titlecase() })}</span>"
    }

    private fun formatEventBadge(event: TraceEvent, severity: EventSeverity): String {
        val icon = severity.icon
        val css = "event-badge ${severity.cssClass}"
        return "<span class=\"$css\"><span class=\"badge-icon\">$icon</span>${escapeHtml(event.eventType.name.lowercase().replace('_', ' '))}</span>"
    }

    private fun classifyEventSeverity(event: TraceEvent): EventSeverity {
        return when {
            event.eventType.name.contains("FAILURE", ignoreCase = true) -> EventSeverity.FAILURE
            event.eventType.name.contains("SUCCESS", ignoreCase = true) -> EventSeverity.SUCCESS
            event.eventType.name.contains("WARNING", ignoreCase = true) -> EventSeverity.WARNING
            else -> EventSeverity.INFO
        }
    }

    private fun buildManifoldSummary(trace: List<TraceEvent>): String {
        if (trace.isEmpty()) return ""
        val totalEvents = trace.size
        val failureCount = trace.count { classifyEventSeverity(it) == EventSeverity.FAILURE }
        val successCount = trace.count { classifyEventSeverity(it) == EventSeverity.SUCCESS }
        val start = trace.first().timestamp
        val end = trace.last().timestamp
        val durationMs = (end - start).coerceAtLeast(0L)
        val loopIterations = trace.count { it.eventType == TraceEventType.MANIFOLD_LOOP_ITERATION }
        val agentNames = trace.filter { it.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) }
            .mapNotNull { it.metadata["agentName"]?.toString() }
            .distinct()
        val duration = formatDuration(durationMs)
        val agentSummary = if (agentNames.isEmpty()) "No agent interactions" else agentNames.joinToString(", ") { escapeHtml(it) }

        return """
            <div class="summary-grid">
                <div class="summary-card">
                    <h3>Total Events</h3>
                    <div class="value">$totalEvents</div>
                    <div class="subtext">Across ${trace.map { it.phase }.distinct().size} phases</div>
                </div>
                <div class="summary-card">
                    <h3>Execution Time</h3>
                    <div class="value">$duration</div>
                    <div class="subtext">Loop iterations: $loopIterations</div>
                </div>
                <div class="summary-card">
                    <h3>Outcome</h3>
                    <div class="value">${successCount} ✓ / $failureCount ✕</div>
                    <div class="subtext">Success vs failure events</div>
                </div>
                <div class="summary-card">
                    <h3>Agents Touched</h3>
                    <div class="value">${agentNames.size}</div>
                    <div class="subtext">$agentSummary</div>
                </div>
            </div>
        """.trimIndent()
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "0 ms"
        val seconds = durationMs / 1000
        val millis = durationMs % 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return when {
            minutes > 0 -> String.format("%d:%02d.%03ds", minutes, remainingSeconds, millis)
            seconds > 0 -> String.format("%d.%03ds", seconds, millis)
            else -> "$millis ms"
        }
    }

    private enum class EventSeverity(val cssClass: String, val icon: String) {
        SUCCESS("success", "✅"),
        FAILURE("failure", "❌"),
        WARNING("warning", "⚠️"),
        INFO("info", "ℹ️")
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
            table.append("<tr class=\"trace-item\" data-pipe=\"$nodeKey\"><td>+${elapsed}ms</td><td>${event.pipeName}</td><td>${event.eventType}</td><td>$status</td></tr>")
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
            
            .trace-item.highlighted {
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
            
            .trace-item {
                transition: background-color 0.3s ease;
                cursor: pointer;
            }
            
            .trace-item:hover {
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
