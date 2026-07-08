package com.TTT.Debug

/**
 * Produces text && HTML reports from trace streams, with special handling for harness-level orchestration
 * traces such as Junction && Manifold.
 */
class TraceVisualizer
{
    /**
     * Render a human-readable flow chart for a trace stream.
     *
     * Junction && other harness traces get a dedicated heading so their orchestration steps are easy to
     * distinguish from ordinary pipe traces.
     *
     * @param trace The trace events to render.
     * @return A formatted textual flow chart.
     */
    fun generateFlowChart(trace: List<TraceEvent>): String {
        val flowChart = StringBuilder()

        // Detect whether the trace belongs to a container harness so the visualizer can pick the correct
        // heading && event symbols for Junction's discussion/workflow lifecycle.
        val isPumpStationTrace = trace.any { it.eventType.name.startsWith("PUMP_STATION_") }
        val isManifoldTrace = trace.any { it.eventType.name.startsWith("MANIFOLD_") }
        val isJunctionTrace = trace.any { it.eventType.name.startsWith("JUNCTION_") }
        val isDistributionGridTrace = trace.any { it.eventType.name.startsWith("DISTRIBUTION_GRID_") }

        if(isPumpStationTrace)
        {
            flowChart.append("=== PumpStation Orchestration Flow ===\n")
        }
        else if(isDistributionGridTrace)
        {
            flowChart.append("=== DistributionGrid Orchestration Flow ===\n")
        }
        else if(isJunctionTrace)
        {
            flowChart.append("=== Junction Discussion Flow ===\n")
        }
        else if(isManifoldTrace)
        {
            flowChart.append("=== Manifold Orchestration Flow ===\n")
        }
        else
        {
            flowChart.append("=== Pipeline Flow Chart ===\n")
        }
        
        trace.forEach { event ->
            val symbol = when(event.eventType) {
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
                TraceEventType.MANIFOLD_INIT_CHECK -> "🔍"
                TraceEventType.MANIFOLD_LOOP_ITERATION -> "🔄"
                
                // Junction orchestration events
                TraceEventType.JUNCTION_START -> "🧭"
                TraceEventType.JUNCTION_END -> "🏁"
                TraceEventType.JUNCTION_SUCCESS -> "✅"
                TraceEventType.JUNCTION_FAILURE -> "❌"
                TraceEventType.JUNCTION_PAUSE -> "⏸️"
                TraceEventType.JUNCTION_RESUME -> "▶️"
                TraceEventType.JUNCTION_ROUND_START -> "🗣️"
                TraceEventType.JUNCTION_ROUND_END -> "🔚"
                TraceEventType.JUNCTION_VOTE_TALLY -> "🗳️"
                TraceEventType.JUNCTION_CONSENSUS_CHECK -> "📏"
                TraceEventType.JUNCTION_PARTICIPANT_DISPATCH -> "📤"
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE -> "📥"
                TraceEventType.JUNCTION_WORKFLOW_START -> "⚙️"
                TraceEventType.JUNCTION_WORKFLOW_END -> "🏁"
                TraceEventType.JUNCTION_WORKFLOW_SUCCESS -> "✅"
                TraceEventType.JUNCTION_WORKFLOW_FAILURE -> "❌"
                TraceEventType.JUNCTION_PHASE_START -> "🧩"
                TraceEventType.JUNCTION_PHASE_END -> "🔁"
                TraceEventType.JUNCTION_HANDOFF -> "📦"

                // DistributionGrid orchestration events
                TraceEventType.DISTRIBUTION_GRID_START -> "🧭"
                TraceEventType.DISTRIBUTION_GRID_END -> "🏁"
                TraceEventType.DISTRIBUTION_GRID_SUCCESS -> "✅"
                TraceEventType.DISTRIBUTION_GRID_FAILURE -> "❌"
                TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION -> "🧠"
                TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_DISPATCH -> "🛠️"
                TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_RESPONSE -> "📥"
                TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF -> "📤"
                TraceEventType.DISTRIBUTION_GRID_PEER_RESPONSE -> "📬"
                TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE -> "🤝"
                TraceEventType.DISTRIBUTION_GRID_POLICY_EVALUATION -> "🛡️"
                TraceEventType.DISTRIBUTION_GRID_MEMORY_ENVELOPE -> "🧠"
                TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL -> "🗂️"
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE -> "🔎"
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION -> "🪪"
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL -> "♻️"
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY -> "📚"
                TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION -> "🧾"
                TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING -> "📣"
                TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW -> "⏱️"
                TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT -> "💾"
                TraceEventType.DISTRIBUTION_GRID_PAUSE -> "⏸️"
                TraceEventType.DISTRIBUTION_GRID_RESUME -> "▶️"

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

                // PumpStation orchestration events
                TraceEventType.PUMP_STATION_STARTED -> "⛽"
                TraceEventType.PUMP_STATION_COMPLETED -> "🏁"
                TraceEventType.PUMP_STATION_FAILED -> "❌"
                TraceEventType.PUMP_STATION_SUSPENDED -> "⏸"
                TraceEventType.PUMP_STATION_RESUMED -> "▶"
                TraceEventType.PUMP_STATION_HEALTH_CHECK_STARTED -> "💓"
                TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED -> "🩺"
                TraceEventType.PUMP_STATION_JUDGE_STARTED -> "⚖"
                TraceEventType.PUMP_STATION_JUDGE_COMPLETED -> "✅"
                TraceEventType.PUMP_STATION_JUDGE_SKIPPED -> "⏭"
                TraceEventType.PUMP_STATION_DISPATCH_STARTED -> "🧭"
                TraceEventType.PUMP_STATION_DISPATCH_COMPLETED -> "🛤"
                TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED -> "🛡"
                TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED -> "🛡"
                TraceEventType.PUMP_STATION_PATH_VALIDATION_COMPLETED -> "✔"
                TraceEventType.PUMP_STATION_INTERVENTION_STARTED -> "🆘"
                TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED -> "🆘"
                TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED -> "👤"
                TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED -> "👥"
                TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED -> "🧠"
                TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED -> "🧠"
                TraceEventType.PUMP_STATION_COMPACTION_STARTED -> "🗜"
                TraceEventType.PUMP_STATION_COMPACTION_COMPLETED -> "🗜"
                TraceEventType.PUMP_STATION_COMPACTION_INFLATED -> "⚠"
                TraceEventType.PUMP_STATION_COMPACTION_ROLLED_BACK -> "↩"
                TraceEventType.PUMP_STATION_COMPACTION_HANDED_OFF -> "⤵"
                TraceEventType.PUMP_STATION_SAFE_PRUNE_APPLIED -> "✂"
                TraceEventType.PUMP_STATION_SAFE_PRUNE_DRY_RUN_COMPLETED -> "🔍"
                TraceEventType.PUMP_STATION_GOAL_VALIDATION_STARTED -> "🎯"
                TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED -> "🎯"
                TraceEventType.PUMP_STATION_PATH_SELECTED -> "👆"
                TraceEventType.PUMP_STATION_PATH_STARTED -> "▶"
                TraceEventType.PUMP_STATION_PATH_COMPLETED -> "✅"
                TraceEventType.PUMP_STATION_PATH_FAILED -> "❌"
                TraceEventType.PUMP_STATION_PATH_HIDDEN -> "🚫"
                TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED -> "🔓"
                TraceEventType.PUMP_STATION_STASH_CREATED -> "📦"
                TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED -> "💥"
                TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED -> "⚠"

                else -> "ℹ️"
            }
            
            flowChart.append("$symbol ${event.pipeName} -> ${event.eventType}\n")
        }
        
        return flowChart.toString()
    }
    
    /**
     * Render a time-ordered execution timeline for a trace stream.
     *
     * @param trace The trace events to render.
     * @return A formatted timeline string.
     */
    fun generateTimeline(trace: List<TraceEvent>): String {
        val timeline = StringBuilder()
        val heading = when {
            trace.any { it.eventType.name.startsWith("PUMP_STATION_") } -> "=== PumpStation Timeline ===\n"
            trace.any { it.eventType.name.startsWith("DISTRIBUTION_GRID_") } -> "=== DistributionGrid Timeline ===\n"
            trace.any { it.eventType.name.startsWith("JUNCTION_") } -> "=== Junction Timeline ===\n"
            trace.any { it.eventType.name.startsWith("MANIFOLD_") } -> "=== Manifold Timeline ===\n"
            else -> "=== Execution Timeline ===\n"
        }
        timeline.append(heading)
        
        val startTime = trace.firstOrNull()?.timestamp ?: 0L

        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            val label = when {
                event.eventType.name.startsWith("PUMP_STATION_") -> mapPumpStationNodeName(event)
                event.eventType.name.startsWith("DISTRIBUTION_GRID_") -> mapDistributionGridNodeName(event)
                event.eventType.name.startsWith("JUNCTION_") -> mapJunctionNodeName(event)
                event.eventType.name.startsWith("MANIFOLD_") ||
                    event.eventType.name.startsWith("MANAGER_") ||
                    event.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) -> mapManifoldNodeName(event)
                else -> event.pipeName
            }
            timeline.append("[${elapsed}ms] ${label}: ${event.eventType} (${event.phase})\n")

            if(event.error != null)
            {
                timeline.append("    ERROR: ${event.error.message}\n")
            }
        }

        return timeline.toString()
    }
    
    /**
     * Render a compact console-friendly summary of the supplied trace events.
     *
     * @param trace The trace events to render.
     * @return A textual console summary.
     */
    fun generateConsoleOutput(trace: List<TraceEvent>): String {
        val output = StringBuilder()
        val heading = when {
            trace.any { it.eventType.name.startsWith("PUMP_STATION_") } -> "=== TPipe PumpStation Trace ===\n"
            trace.any { it.eventType.name.startsWith("DISTRIBUTION_GRID_") } -> "=== TPipe DistributionGrid Trace ===\n"
            trace.any { it.eventType.name.startsWith("JUNCTION_") } -> "=== TPipe Junction Trace ===\n"
            trace.any { it.eventType.name.startsWith("MANIFOLD_") } -> "=== TPipe Manifold Trace ===\n"
            else -> "=== TPipe Execution Trace ===\n"
        }
        output.append(heading)

        trace.forEach { event ->
            val status = when(event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS,
                TraceEventType.PUMP_STATION_COMPLETED, TraceEventType.PUMP_STATION_PATH_COMPLETED,
                TraceEventType.PUMP_STATION_JUDGE_COMPLETED, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED,
                TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED -> "[SUCCESS]"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE,
                TraceEventType.PUMP_STATION_FAILED, TraceEventType.PUMP_STATION_PATH_FAILED,
                TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED, TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED -> "[FAILURE]"
                else -> "[INFO]"
            }

            val label = when {
                event.eventType.name.startsWith("PUMP_STATION_") -> mapPumpStationNodeName(event)
                event.eventType.name.startsWith("DISTRIBUTION_GRID_") -> mapDistributionGridNodeName(event)
                event.eventType.name.startsWith("JUNCTION_") -> mapJunctionNodeName(event)
                event.eventType.name.startsWith("MANIFOLD_") ||
                    event.eventType.name.startsWith("MANAGER_") ||
                    event.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) -> mapManifoldNodeName(event)
                else -> event.pipeName
            }

            output.append("$status $label - ${event.eventType} (${event.phase})\n")
            
            if(event.error != null)
            {
                output.append("  Error: ${event.error.message}\n")
            }
            
            if(event.metadata.isNotEmpty())
            {
                output.append("  Metadata: ${event.metadata}\n")
            }
        }
        
        return output.toString()
    }

    /**
     * Render Markdown output for trace streams.
     */
    fun generateMarkdownOutput(trace: List<TraceEvent>): String {
        val heading = when {
            trace.any { it.eventType.name.startsWith("PUMP_STATION_") } -> "# TPipe PumpStation Trace Report\n\n"
            trace.any { it.eventType.name.startsWith("DISTRIBUTION_GRID_") } -> "# TPipe DistributionGrid Trace Report\n\n"
            trace.any { it.eventType.name.startsWith("JUNCTION_") } -> "# TPipe Junction Trace Report\n\n"
            trace.any { it.eventType.name.startsWith("MANIFOLD_") } -> "# TPipe Manifold Trace Report\n\n"
            else -> "# TPipe Trace Report\n\n"
        }
        val md = StringBuilder(heading)
        md.append("| Timestamp | Node | Event | Phase | Status |\n")
        md.append("|-----------|------|-------|-------|--------|\n")

        trace.forEach { event ->
            val label = when {
                event.eventType.name.startsWith("PUMP_STATION_") -> mapPumpStationNodeName(event)
                event.eventType.name.startsWith("DISTRIBUTION_GRID_") -> mapDistributionGridNodeName(event)
                event.eventType.name.startsWith("JUNCTION_") -> mapJunctionNodeName(event)
                event.eventType.name.startsWith("MANIFOLD_") ||
                    event.eventType.name.startsWith("MANAGER_") ||
                    event.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) -> mapManifoldNodeName(event)
                else -> event.pipeName
            }

            val status = when {
                event.eventType.name.contains("SUCCESS") -> "SUCCESS"
                event.eventType.name.contains("FAILURE") -> "FAILURE"
                else -> "INFO"
            }
            md.append("| ${event.timestamp} | $label | ${event.eventType} | ${event.phase} | $status |\n")
        }

        return md.toString()
    }
    
    /**
     * Render an HTML report for the supplied trace stream.
     *
     * Junction traces use a custom report path so workflow phases, handoff events, && discussion rounds can
     * be grouped into a harness-aware presentation.
     *
     * @param trace The trace events to render.
     * @return An HTML report string.
     */
    fun generateHtmlReport(trace: List<TraceEvent>): String {
        // Junction traces are rendered with their own report layout because the harness can emit both
        // discussion && workflow phase events, && those need to be grouped differently from plain pipes.
        val isJunctionTrace = trace.any { it.eventType.name.startsWith("JUNCTION_") }
        val isManifoldTrace = trace.any { it.eventType.name.startsWith("MANIFOLD_") }
        val isDistributionGridTrace = trace.any { it.eventType.name.startsWith("DISTRIBUTION_GRID_") }
        val isSplitterTrace = trace.any { it.eventType.name.startsWith("SPLITTER_") }
        val isPumpStationTrace = trace.any { it.eventType.name.startsWith("PUMP_STATION_") }

        return if(isSplitterTrace) {
            generateSplitterHtmlReport(trace)
        }
        else if(isDistributionGridTrace) {
            generateDistributionGridHtmlReport(trace)
        }
        else if(isJunctionTrace) {
            generateJunctionHtmlReport(trace)
        }
        else if(isManifoldTrace) {
            generateManifoldHtmlReport(trace)
        }
        else if(isPumpStationTrace) {
            generatePumpStationHtmlReport(trace)
        }
        else
        {
            generateStandardHtmlReport(trace)
        }
    }
    
    private fun generateMermaidFlowGraph(trace: List<TraceEvent>, nodes: List<TraceNode>): String {
        val graph = StringBuilder()
        graph.append("graph TD\n")
        
        val nodeMap = nodes.associate { it.pipeName to it.nodeId }
        
        // Create nodes for each pipe
        nodes.forEachIndexed { index, node ->
            val label = formatNodeLabel(node.pipeName).replace("\n", "<br/>")
            if(index == 0)
            {
                graph.append("    ${node.nodeId}{{\"$label\"}}\n")
            } else {
                graph.append("    ${node.nodeId}[\"$label\"]\n")
            }
            graph.append("    click ${node.nodeId} scrollToEvent\n")  // ADD: Click handler
        }
        
        // Add connections && styling based on events
        var prevNode: String? = null
        trace.forEach { event ->
            val nodeKey = TraceNodeMapper.resolveNodeKey(event)
            val currentNode = nodeMap[nodeKey]
                ?: nodeMap.entries.find { it.key.startsWith(nodeKey) && it.key.count { it == ':' } == nodeKey.count { it == ':' } + 1 }?.value
                ?: nodeMap.entries.find { nodeKey.startsWith(it.key) && nodeKey.count { it == ':' } == it.key.count { it == ':' } + 1 }?.value
                ?: nodeMap[event.pipeName]

            if(prevNode != null && currentNode != null && prevNode != currentNode)
            {
                graph.append("    $prevNode --> $currentNode\n")
            }
            
            // Add styling based on event type
            when(event.eventType)
            {
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
            
            if(currentNode != null)
            {
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
            val statusClass = when(event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "success"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "failure"
                else -> "info"
            }
            
            val status = when(event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "✅ SUCCESS"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "❌ FAILURE"
                else -> "ℹ️ INFO"
            }
            
            val metadata = if(event.error != null) {
                "<strong>Error:</strong> ${event.error.message}"
            }
            else if(event.metadata.isNotEmpty() || event.content?.text?.isNotBlank() == true)
            {
                // Separate reasoning content, inputText, && outputText from other metadata for better display
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
                
                val metadataHtml = if(otherMetadata.isNotEmpty()) {
                    otherMetadata.entries.joinToString("<br>") { entry ->
                        val key = entry.key
                        val value = entry.value
                        if (key.contains("token", ignoreCase = true)) {
                            val color = when {
                                key.contains("input", ignoreCase = true) -> "#28a745" // Green
                                key.contains("output", ignoreCase = true) -> "#17a2b8" // Blue
                                else -> "#6f42c1" // Purple
                            }
                            "<strong>${escapeHtml(key)}:</strong> <span style=\"color: $color; font-weight: bold;\">${escapeHtml(value.toString())}</span>"
                        } else {
                            "<strong>${escapeHtml(key)}:</strong> ${escapeHtml(value.toString())}"
                        }
                    }
                }
                else
                {
                    ""
                }

                val sections = mutableListOf<String>()
                if(metadataHtml.isNotEmpty())
                {
                    sections.add(metadataHtml)
                }

                // Add inputText
                val inputText = inputKey?.let { event.metadata[it]?.toString() } ?:
                    if(event.eventType == TraceEventType.PIPE_START || event.eventType == TraceEventType.CONTEXT_PULL)
                        event.content?.text
                    else null

                if(!inputText.isNullOrBlank() && inputText != "N/A" && inputText != "null")
                {
                    sections.add(createExpandableSection("Input Content", inputText, "📥", "#28a745"))
                }

                // Add outputText
                val outputText = outputKey?.let { event.metadata[it]?.toString() } ?:
                    if(event.eventType == TraceEventType.PIPE_SUCCESS || event.eventType == TraceEventType.API_CALL_SUCCESS)
                        event.content?.text
                    else null

                if(!outputText.isNullOrBlank() && outputText != "N/A" && outputText != "null")
                {
                    sections.add(createExpandableSection("Output Content", outputText, "📤", "#17a2b8"))
                }

                // Add requestObject
                val requestObject = requestObjectKey?.let { event.metadata[it]?.toString() }
                if(!requestObject.isNullOrBlank() && requestObject != "N/A" && requestObject != "null")
                {
                    sections.add(createExpandableSection("Request Object", requestObject, "📦", "#6c757d"))
                }

                // Add generatedContent
                val generatedContent = generatedContentKey?.let { event.metadata[it]?.toString() }
                if(!generatedContent.isNullOrBlank() && generatedContent != "N/A" && generatedContent != "null")
                {
                    sections.add(createExpandableSection("Generated Content", generatedContent, "✨", "#fd7e14"))
                }

                // Add fullPrompt
                val fullPrompt = fullPromptKey?.let { event.metadata[it]?.toString() }
                if(!fullPrompt.isNullOrBlank() && fullPrompt != "N/A" && fullPrompt != "null")
                {
                    sections.add(createExpandableSection("Full Prompt", fullPrompt, "📝", "#000000"))
                }

                // Add contentText
                val contentText = contentTextKey?.let { event.metadata[it]?.toString() }
                if(!contentText.isNullOrBlank() && contentText != "N/A" && contentText != "null")
                {
                    sections.add(createExpandableSection("Content Text", contentText, "📄", "#000000"))
                }

                // Add pageKey
                val pageKey = pageKeyKey?.let { event.metadata[it]?.toString() }
                if(!pageKey.isNullOrBlank() && pageKey != "N/A" && pageKey != "null")
                {
                    sections.add(createExpandableSection("Page Key", pageKey, "🔑", "#ffc107"))
                }

                // Add contextWindow
                val contextWindow = contextWindowKey?.let { event.metadata[it]?.toString() }
                if(!contextWindow.isNullOrBlank() && contextWindow != "N/A" && contextWindow != "null")
                {
                    sections.add(createExpandableSection("Context Window", contextWindow, "🪟", "#6f42c1"))
                }

                // Add miniBank
                val miniBank = miniBankKey?.let { event.metadata[it]?.toString() }
                if(!miniBank.isNullOrBlank() && miniBank != "N/A" && miniBank != "null")
                {
                    sections.add(createExpandableSection("Mini Bank", miniBank, "🏦", "#e83e8c"))
                }

                // Add reasoning content in an expandable section
                if(reasoningKey != null)
                {
                    val reasoningContent = event.metadata[reasoningKey].toString()
                    if(reasoningContent.isNotBlank() && reasoningContent != "N/A" && reasoningContent != "null")
                    {
                        sections.add(createExpandableSection("reasoningContent", reasoningContent, "🧠", "#007bff"))
                    }
                }

                if(sections.isNotEmpty())
                {
                    sections.joinToString("")
                }
                else
                {
                    "-"
                }
            }
            else
            {
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
        val orchestrationTable = generateOrchestrationTable(trace, ::mapManifoldNodeName)
        val agentInteractionTable = generateAgentInteractionTable(trace)
        val tokenCard = buildContainerTokenCard(trace)
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
                    .metadata-grid > .metadata-item { min-width: 0; }
                    .metadata-item { min-width: 0; padding: 10px 12px; border-radius: 10px; background: rgba(148,163,184,0.1); border: 1px solid rgba(148,163,184,0.18); }
                    .metadata-item strong { display: block; font-size: 0.75rem; color: #475569; text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 4px; white-space: normal; overflow-wrap: anywhere; word-break: break-word; line-height: 1.25; }
                    .metadata-item span { color: #0f172a; font-weight: 500; word-break: break-word; overflow-wrap: anywhere; font-size: 0.92rem; line-height: 1.35; }
                    .empty-state { margin: 0; color: #94a3b8; font-size: 0.9rem; font-style: italic; }
                    /* SafePrune events get a popover-on-hover that surfaces the full report payload
                       (originalCount, finalCount, tokensRemoved, enabledFlags). The popover is
                       hidden by default and revealed when the card is hovered or focused. */
                    .event-card[data-safe-prune="true"] { cursor: help; }
                    .event-card[data-safe-prune="true"] .safe-prune-popup { display: none; position: absolute; top: 100%; right: 0; margin-top: 6px; padding: 10px 12px; min-width: 240px; max-width: 360px; border-radius: 10px; background: #0f172a; color: #e2e8f0; font-size: 0.82rem; line-height: 1.45; box-shadow: 0 12px 28px rgba(15,23,42,0.45); z-index: 50; pointer-events: none; }
                    .event-card[data-safe-prune="true"]:hover .safe-prune-popup,
                    .event-card[data-safe-prune="true"]:focus-within .safe-prune-popup { display: block; }
                    .event-card[data-safe-prune="dry-run"] .safe-prune-popup { background: #1e3a5f; }
                    .safe-prune-popup h5 { margin: 0 0 6px; font-size: 0.78rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: #facc15; }
                    .safe-prune-popup dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; }
                    .safe-prune-popup dt { color: #94a3b8; font-weight: 600; }
                    .safe-prune-popup dd { margin: 0; color: #f1f5f9; }
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
                    $tokenCard

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
     * Generates HTML report for Junction discussion traces by reusing the orchestration layout with Junction labels.
     */
    private fun generateJunctionHtmlReport(trace: List<TraceEvent>): String
    {
        val nodes = buildJunctionNodes(trace)
        val mermaidGraph = generateJunctionMermaidGraph(nodes, trace)
        val stateRibbon = buildJunctionStateRibbon(trace)
        val orchestrationTable = generateOrchestrationTable(trace, ::mapJunctionNodeName)
        val participantInteractionTable = generateParticipantInteractionTable(trace)
        val tokenCard = buildContainerTokenCard(trace)
        val javascript = TraceInteractivity.generateJavaScript(nodes)

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>TPipe Junction Execution Analysis</title>
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
                    .state-ribbon { margin: 22px 0 30px; padding: 20px 22px; border-radius: 14px; border: 1px solid rgba(245,158,11,0.22); background: linear-gradient(135deg, rgba(255,251,235,0.95) 0%, rgba(255,247,237,0.95) 100%); box-shadow: inset 0 1px 0 rgba(255,255,255,0.75); }
                    .state-ribbon h2 { margin-top: 0; margin-bottom: 16px; font-size: 1.15rem; color: #92400e; }
                    .state-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 14px; }
                    .state-card { background: rgba(255,255,255,0.88); border-radius: 12px; padding: 16px 18px; border: 1px solid rgba(245,158,11,0.18); box-shadow: 0 8px 18px rgba(15,23,42,0.06); }
                    .state-card h3 { margin: 0 0 8px; font-size: 0.78rem; letter-spacing: 0.12em; color: #b45309; text-transform: uppercase; }
                    .state-card .value { font-size: 1rem; font-weight: 600; color: #0f172a; line-height: 1.45; word-break: break-word; }
                    .state-card .subtext { font-size: 0.85rem; color: #64748b; margin-top: 6px; line-height: 1.35; }
                    .junction-section { margin: 28px 0; padding: 22px; border-radius: 14px; border: 1px solid rgba(148,163,184,0.22); background: #f8fafc; box-shadow: inset 0 1px 0 rgba(255,255,255,0.9); }
                    .junction-section h2 { margin-top: 0; margin-bottom: 18px; font-size: 1.25rem; color: #1e293b; }
                    .orchestration { border-left: 5px solid #6366f1; }
                    .participant-interaction { border-left: 5px solid #10b981; }
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
                    .metadata-grid > .metadata-item { min-width: 0; }
                    .metadata-item { min-width: 0; padding: 10px 12px; border-radius: 10px; background: rgba(148,163,184,0.1); border: 1px solid rgba(148,163,184,0.18); }
                    .metadata-item strong { display: block; font-size: 0.75rem; color: #475569; text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 4px; white-space: normal; overflow-wrap: anywhere; word-break: break-word; line-height: 1.25; }
                    .metadata-item span { color: #0f172a; font-weight: 500; word-break: break-word; overflow-wrap: anywhere; font-size: 0.92rem; line-height: 1.35; }
                    .empty-state { margin: 0; color: #94a3b8; font-size: 0.9rem; font-style: italic; }
                    /* SafePrune events get a popover-on-hover that surfaces the full report payload
                       (originalCount, finalCount, tokensRemoved, enabledFlags). The popover is
                       hidden by default and revealed when the card is hovered or focused. */
                    .event-card[data-safe-prune="true"] { cursor: help; }
                    .event-card[data-safe-prune="true"] .safe-prune-popup { display: none; position: absolute; top: 100%; right: 0; margin-top: 6px; padding: 10px 12px; min-width: 240px; max-width: 360px; border-radius: 10px; background: #0f172a; color: #e2e8f0; font-size: 0.82rem; line-height: 1.45; box-shadow: 0 12px 28px rgba(15,23,42,0.45); z-index: 50; pointer-events: none; }
                    .event-card[data-safe-prune="true"]:hover .safe-prune-popup,
                    .event-card[data-safe-prune="true"]:focus-within .safe-prune-popup { display: block; }
                    .event-card[data-safe-prune="dry-run"] .safe-prune-popup { background: #1e3a5f; }
                    .safe-prune-popup h5 { margin: 0 0 6px; font-size: 0.78rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: #facc15; }
                    .safe-prune-popup dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; }
                    .safe-prune-popup dt { color: #94a3b8; font-weight: 600; }
                    .safe-prune-popup dd { margin: 0; color: #f1f5f9; }
                    details.event-details { border: 1px solid rgba(148,163,184,0.25); border-radius: 10px; background: rgba(248,250,252,0.8); padding: 12px 14px; }
                    details.event-details summary { cursor: pointer; font-weight: 600; color: #334155; font-size: 0.95rem; list-style: none; display: flex; align-items: center; gap: 8px; }
                    details.event-details summary::before { content: "⤵"; transition: transform 0.2s ease; font-size: 0.9rem; }
                    details.event-details[open] summary::before { transform: rotate(-180deg); }
                    .content-preview { margin: 14px 4px 6px; border-radius: 10px; background: white; border: 1px solid rgba(148,163,184,0.25); padding: 14px; }
                    .content-preview pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: 'JetBrains Mono', monospace; font-size: 0.84rem; line-height: 1.5; color: #0f172a; }
                    .context-chip { display: inline-flex; margin-top: 10px; padding: 6px 10px; border-radius: 9999px; background: rgba(167,139,250,0.15); color: #6b21a8; font-weight: 600; font-size: 0.84rem; }
                    .error-block { margin: 0; padding: 12px 14px; background: rgba(254,226,226,0.75); border-radius: 10px; color: #991b1b; border: 1px solid rgba(248,113,113,0.35); font-family: 'JetBrains Mono', monospace; font-size: 0.84rem; white-space: pre-wrap; }
                    table { width: 100%; border-collapse: collapse; }
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
                    <h1>🎯 TPipe Junction Execution Analysis</h1>
                    $tokenCard

                    ${buildJunctionSummary(trace)}

                    $stateRibbon

                    <div class="junction-section orchestration">
                        <h2>📊 Orchestration Flow</h2>
                        <div class="mermaid">$mermaidGraph</div>
                    </div>

                    <div class="junction-section orchestration">
                        <h2>🎯 Orchestration Timeline</h2>
                        $orchestrationTable
                    </div>

                    <div class="junction-section participant-interaction">
                        <h2>🤖 Participant Interactions</h2>
                        $participantInteractionTable
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
     * Generates DistributionGrid-specific HTML report with unified execution, discovery, && hosted-listing views.
     */
    private fun generateDistributionGridHtmlReport(trace: List<TraceEvent>): String
    {
        val nodes = buildDistributionGridNodes(trace)
        val mermaidGraph = generateDistributionGridMermaidGraph(nodes, trace)
        val orchestrationTable = generateOrchestrationTable(trace, ::mapDistributionGridNodeName)
        val activityTable = generateDistributionGridActivityTable(trace)
        val stateRibbon = buildDistributionGridStateRibbon(trace)
        val tokenCard = buildContainerTokenCard(trace)
        val javascript = TraceInteractivity.generateJavaScript(nodes)

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>TPipe DistributionGrid Execution Analysis</title>
                <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 24px; background: #f1f5f9; color: #1e293b; }
                    .container { max-width: 1280px; margin: 0 auto; background: white; padding: 28px; border-radius: 14px; box-shadow: 0 22px 50px rgba(15,23,42,0.16); }
                    h1 { color: #0f172a; text-align: center; margin-bottom: 28px; font-size: 2rem; letter-spacing: -0.02em; }
                    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 18px; margin: 18px 0 34px; }
                    .summary-card { background: linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%); border-radius: 14px; padding: 18px 20px; border: 1px solid rgba(59,130,246,0.18); box-shadow: inset 0 1px 0 rgba(255,255,255,0.7); }
                    .summary-card h3 { margin: 0 0 8px; font-size: 0.8rem; letter-spacing: 0.12em; color: #475569; text-transform: uppercase; }
                    .summary-card .value { font-size: 1.75rem; font-weight: 600; color: #0f172a; }
                    .summary-card .subtext { font-size: 0.92rem; color: #64748b; margin-top: 8px; line-height: 1.4; }
                    .state-ribbon { margin: 22px 0 30px; padding: 20px 22px; border-radius: 14px; border: 1px solid rgba(14,165,233,0.22); background: linear-gradient(135deg, rgba(239,246,255,0.96) 0%, rgba(236,253,245,0.96) 100%); box-shadow: inset 0 1px 0 rgba(255,255,255,0.75); }
                    .state-ribbon h2 { margin-top: 0; margin-bottom: 16px; font-size: 1.15rem; color: #075985; }
                    .state-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 14px; }
                    .state-card { background: rgba(255,255,255,0.9); border-radius: 12px; padding: 16px 18px; border: 1px solid rgba(14,165,233,0.16); box-shadow: 0 8px 18px rgba(15,23,42,0.06); }
                    .state-card h3 { margin: 0 0 8px; font-size: 0.78rem; letter-spacing: 0.12em; color: #0369a1; text-transform: uppercase; }
                    .state-card .value { font-size: 1rem; font-weight: 600; color: #0f172a; line-height: 1.45; word-break: break-word; }
                    .state-card .subtext { font-size: 0.85rem; color: #64748b; margin-top: 6px; line-height: 1.35; }
                    .grid-section { margin: 28px 0; padding: 22px; border-radius: 14px; border: 1px solid rgba(148,163,184,0.22); background: #f8fafc; box-shadow: inset 0 1px 0 rgba(255,255,255,0.9); }
                    .grid-section h2 { margin-top: 0; margin-bottom: 18px; font-size: 1.25rem; color: #1e293b; }
                    .orchestration { border-left: 5px solid #0284c7; }
                    .activity { border-left: 5px solid #10b981; }
                    .mermaid { text-align: center; background: white; padding: 24px; border-radius: 12px; border: 1px solid rgba(148,163,184,0.25); box-shadow: 0 10px 20px rgba(15,23,42,0.08); }
                    .event-feed { display: flex; flex-direction: column; gap: 18px; }
                    .event-card { position: relative; padding: 20px 22px; border-radius: 14px; border: 1px solid rgba(148,163,184,0.25); background: white; box-shadow: 0 8px 18px rgba(15,23,42,0.08); transition: transform 0.18s ease, box-shadow 0.18s ease; }
                    .event-card:hover { transform: translateY(-2px); box-shadow: 0 14px 26px rgba(15,23,42,0.12); }
                    .event-card.highlighted { border-color: #facc15; box-shadow: 0 0 0 3px rgba(250,204,21,0.35); }
                    .event-card.success { border-left: 4px solid rgba(16,185,129,0.8); }
                    .event-card.failure { border-left: 4px solid rgba(239,68,68,0.85); }
                    .event-card.warning { border-left: 4px solid rgba(251,191,36,0.9); }
                    .event-card.info { border-left: 4px solid rgba(2,132,199,0.85); }
                    .event-header { display: flex; flex-wrap: wrap; gap: 12px 16px; align-items: center; margin-bottom: 16px; }
                    .event-time { font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; color: #64748b; padding: 4px 10px; border-radius: 9999px; background: rgba(226,232,240,0.6); border: 1px solid rgba(148,163,184,0.35); }
                    .event-badge { display: inline-flex; align-items: center; gap: 8px; padding: 7px 12px; border-radius: 9999px; font-weight: 600; font-size: 0.88rem; text-transform: capitalize; }
                    .event-badge.success { background: rgba(220,252,231,0.9); color: #166534; }
                    .event-badge.failure { background: rgba(254,226,226,0.9); color: #991b1b; }
                    .event-badge.warning { background: rgba(254,243,199,0.9); color: #92400e; }
                    .event-badge.info { background: rgba(224,242,254,0.95); color: #075985; }
                    .badge-icon { font-size: 1rem; }
                    .phase-pill { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 9999px; border: 1px solid rgba(148,163,184,0.35); background: rgba(148,163,184,0.15); font-size: 0.85rem; color: #475569; letter-spacing: 0.02em; }
                    .node-tag { padding: 6px 11px; border-radius: 999px; background: rgba(14,165,233,0.12); color: #0369a1; font-size: 0.88rem; font-weight: 500; }
                    .event-body { display: grid; gap: 18px; }
                    .event-section h4 { margin: 0 0 8px; font-size: 0.82rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.12em; color: #475569; }
                    .metadata-grid { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
                    .metadata-grid > .metadata-item { min-width: 0; }
                    .metadata-item { min-width: 0; padding: 10px 12px; border-radius: 10px; background: rgba(148,163,184,0.1); border: 1px solid rgba(148,163,184,0.18); }
                    .metadata-item strong { display: block; font-size: 0.75rem; color: #475569; text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 4px; white-space: normal; overflow-wrap: anywhere; word-break: break-word; line-height: 1.25; }
                    .metadata-item span { color: #0f172a; font-weight: 500; word-break: break-word; overflow-wrap: anywhere; font-size: 0.92rem; line-height: 1.35; }
                    .empty-state { margin: 0; color: #94a3b8; font-size: 0.9rem; font-style: italic; }
                    /* SafePrune events get a popover-on-hover that surfaces the full report payload
                       (originalCount, finalCount, tokensRemoved, enabledFlags). The popover is
                       hidden by default and revealed when the card is hovered or focused. */
                    .event-card[data-safe-prune="true"] { cursor: help; }
                    .event-card[data-safe-prune="true"] .safe-prune-popup { display: none; position: absolute; top: 100%; right: 0; margin-top: 6px; padding: 10px 12px; min-width: 240px; max-width: 360px; border-radius: 10px; background: #0f172a; color: #e2e8f0; font-size: 0.82rem; line-height: 1.45; box-shadow: 0 12px 28px rgba(15,23,42,0.45); z-index: 50; pointer-events: none; }
                    .event-card[data-safe-prune="true"]:hover .safe-prune-popup,
                    .event-card[data-safe-prune="true"]:focus-within .safe-prune-popup { display: block; }
                    .event-card[data-safe-prune="dry-run"] .safe-prune-popup { background: #1e3a5f; }
                    .safe-prune-popup h5 { margin: 0 0 6px; font-size: 0.78rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: #facc15; }
                    .safe-prune-popup dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; }
                    .safe-prune-popup dt { color: #94a3b8; font-weight: 600; }
                    .safe-prune-popup dd { margin: 0; color: #f1f5f9; }
                    details.event-details { border: 1px solid rgba(148,163,184,0.25); border-radius: 10px; background: rgba(248,250,252,0.8); padding: 12px 14px; }
                    details.event-details summary { cursor: pointer; font-weight: 600; color: #334155; font-size: 0.95rem; list-style: none; display: flex; align-items: center; gap: 8px; }
                    details.event-details summary::before { content: "⤵"; transition: transform 0.2s ease; font-size: 0.9rem; }
                    details.event-details[open] summary::before { transform: rotate(-180deg); }
                    .content-preview { margin: 14px 4px 6px; border-radius: 10px; background: white; border: 1px solid rgba(148,163,184,0.25); padding: 14px; }
                    .content-preview pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: 'JetBrains Mono', monospace; font-size: 0.84rem; line-height: 1.5; color: #0f172a; }
                    .context-chip { display: inline-flex; margin-top: 10px; padding: 6px 10px; border-radius: 9999px; background: rgba(14,165,233,0.12); color: #0369a1; font-weight: 600; font-size: 0.84rem; }
                    .error-block { margin: 0; padding: 12px 14px; background: rgba(254,226,226,0.75); border-radius: 10px; color: #991b1b; border: 1px solid rgba(248,113,113,0.35); font-family: 'JetBrains Mono', monospace; font-size: 0.84rem; white-space: pre-wrap; }
                    table { width: 100%; border-collapse: collapse; }
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
                    <h1>🧭 TPipe DistributionGrid Execution Analysis</h1>
                    $tokenCard

                    ${buildDistributionGridSummary(trace)}

                    $stateRibbon

                    <div class="grid-section orchestration">
                        <h2>📊 Grid Orchestration Flow</h2>
                        <div class="mermaid">$mermaidGraph</div>
                    </div>

                    <div class="grid-section orchestration">
                        <h2>🎯 Routing, Handoff, && Decision Timeline</h2>
                        $orchestrationTable
                    </div>

                    <div class="grid-section activity">
                        <h2>🗂️ Discovery, Registry, && Public Listing Activity</h2>
                        $activityTable
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
     * Generates Splitter-specific HTML report with parallel pipeline visualization.
     */
    private fun generateSplitterHtmlReport(trace: List<TraceEvent>): String
    {
        val nodes = TraceNodeMapper.mapEventsToNodes(trace)
        val mermaidGraph = generateMermaidFlowGraph(trace, nodes)
        val tokenCard = buildContainerTokenCard(trace)
        val detailsTable = generateDetailsTable(trace)
        val javascript = TraceInteractivity.generateJavaScript(nodes)

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>TPipe Splitter Execution Flow</title>
                <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
                <style>
                    body { font-family: "Segoe UI", Tahoma, Geneva, Verdana, sans-serif; margin: 20px; background: #f5f5f5; }
                    .container { max-width: 1400px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    h1 { color: #333; text-align: center; margin-bottom: 30px; }
                    h1 span { color: #6366f1; }
                    .flow-section { margin-bottom: 40px; }
                    .details-section { margin-top: 40px; }
                    .instruction { text-align: center; color: #666; font-style: italic; margin-bottom: 20px; }
                    table { border-collapse: collapse; width: 100%; margin-top: 20px; }
                    th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
                    th { background-color: #6366f1; color: white; font-weight: 600; }
                    tr:nth-child(even) { background-color: #f8f9fa; }
                    .mermaid { text-align: center; background: white; padding: 20px; border-radius: 8px; }
                    .trace-item.highlighted { background-color: #fff3cd !important; border-left: 4px solid #ffc107; }
                    .flash-highlight { animation: flashEffect 2s ease-in-out; }
                    @keyframes flashEffect { 0%, 100% { background-color: inherit; } 50% { background-color: #ffeb3b; } }
                    .trace-item { transition: background-color 0.3s ease; cursor: pointer; }
                    .trace-item:hover { background-color: #f8f9fa; }
                    #trace-details-table { scroll-margin-top: 20px; }
                    .node rect { cursor: pointer; transition: stroke-width 0.2s ease; }
                    .node:hover rect { stroke-width: 3px !important; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1><span>&#9660;</span> TPipe Splitter Execution Flow</h1>
                    $tokenCard

                    <div class="flow-section">
                        <h2>&#128202; Interactive Flow Graph</h2>
                        <p class="instruction">&#128161; Click on any node to jump to its events in the table below</p>
                        <div class="mermaid">$mermaidGraph</div>
                    </div>

                    <div class="details-section">
                        <h2>&#128203; Execution Details</h2>
                        $detailsTable
                    </div>
                </div>

                $javascript
            </body>
            </html>
        """.trimIndent()
    }

    //======================================================================================================
    // PumpStation Custom Report
    //
    // Designed from the ground up for the harness's turn-state-machine nature. NOT a copy of any
    // other container's report. Vertical scroll of turn cards, each with a horizontal phase ribbon
    // showing the per-turn progression: HealthCheck → Judge → Dispatch → Path → Foreground →
    // Background → Memory → Compaction. Background/parallel activity is interleaved chronologically
    // within each turn card with a distinct visual marker. State ribbon at top shows 6 KPIs.
    //======================================================================================================

    /**
     * Derive the harness's overall status from a PumpStation trace. Returns "completed" if the trace
     * ends with PUMP_STATION_COMPLETED, "failed" if it ends with PUMP_STATION_FAILED, "suspended"
     * if the last signal is PUMP_STATION_SUSPENDED, and "running" otherwise (trace still open).
     */
    private fun derivePumpStationStatus(trace: List<TraceEvent>): String
    {
        val lastLifecycle = trace.lastOrNull { event ->
            event.eventType in listOf(
                TraceEventType.PUMP_STATION_COMPLETED,
                TraceEventType.PUMP_STATION_FAILED,
                TraceEventType.PUMP_STATION_SUSPENDED
            )
        } ?: return "running"
        return when (lastLifecycle.eventType)
        {
            TraceEventType.PUMP_STATION_COMPLETED -> "completed"
            TraceEventType.PUMP_STATION_FAILED -> "failed"
            TraceEventType.PUMP_STATION_SUSPENDED -> "suspended"
            else -> "running"
        }
    }

    /**
     * Compute the harness's total wall-clock duration from the first to the last event timestamp.
     * Returns the duration in milliseconds. Zero when the trace is empty.
     */
    private fun pumpStationDurationMs(trace: List<TraceEvent>): Long
    {
        if (trace.isEmpty()) return 0L
        val first = trace.first().timestamp
        val last = trace.last().timestamp
        return (last - first).coerceAtLeast(0L)
    }

    /**
     * Build the six state-ribbon KPI cards. Each card shows: turn counter, goal state, memory
     * fill ratio, paths visible/hidden, error count, lorebook size.
     */
    private fun buildPumpStationStateRibbon(trace: List<TraceEvent>): String
    {
        val distinctTurns = trace.mapNotNull { it.metadata["turnIndex"]?.toString()?.toIntOrNull() }.distinct().sorted()
        val turnCounter = if (distinctTurns.isEmpty()) "0" else "${distinctTurns.size}"

        val goalEvents = trace.filter { it.eventType == TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED }
        val goalPassed = goalEvents.count { it.metadata["passed"] == true }
        val goalFailed = goalEvents.size - goalPassed
        val goalCard = when
        {
            goalFailed > 0 -> "<span class='ps-kpi-value ps-status-failed'>✗ $goalFailed fail</span><span class='ps-kpi-sub'>${goalPassed} passed</span>"
            goalPassed > 0 -> "<span class='ps-kpi-value ps-status-completed'>✓ Pass</span><span class='ps-kpi-sub'>${goalPassed} goal checks</span>"
            else -> "<span class='ps-kpi-value'>—</span><span class='ps-kpi-sub'>no goal</span>"
        }

        val lastMemUpdate = trace.lastOrNull { it.eventType == TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED }
        val memPercent = (lastMemUpdate?.metadata?.get("compactionPercent") as? Number)?.toDouble()
        val memCard = if (memPercent != null)
        {
            val pct = (memPercent * 100).toInt().coerceIn(0, 100)
            "<span class='ps-kpi-value'>${pct}%</span>" +
                "<div class='ps-mem-bar'><div class='ps-mem-bar-fill' style='width:${pct}%'></div></div>"
        }
        else
        {
            "<span class='ps-kpi-value'>—</span><span class='ps-kpi-sub'>no mem updates</span>"
        }

        val selectedPaths = trace.filter { it.eventType == TraceEventType.PUMP_STATION_PATH_SELECTED }
            .mapNotNull { it.metadata["pathName"]?.toString() }.toSet()
        val hiddenPaths = trace.filter { it.eventType == TraceEventType.PUMP_STATION_PATH_HIDDEN }
            .mapNotNull { it.metadata["pathName"]?.toString() }.toSet()
        val visibleCount = (selectedPaths - hiddenPaths).size
        val hiddenCount = hiddenPaths.size
        val pathsCard = "<span class='ps-kpi-value'>${visibleCount}/${selectedPaths.size}</span>" +
            "<span class='ps-kpi-sub'>${hiddenCount} hidden</span>"

        val errorEvents = trace.filter {
            it.eventType == TraceEventType.PUMP_STATION_FAILED ||
            it.eventType == TraceEventType.PUMP_STATION_PATH_FAILED ||
            it.eventType == TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED ||
            it.eventType == TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED
        }
        val errorCount = errorEvents.size
        val lastErrorType = errorEvents.lastOrNull()?.eventType?.name?.removePrefix("PUMP_STATION_") ?: "None"
        val errorsCard = "<span class='ps-kpi-value'>${errorCount}</span>" +
            "<span class='ps-kpi-sub'>${lastErrorType}</span>"

        val loreCard = "<span class='ps-kpi-value'>${goalPassed + goalFailed}</span>" +
            "<span class='ps-kpi-sub'>goal evals</span>"

        return """
            <div class="ps-ribbon">
                <div class="ps-ribbon-card"><h3>Turn</h3>${turnCounter}</div>
                <div class="ps-ribbon-card"><h3>Goal</h3>${goalCard}</div>
                <div class="ps-ribbon-card"><h3>Memory</h3>${memCard}</div>
                <div class="ps-ribbon-card"><h3>Paths</h3>${pathsCard}</div>
                <div class="ps-ribbon-card"><h3>Errors</h3>${errorsCard}</div>
                <div class="ps-ribbon-card"><h3>Goal Evals</h3>${loreCard}</div>
            </div>
        """.trimIndent()
    }

    /**
     * Build the SVG memory-pressure sparkline. One bar per turn, height proportional to fill ratio.
     * Threshold line drawn across the top of the chart at the configured blowout threshold.
     */
    private fun buildPumpStationSparkline(trace: List<TraceEvent>): String
    {
        val memByTurn = trace.filter { it.eventType == TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED }
            .associateBy(
                { it.metadata["turnIndex"]?.toString()?.toIntOrNull() ?: -1 },
                { (it.metadata["compactionPercent"] as? Number)?.toDouble() ?: 0.0 }
            )
        if (memByTurn.isEmpty()) return ""

        val sortedTurns = memByTurn.keys.sorted()
        val barWidth = 24
        val gap = 4
        val chartHeight = 80
        val chartWidth = sortedTurns.size * (barWidth + gap) + 20
        val threshold = 0.9

        val bars = sortedTurns.mapIndexed { index, turn ->
            val ratio = memByTurn[turn] ?: 0.0
            val barHeight = (ratio * chartHeight).toInt().coerceAtLeast(2)
            val x = 10 + index * (barWidth + gap)
            val y = chartHeight - barHeight + 10
            val color = when
            {
                ratio >= threshold -> "#ef4444"
                ratio >= 0.7 -> "#f59e0b"
                else -> "#10b981"
            }
            "<rect x='$x' y='$y' width='$barWidth' height='$barHeight' fill='$color' rx='2'>" +
                "<title>Turn $turn: ${(ratio * 100).toInt()}%</title></rect>" +
                "<text x='${x + barWidth / 2}' y='${chartHeight + 22}' font-size='9' fill='#64748b' text-anchor='middle'>$turn</text>"
        }.joinToString("")

        val thresholdY = chartHeight - (threshold * chartHeight).toInt() + 10
        val thresholdLine = "<line x1='0' y1='$thresholdY' x2='$chartWidth' y2='$thresholdY' " +
            "stroke='#ef4444' stroke-dasharray='4,3' stroke-width='1'/>" +
            "<text x='${chartWidth - 4}' y='${thresholdY - 4}' font-size='9' fill='#ef4444' text-anchor='end'>blowout ${(threshold * 100).toInt()}%</text>"

        return """
            <div class="ps-sparkline-section">
                <h2>Memory pressure by turn</h2>
                <div class="ps-sparkline-wrap">
                    <svg width="$chartWidth" height="${chartHeight + 30}" class="ps-sparkline">
                        $bars
                        $thresholdLine
                    </svg>
                </div>
            </div>
        """.trimIndent()
    }

    /**
     * Build the two-column path inventory. Active paths on the left (with call count, last risk,
     * last status), reserve paths on the right (revealed or not).
     */
    private fun buildPumpStationPathInventory(trace: List<TraceEvent>): String
    {
        val pathEvents = trace.filter {
            it.eventType in listOf(
                TraceEventType.PUMP_STATION_PATH_SELECTED,
                TraceEventType.PUMP_STATION_PATH_COMPLETED,
                TraceEventType.PUMP_STATION_PATH_FAILED,
                TraceEventType.PUMP_STATION_PATH_HIDDEN
            )
        }
        val activePaths = pathEvents
            .filter { it.eventType == TraceEventType.PUMP_STATION_PATH_SELECTED }
            .mapNotNull { it.metadata["pathName"]?.toString() }
            .toSet()
        val hiddenPaths = pathEvents
            .filter { it.eventType == TraceEventType.PUMP_STATION_PATH_HIDDEN }
            .mapNotNull { it.metadata["pathName"]?.toString() }
            .toSet()
        val reserveReveals = trace.filter { it.eventType == TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED }
            .mapNotNull { it.metadata["pathName"]?.toString() }.toSet()

        val activeCards = activePaths.map { pathName ->
            val calls = pathEvents.count {
                it.eventType == TraceEventType.PUMP_STATION_PATH_SELECTED &&
                it.metadata["pathName"]?.toString() == pathName
            }
            val lastRisk = pathEvents.lastOrNull {
                it.metadata["pathName"]?.toString() == pathName &&
                (it.eventType == TraceEventType.PUMP_STATION_PATH_SELECTED ||
                 it.eventType == TraceEventType.PUMP_STATION_PATH_STARTED)
            }?.metadata?.get("riskLevel")?.toString() ?: "?"
            val isHidden = pathName in hiddenPaths
            val statusClass = if (isHidden) "ps-path-hidden" else "ps-path-active"
            val riskClass = "ps-risk-${lastRisk.lowercase()}"
            "<div class='ps-path-card $statusClass'>" +
                "<div class='ps-path-name'>$pathName</div>" +
                "<div class='ps-path-meta'>" +
                "<span class='ps-badge $riskClass'>$lastRisk</span>" +
                "<span class='ps-call-count'>$calls calls</span>" +
                (if (isHidden) "<span class='ps-badge ps-hidden-badge'>HIDDEN</span>" else "") +
                "</div></div>"
        }.joinToString("")

        val reserveNames = reserveReveals.joinToString("<br>") { "✓ $it" }.ifEmpty { "<em>none revealed</em>" }

        return """
            <div class="ps-paths-section">
                <div class="ps-paths-col">
                    <h2>Active paths (${activePaths.size})</h2>
                    ${activeCards.ifEmpty { "<em>none</em>" }}
                </div>
                <div class="ps-paths-col">
                    <h2>Reserve reveals</h2>
                    <div class="ps-reserve-list">$reserveNames</div>
                </div>
            </div>
        """.trimIndent()
    }

    /**
     * Group events by turnIndex and produce one card per turn in chronological order. Each card
     * shows the phase ribbon, key facts (judge verdict, selected path, path outcome), the
     * background activity strip, and an expandable details panel with all per-event content.
     */
    private fun buildPumpStationTurnTimeline(trace: List<TraceEvent>): String
    {
        val byTurn = trace.groupBy { it.metadata["turnIndex"]?.toString()?.toIntOrNull() ?: -1 }
            .filterKeys { it >= 0 }
            .toSortedMap()
        if (byTurn.isEmpty()) return "<div class='ps-empty'>No turn-keyed events found in trace.</div>"

        val cards = byTurn.entries.mapIndexed { index, (turn, events) ->
            buildPumpStationTurnCard(turn, events, index == 0)
        }
        return """
            <div class="ps-turns-section">
                <h2>Turn timeline (${byTurn.size} turns)</h2>
                ${cards.joinToString("\n")}
            </div>
        """.trimIndent()
    }

    /**
     * Build a single turn card. The phase ribbon shows which phases fired and their outcome.
     * The key-facts line shows the most important facts (judge verdict, path call, etc). The
     * background activity strip interleaves async events. The details panel is expandable.
     */
    private fun buildPumpStationTurnCard(turnIndex: Int, events: List<TraceEvent>, expanded: Boolean): String
    {
        val phasePills = buildPumpStationPhaseRibbon(events)
        val keyFacts = buildPumpStationKeyFacts(events)
        val backgroundStrip = buildPumpStationBackgroundStrip(events)
        val details = buildPumpStationTurnDetails(events)
        val tokenSummary = buildPumpStationTokenSummary(events)
        val nestedP2P = buildPumpStationNestedP2PBlock(events)
        val openAttr = if (expanded) " open" else ""

        return """
            <details class="ps-turn-card"$openAttr>
                <summary class="ps-turn-summary">
                    <span class="ps-turn-num">Turn $turnIndex</span>
                    <span class="ps-turn-phases">$phasePills</span>
                </summary>
                <div class="ps-turn-body">
                    <div class="ps-turn-facts">$keyFacts</div>
                    $tokenSummary
                    <div class="ps-turn-bg">$backgroundStrip</div>
                    $nestedP2P
                    <div class="ps-turn-details">$details</div>
                </div>
            </details>
        """.trimIndent()
    }

    /**
     * Build the horizontal phase ribbon: pills for each phase that fired this turn, color-coded by
     * success/failure/info. Each pill's label is the short event name (e.g. "Judge✓", "Path→search").
     */
    private fun buildPumpStationPhaseRibbon(events: List<TraceEvent>): String
    {
        val phaseEvents = events.filter {
            it.eventType.name.startsWith("PUMP_STATION_") &&
            it.eventType !in listOf(
                TraceEventType.PUMP_STATION_STARTED,
                TraceEventType.PUMP_STATION_COMPLETED,
                TraceEventType.PUMP_STATION_FAILED,
                TraceEventType.PUMP_STATION_SUSPENDED,
                TraceEventType.PUMP_STATION_RESUMED
            ) && !it.eventType.name.contains("BACKGROUND") && !it.eventType.name.contains("STASH") &&
            !it.eventType.name.contains("BLOWOUT") && !it.eventType.name.contains("RESERVE")
        }
        return phaseEvents.joinToString(" ") { event ->
            val label = phaseShortName(event.eventType)
            val detail = event.metadata["selectedPathName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: event.metadata["pathName"]?.toString()?.takeIf { it.isNotBlank() } ?: ""
            val fullLabel = if (detail.isNotBlank()) "$label→$detail" else label
            val statusClass = when
            {
                event.eventType.name.contains("FAILED") || event.eventType.name.contains("TRIPPED") -> "ps-phase-failed"
                event.eventType.name.contains("COMPLETED") -> "ps-phase-success"
                else -> "ps-phase-info"
            }
            val pillContent = "<span class='ps-phase-pill $statusClass'>$fullLabel</span>"
            // SafePrune events get an interactive hover popup carrying the full report.
            val popupAttr = when (event.eventType)
            {
                TraceEventType.PUMP_STATION_SAFE_PRUNE_APPLIED -> "true"
                TraceEventType.PUMP_STATION_SAFE_PRUNE_DRY_RUN_COMPLETED -> "dry-run"
                else -> null
            }
            if (popupAttr != null)
            {
                val originalCount = event.metadata["originalCount"] ?: "n/a"
                val finalCount = event.metadata["finalCount"] ?: "n/a"
                val tokensRemoved = event.metadata["tokensRemoved"] ?: "n/a"
                val enabledFlags = event.metadata["enabledFlags"] ?: "n/a"
                val title = if (popupAttr == "dry-run") "SafePrune Dry-Run Report" else "SafePrune Applied Report"
                "<span class='ps-phase-wrap' tabindex='0'>$pillContent<span class='ps-safe-prune-popup'><strong>$title</strong><dl>" +
                    "<dt>Original</dt><dd>$originalCount entries</dd>" +
                    "<dt>Final</dt><dd>$finalCount entries</dd>" +
                    "<dt>Tokens removed</dt><dd>$tokensRemoved</dd>" +
                    "<dt>Strategies</dt><dd>$enabledFlags</dd>" +
                    "</dl></span></span>"
            }
            else
            {
                pillContent
            }
        }
    }

    /**
     * Build the "key facts" line for a turn. Surfaces the most important events: judge verdict,
     * dispatch result, path call summary, intervention outcome, stash events, and errors.
     */
    private fun buildPumpStationKeyFacts(events: List<TraceEvent>): String
    {
        val facts = mutableListOf<String>()

        events.firstOrNull { it.eventType == TraceEventType.PUMP_STATION_JUDGE_COMPLETED }?.let { e ->
            val complete = e.metadata["isComplete"] ?: "?"
            val term = e.metadata["shouldTerminate"] ?: "?"
            val cls = if (complete == true) "ps-fact-success" else "ps-fact-info"
            facts += "<div class='ps-fact $cls'>" +
                "<span class='ps-fact-label'>Judge:</span>" +
                "<span class='ps-fact-value'>isComplete=$complete, terminate=$term</span></div>"
        }

        events.firstOrNull { it.eventType == TraceEventType.PUMP_STATION_DISPATCH_COMPLETED }?.let { e ->
            val path = e.metadata["selectedPathName"]?.toString()?.ifBlank { "(none)" } ?: "(none)"
            facts += "<div class='ps-fact ps-fact-info'>" +
                "<span class='ps-fact-label'>Dispatch:</span>" +
                "<span class='ps-fact-value'>→ $path</span></div>"
        }

        events.firstOrNull { it.eventType == TraceEventType.PUMP_STATION_PATH_COMPLETED }?.let { e ->
            val name = e.metadata["pathName"] ?: "?"
            val risk = e.metadata["riskLevel"] ?: "?"
            facts += "<div class='ps-fact ps-fact-success'>" +
                "<span class='ps-fact-label'>Path ✓</span>" +
                "<span class='ps-fact-value'>$name [$risk]</span></div>"
        }

        events.firstOrNull { it.eventType == TraceEventType.PUMP_STATION_PATH_FAILED }?.let { e ->
            val name = e.metadata["pathName"] ?: "?"
            val err = e.metadata["errorMessage"] ?: ""
            facts += "<div class='ps-fact ps-fact-failed'>" +
                "<span class='ps-fact-label'>Path ✗</span>" +
                "<span class='ps-fact-value'>$name — $err</span></div>"
        }

        events.firstOrNull { it.eventType == TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED }?.let { e ->
            val guard = e.metadata["guard"] ?: "?"
            val path = e.metadata["pathName"] ?: "?"
            facts += "<div class='ps-fact ps-fact-warning'>" +
                "<span class='ps-fact-label'>⚠ Loop guard</span>" +
                "<span class='ps-fact-value'>$guard on $path</span></div>"
        }

        return if (facts.isEmpty()) "<em>No key facts recorded for this turn.</em>"
        else facts.joinToString("")
    }

    /**
     * Build the background-activity strip. Lists async/background events that fired this turn
     * (memory updates, foreground agents, background agents, intervention, stash, blowouts, compactions)
     * with a distinct background color so they don't visually merge with the main flow.
     */
    private fun buildPumpStationBackgroundStrip(events: List<TraceEvent>): String
    {
        val backgroundEvents = events.filter {
            it.eventType in listOf(
                TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED,
                TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED,
                TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED,
                TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED,
                TraceEventType.PUMP_STATION_COMPACTION_STARTED,
                TraceEventType.PUMP_STATION_COMPACTION_COMPLETED,
                TraceEventType.PUMP_STATION_INTERVENTION_STARTED,
                TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED,
                TraceEventType.PUMP_STATION_STASH_CREATED,
                TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED,
                TraceEventType.PUMP_STATION_HEALTH_CHECK_STARTED,
                TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED
            )
        }
        if (backgroundEvents.isEmpty()) return ""

        val pills = backgroundEvents.joinToString(" ") { event ->
            val label = phaseShortName(event.eventType)
            val detail = when (event.eventType)
            {
                TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED,
                TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED ->
                    event.metadata["agentName"]?.toString() ?: ""
                TraceEventType.PUMP_STATION_STASH_CREATED ->
                    event.metadata["stashId"]?.toString() ?: ""
                TraceEventType.PUMP_STATION_INTERVENTION_STARTED,
                TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED ->
                    event.metadata["pathName"]?.toString() ?: ""
                TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED ->
                    "status=${event.metadata["status"] ?: "?"}"
                else -> ""
            }
            val fullLabel = if (detail.isNotBlank()) "$label $detail" else label
            val statusClass = when
            {
                event.eventType == TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED -> "ps-bg-blowout"
                event.eventType == TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED -> "ps-bg-warning"
                event.eventType.name.contains("FAILED") -> "ps-bg-failed"
                else -> "ps-bg-info"
            }
            "<span class='ps-bg-pill $statusClass'>$fullLabel</span>"
        }

        return "<div class='ps-bg-strip'><span class='ps-bg-label'>Background:</span> $pills</div>"
    }

    /**
     * Build the expandable details panel for a turn card. Lists each event with its full
     * metadata as key/value rows, indented under the event label. Content-bearing events
     * (Path / Judge / Dispatch / ForegroundAgent / Intervention / NestedP2P) get an
     * additional [buildPumpStationEventExtras] block that surfaces the agent's text
     * content, model reasoning, and token-usage chips inside a `<details>` toggle.
     */
    private fun buildPumpStationTurnDetails(events: List<TraceEvent>): String
    {
        val rows = events.joinToString("") { event ->
            val label = phaseShortName(event.eventType)
            val metaRows = event.metadata.entries
                .filter { it.key != "turnIndex" && it.key != "phase" && it.key != "runId" }
                .joinToString("") { (k, v) ->
                    "<div class='ps-meta-row'><span class='ps-meta-key'>$k:</span>" +
                        "<span class='ps-meta-val'>${escapeHtml(v.toString())}</span></div>"
                }
            val extras = buildPumpStationEventExtras(event)
            "<div class='ps-detail-row'>" +
                "<div class='ps-detail-label'>$label <span class='ps-detail-type'>(${event.eventType.name})</span></div>" +
                "<div class='ps-detail-meta'>$metaRows</div>" +
                extras +
                "</div>"
        }
        return "<div class='ps-details-table'>$rows</div>"
    }

    /**
     * Build the per-turn content / token-extras block. Returns a `<details>` toggle for any
     * content-bearing event (Path / Judge / Dispatch / ForegroundAgent / Intervention /
     * NestedP2P) so the harness agent's [MultimodalContent] is visible without dumping it
     * into the inline metadata stream. The block contains:
     *  - a row of token-usage chips ("in 1,240" / "out 412" / "total 1,652"), only when at
     *    least one token field is non-null
     *  - a `<pre>` block with the agent's text content (HTML-escaped to defang XSS)
     *  - a nested `<details>` for model reasoning when present
     *  - a "binary content (N items)" note when binary content is present
     *
     * Returns an empty string for events that do not carry a content payload.
     */
    private fun buildPumpStationEventExtras(event: TraceEvent): String
    {
        if (!isPumpStationContentEvent(event.eventType)) return ""
        val meta = event.metadata
        val contentPreview = meta["contentPreview"]?.toString()
        val contentLength = meta["contentLength"]?.toString()?.toIntOrNull() ?: 0
        val modelReasoning = meta["modelReasoning"]?.toString()
        val modelReasoningLen = meta["modelReasoningLen"]?.toString()?.toIntOrNull() ?: 0
        val binaryCount = meta["binaryCount"]?.toString()?.toIntOrNull() ?: 0
        val inputTokens = readTokenField(meta, "inputTokens")
        val outputTokens = readTokenField(meta, "outputTokens")
        val totalTokens = readTokenField(meta, "totalTokens")
        val hasTokens = inputTokens != null || outputTokens != null || totalTokens != null
        val hasContent = !contentPreview.isNullOrEmpty() || !modelReasoning.isNullOrEmpty() || binaryCount > 0
        if (!hasTokens && !hasContent) return ""

        val summary = "View content" + when
        {
            contentLength > 0 && modelReasoningLen > 0 -> " (${formatCount(contentLength)} chars + ${formatCount(modelReasoningLen)} reasoning)"
            contentLength > 0 -> " (${formatCount(contentLength)} chars)"
            modelReasoningLen > 0 -> " (${formatCount(modelReasoningLen)} reasoning chars)"
            binaryCount > 0 -> " ($binaryCount binary item(s))"
            else -> ""
        }

        val tokenChips = if (hasTokens) buildTokenChips(inputTokens, outputTokens, totalTokens) else ""
        val textBlock = if (!contentPreview.isNullOrEmpty()) buildTextBlock(contentPreview) else ""
        val reasoningBlock = if (!modelReasoning.isNullOrEmpty()) buildReasoningBlock(modelReasoning, modelReasoningLen) else ""
        val binaryNote = if (binaryCount > 0)
            "<div class='ps-binary-note'>binary content: $binaryCount item(s) (descriptors only — not inlined)</div>"
        else ""

        return """
            <details class="ps-event-extras">
                <summary class="ps-event-extras-summary">$summary</summary>
                <div class="ps-event-extras-body">
                    $tokenChips
                    $textBlock
                    $reasoningBlock
                    $binaryNote
                </div>
            </details>
        """.trimIndent()
    }

    /**
     * Build the per-turn nested P2P block. Surfaces all [PUMP_STATION_NESTED_P2P_COMPLETED]
     * events for the turn as a numbered sub-list with the same per-event extras treatment.
     * Returns an empty string when no nested P2P calls fired in the turn.
     */
    private fun buildPumpStationNestedP2PBlock(events: List<TraceEvent>): String
    {
        val nested = events.filter { it.eventType == TraceEventType.PUMP_STATION_NESTED_P2P_COMPLETED }
        if (nested.isEmpty()) return ""
        val rows = nested.mapIndexed { idx, ev ->
            val path = ev.metadata["pathName"]?.toString().orEmpty()
            val agent = ev.metadata["agentName"]?.toString() ?: "(unknown)"
            val label = if (path.isNotEmpty()) "path=$path, agent=$agent" else "agent=$agent"
            "<li class='ps-nested-p2p-item'>" +
                "<div class='ps-nested-p2p-label'>${idx + 1}. $label</div>" +
                buildPumpStationEventExtras(ev) +
                "</li>"
        }.joinToString("")
        return """
            <div class="ps-nested-p2p">
                <div class="ps-nested-p2p-header">Nested P2P calls (${nested.size})</div>
                <ol class="ps-nested-p2p-list">$rows</ol>
            </div>
        """.trimIndent()
    }

    /**
     * Build the per-turn token summary row. Sums input/output/total across the turn's
     * content-bearing events and renders three aggregate pills. Returns an empty string
     * when no content-bearing event in the turn carries any token data.
     */
    private fun buildPumpStationTokenSummary(events: List<TraceEvent>): String
    {
        var input = 0; var output = 0; var total = 0
        var sawAny = false
        for (e in events)
        {
            if (!isPumpStationContentEvent(e.eventType)) continue
            val inT = readTokenField(e.metadata, "inputTokens")
            val outT = readTokenField(e.metadata, "outputTokens")
            val totT = readTokenField(e.metadata, "totalTokens")
            if (inT != null || outT != null || totT != null) sawAny = true
            if (inT != null) input += inT
            if (outT != null) output += outT
            if (totT != null) total += totT
        }
        if (!sawAny) return ""
        val inputStr = if (input > 0) formatCount(input) else "—"
        val outputStr = if (output > 0) formatCount(output) else "—"
        val totalStr = if (total > 0) formatCount(total) else "—"
        return """
            <div class="ps-token-summary">
                <span class="ps-token-summary-label">Turn tokens:</span>
                <span class="ps-token-chip ps-token-chip-in">in $inputStr</span>
                <span class="ps-token-chip ps-token-chip-out">out $outputStr</span>
                <span class="ps-token-chip ps-token-chip-total">total $totalStr</span>
            </div>
        """.trimIndent()
    }

    /**
     * Build the trace-wide token totals card for the report header. Sums input/output across
     * every event that carries them in metadata, SKIPPING KILLSWITCH_CHECK (which reports
     * cumulative-AT-check-time, not actual spend — the underlying JUDGE_COMPLETED /
     * DISPATCH_COMPLETED / PATH_COMPLETED events already account for that ground). Returns
     * null when no event in the trace carries any token metadata, so short traces don't show
     * a misleading "0 tokens" card.
     *
     * Uses Long for the sums because a long-running harness can blow past Int.MAX_VALUE
     * (the per-event values are Int; the aggregate is the concern).
     *
     * Shared across all five container HTML reports (PumpStation, Manifold, Junction,
     * Splitter, DistributionGrid) — the visualizer previously had a per-container
     * `buildPumpStationTokenCard` only. Now lifted here with the same shape.
     */
    private fun buildContainerTokenCard(trace: List<TraceEvent>): String?
    {
        var input = 0L
        var output = 0L
        var counted = 0
        for (event in trace)
        {
            // Skip KILLSWITCH_CHECK — every check duplicates prior spend.
            if (event.eventType == TraceEventType.KILLSWITCH_CHECK) continue
            val inMeta = event.metadata["inputTokens"]?.toString()?.toLongOrNull()
            val outMeta = event.metadata["outputTokens"]?.toString()?.toLongOrNull()
            if (inMeta != null || outMeta != null) counted++
            if (inMeta != null) input += inMeta
            if (outMeta != null) output += outMeta
        }
        if (counted == 0) return null
        return """
            <span class="trace-token-card">
                <span class="trace-token-card-label">TOKEN TOTALS</span>
                <span class="trace-token-input">Input: ${"%,d".format(input)}</span>
                <span class="trace-token-output">Output: ${"%,d".format(output)}</span>
                <span class="trace-token-sum">Total: ${"%,d".format(input + output)}</span>
                <span class="trace-token-events">Events w/ tokens: $counted / ${trace.size}</span>
            </span>
        """.trimIndent()
    }

    /**
     * Render the three small token-usage pills used by both per-event extras and the per-turn
     * summary. Pills whose value is null are omitted.
     */
    private fun buildTokenChips(input: Int?, output: Int?, total: Int?): String
    {
        val chips = mutableListOf<String>()
        if (input != null) chips += "<span class='ps-token-chip ps-token-chip-in'>in ${formatCount(input)}</span>"
        if (output != null) chips += "<span class='ps-token-chip ps-token-chip-out'>out ${formatCount(output)}</span>"
        if (total != null) chips += "<span class='ps-token-chip ps-token-chip-total'>total ${formatCount(total)}</span>"
        if (chips.isEmpty()) return ""
        return "<div class='ps-token-row'>${chips.joinToString("")}</div>"
    }

    /**
     * Render the text content block. Escapes HTML to defang XSS. When the preview is shorter
     * than the recorded length, surfaces a "(truncated; full content in trace JSON)" hint so
     * developers know there is more in the raw trace.
     */
    private fun buildTextBlock(preview: String): String
    {
        val escaped = escapeHtml(preview)
        val truncationHint = if (preview.endsWith("...")) "<div class='ps-text-truncated'>truncated for preview — see raw trace for full content</div>" else ""
        return "<pre class='ps-event-text'>$escaped</pre>$truncationHint"
    }

    /**
     * Render the model-reasoning nested block when the agent produced reasoning text.
     */
    private fun buildReasoningBlock(reasoning: String, totalLen: Int): String
    {
        val escaped = escapeHtml(reasoning)
        val summary = if (totalLen > reasoning.length)
            "Model reasoning (${formatCount(reasoning.length)} of ${formatCount(totalLen)} chars shown)"
        else
            "Model reasoning (${formatCount(totalLen)} chars)"
        val truncationHint = if (reasoning.endsWith("...")) "<div class='ps-text-truncated'>truncated for preview</div>" else ""
        return "<details class='ps-event-reasoning'><summary class='ps-event-reasoning-summary'>$summary</summary><pre class='ps-event-text ps-event-reasoning-text'>$escaped</pre>$truncationHint</details>"
    }

    /**
     * True if the event type carries a [MultimodalContent] payload worth rendering as a
     * collapsible extras block.
     */
    private fun isPumpStationContentEvent(type: TraceEventType): Boolean
    {
        return when (type)
        {
            TraceEventType.PUMP_STATION_PATH_COMPLETED,
            TraceEventType.PUMP_STATION_JUDGE_COMPLETED,
            TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
            TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED,
            TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED,
            TraceEventType.PUMP_STATION_NESTED_P2P_COMPLETED -> true
            else -> false
        }
    }

    /**
     * Read a token field from the metadata map. Sentinel value -1 means "not tracked" (the
     * funnel writes -1 when the upstream event had a null token field). Returns null in that
     * case so the visualizer can hide the chip rather than show a misleading zero.
     */
    private fun readTokenField(meta: Map<String, Any>, key: String): Int?
    {
        val raw = meta[key]?.toString()?.toIntOrNull() ?: return null
        return if (raw < 0) null else raw
    }

    /**
     * Format a token count with thousands separators.
     */
    private fun formatCount(n: Int): String
    {
        if (n < 1000) return n.toString()
        return "%,d".format(n)
    }

    /**
     * Build the outcome panel: status, exit reason, last successful turn, failure summary.
     */
    private fun buildPumpStationOutcomePanel(trace: List<TraceEvent>, status: String): String
    {
        val distinctTurns = trace.mapNotNull { it.metadata["turnIndex"]?.toString()?.toIntOrNull() }.distinct().sorted()
        val lastTurn = distinctTurns.lastOrNull() ?: 0
        val totalEvents = trace.size

        val failedEvent = trace.lastOrNull { it.eventType == TraceEventType.PUMP_STATION_FAILED }
        val exitReason = failedEvent?.metadata?.get("exitReason")?.toString() ?: "—"
        val lastError = failedEvent?.metadata?.get("errorMessage")?.toString() ?: "—"
        val errorType = failedEvent?.metadata?.get("error")?.toString() ?: "—"

        val lastSuccess = trace.lastOrNull { it.eventType == TraceEventType.PUMP_STATION_PATH_COMPLETED }
        val lastSuccessPath = lastSuccess?.metadata?.get("pathName")?.toString() ?: "—"

        return """
            <div class="ps-outcome">
                <h2>Outcome</h2>
                <div class="ps-outcome-grid">
                    <div><span class="ps-outcome-label">Status:</span> <span class="ps-status-$status">${status.uppercase()}</span></div>
                    <div><span class="ps-outcome-label">Last turn:</span> $lastTurn</div>
                    <div><span class="ps-outcome-label">Total events:</span> $totalEvents</div>
                    <div><span class="ps-outcome-label">Exit reason:</span> $exitReason</div>
                    <div><span class="ps-outcome-label">Error type:</span> $errorType</div>
                    <div><span class="ps-outcome-label">Last error:</span> ${escapeHtml(lastError)}</div>
                    <div><span class="ps-outcome-label">Last successful path:</span> $lastSuccessPath</div>
                </div>
            </div>
        """.trimIndent()
    }

    /**
     * CSS for the PumpStation report. Reuses the visual language of other TPipe reports
     * (rounded cards, subtle shadows, indigo accents) while introducing custom classes for
     * turn cards, phase ribbons, and the memory bar.
     */
    private fun generatePumpStationCSS(): String = """
        body { font-family: 'Segoe UI', Tahoma, sans-serif; margin: 24px; background: #f1f5f9; color: #1e293b; }
        .ps-container { max-width: 1200px; margin: 0 auto; background: white; padding: 28px; border-radius: 14px; box-shadow: 0 22px 50px rgba(15,23,42,0.16); }
        .ps-header { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; margin-bottom: 24px; padding-bottom: 18px; border-bottom: 1px solid #e2e8f0; }
        .ps-title { font-size: 1.4rem; font-weight: 700; color: #0f172a; }
        .ps-status { padding: 6px 14px; border-radius: 999px; font-weight: 600; font-size: 0.85rem; }
        .ps-status-completed { background: rgba(220,252,231,0.9); color: #166534; }
        .ps-status-failed { background: rgba(254,226,226,0.9); color: #991b1b; }
        .ps-status-suspended { background: rgba(254,243,199,0.9); color: #92400e; }
        .ps-status-running { background: rgba(224,231,255,0.95); color: #3730a3; }
        .ps-run-id { font-family: 'JetBrains Mono', monospace; color: #64748b; font-size: 0.85rem; }
        .ps-duration { margin-left: auto; color: #64748b; font-size: 0.9rem; }
        .ps-ribbon { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; margin-bottom: 28px; }
        .ps-ribbon-card { background: linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%); border: 1px solid rgba(99,102,241,0.18); border-radius: 12px; padding: 14px 16px; }
        .ps-ribbon-card h3 { margin: 0 0 8px; font-size: 0.72rem; letter-spacing: 0.12em; color: #475569; text-transform: uppercase; }
        .ps-kpi-value { display: block; font-size: 1.5rem; font-weight: 600; color: #0f172a; }
        .ps-kpi-sub { display: block; font-size: 0.8rem; color: #64748b; margin-top: 4px; }
        .ps-mem-bar { height: 6px; background: rgba(99,102,241,0.15); border-radius: 999px; margin-top: 6px; overflow: hidden; }
        .ps-mem-bar-fill { height: 100%; background: linear-gradient(90deg, #10b981, #6366f1); }
        .ps-sparkline-section { margin-bottom: 28px; padding: 18px; background: #f8fafc; border-radius: 12px; }
        .ps-sparkline-section h2 { margin-top: 0; font-size: 1rem; color: #475569; }
        .ps-sparkline-wrap { overflow-x: auto; }
        .ps-paths-section { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 28px; }
        .ps-paths-col { padding: 16px; background: #f8fafc; border-radius: 12px; }
        .ps-paths-col h2 { margin-top: 0; font-size: 0.95rem; color: #475569; }
        .ps-path-card { padding: 10px 12px; background: white; border-radius: 8px; border: 1px solid #e2e8f0; margin-bottom: 8px; }
        .ps-path-hidden { opacity: 0.6; }
        .ps-path-name { font-weight: 600; font-size: 0.9rem; }
        .ps-path-meta { display: flex; gap: 8px; margin-top: 6px; flex-wrap: wrap; }
        .ps-badge { padding: 2px 8px; border-radius: 999px; font-size: 0.7rem; font-weight: 600; }
        .ps-risk-low { background: rgba(220,252,231,0.9); color: #166534; }
        .ps-risk-medium { background: rgba(254,243,199,0.9); color: #92400e; }
        .ps-risk-high { background: rgba(254,226,226,0.9); color: #991b1b; }
        .ps-hidden-badge { background: #1e293b; color: white; }
        .ps-call-count { font-size: 0.75rem; color: #64748b; }
        .ps-reserve-list { font-size: 0.85rem; color: #475569; }
        .ps-turns-section { margin-bottom: 28px; }
        .ps-turns-section h2 { font-size: 1.05rem; color: #1e293b; margin-bottom: 14px; }
        .ps-turn-card { background: white; border: 1px solid #e2e8f0; border-radius: 12px; margin-bottom: 12px; overflow: hidden; }
        .ps-turn-card[open] { box-shadow: 0 8px 18px rgba(15,23,42,0.08); }
        .ps-turn-summary { cursor: pointer; padding: 14px 18px; display: flex; align-items: center; gap: 16px; background: #f8fafc; list-style: none; }
        .ps-turn-summary::-webkit-details-marker { display: none; }
        .ps-turn-num { font-weight: 700; color: #1e293b; font-size: 0.95rem; }
        .ps-turn-phases { display: flex; gap: 6px; flex-wrap: wrap; flex: 1; }
        .ps-phase-pill { padding: 3px 10px; border-radius: 999px; font-size: 0.75rem; font-weight: 500; }
        .ps-phase-success { background: rgba(220,252,231,0.9); color: #166534; }
        .ps-phase-failed { background: rgba(254,226,226,0.9); color: #991b1b; }
        .ps-phase-info { background: rgba(224,231,255,0.95); color: #3730a3; }
        /* SafePrune hover popup — surfaces the full SafePruneReport payload on hover/focus. */
        .ps-phase-wrap { position: relative; cursor: help; }
        .ps-phase-wrap .ps-safe-prune-popup { display: none; position: absolute; top: 100%; left: 0; margin-top: 6px; padding: 10px 12px; min-width: 240px; max-width: 360px; border-radius: 10px; background: #0f172a; color: #e2e8f0; font-size: 0.78rem; line-height: 1.45; box-shadow: 0 12px 28px rgba(15,23,42,0.45); z-index: 50; pointer-events: none; }
        .ps-phase-wrap:hover .ps-safe-prune-popup,
        .ps-phase-wrap:focus-within .ps-safe-prune-popup { display: block; }
        .ps-phase-wrap .ps-safe-prune-popup strong { display: block; font-size: 0.74rem; letter-spacing: 0.1em; color: #facc15; text-transform: uppercase; margin-bottom: 6px; }
        .ps-phase-wrap .ps-safe-prune-popup dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; }
        .ps-phase-wrap .ps-safe-prune-popup dt { color: #94a3b8; font-weight: 600; }
        .ps-phase-wrap .ps-safe-prune-popup dd { margin: 0; color: #f1f5f9; }
        .ps-turn-body { padding: 16px 18px; }
        .ps-turn-facts { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
        .ps-fact { padding: 8px 12px; border-radius: 8px; border-left: 3px solid; font-size: 0.88rem; }
        .ps-fact-success { background: rgba(220,252,231,0.4); border-color: #10b981; }
        .ps-fact-failed { background: rgba(254,226,226,0.4); border-color: #ef4444; }
        .ps-fact-warning { background: rgba(254,243,199,0.4); border-color: #f59e0b; }
        .ps-fact-info { background: rgba(241,245,249,0.6); border-color: #6366f1; }
        .ps-fact-label { font-weight: 600; color: #475569; margin-right: 8px; }
        .ps-turn-bg { padding: 10px 12px; background: #f1f5f9; border-radius: 8px; margin-bottom: 14px; }
        .ps-bg-label { font-size: 0.75rem; color: #64748b; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; margin-right: 8px; }
        .ps-bg-pill { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 0.72rem; margin-right: 4px; }
        .ps-bg-info { background: rgba(224,231,255,0.95); color: #3730a3; }
        .ps-bg-warning { background: rgba(254,243,199,0.9); color: #92400e; }
        .ps-bg-failed { background: rgba(254,226,226,0.9); color: #991b1b; }
        .ps-bg-blowout { background: #1e293b; color: white; }
        .ps-turn-details { background: #f8fafc; padding: 12px; border-radius: 8px; }
        .ps-detail-row { padding: 6px 0; border-bottom: 1px dashed #e2e8f0; }
        .ps-detail-row:last-child { border-bottom: none; }
        .ps-detail-label { font-weight: 600; font-size: 0.82rem; color: #1e293b; }
        .ps-detail-type { font-family: 'JetBrains Mono', monospace; font-size: 0.72rem; color: #64748b; }
        .ps-detail-meta { margin-top: 4px; padding-left: 12px; }
        .ps-meta-row { font-size: 0.78rem; }
        .ps-meta-key { color: #64748b; font-weight: 500; }
        .ps-meta-val { color: #0f172a; font-family: 'JetBrains Mono', monospace; font-size: 0.75rem; }
        .ps-outcome { background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%); color: white; padding: 22px; border-radius: 12px; }
        .ps-outcome h2 { margin-top: 0; font-size: 1.1rem; }
        .ps-outcome-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
        .ps-outcome-label { color: #94a3b8; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.08em; margin-right: 6px; }
        .ps-empty { padding: 20px; text-align: center; color: #94a3b8; font-style: italic; }
        /* Per-event content extras — collapsible block for path/judge/dispatch/FG/intervention/nested P2P */
        .ps-event-extras { margin-top: 8px; margin-bottom: 4px; background: white; border: 1px solid #e2e8f0; border-radius: 8px; }
        .ps-event-extras[open] { box-shadow: inset 0 0 0 1px rgba(99,102,241,0.18); }
        .ps-event-extras-summary { cursor: pointer; padding: 6px 12px; font-size: 0.78rem; color: #475569; list-style: none; }
        .ps-event-extras-summary::-webkit-details-marker { display: none; }
        .ps-event-extras-summary::before { content: "▶ "; font-size: 0.6rem; color: #6366f1; }
        .ps-event-extras[open] .ps-event-extras-summary::before { content: "▼ "; }
        .ps-event-extras-body { padding: 8px 12px 12px; border-top: 1px dashed #e2e8f0; }
        .ps-event-text { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 10px 12px; font-family: 'JetBrains Mono', monospace; font-size: 0.75rem; line-height: 1.5; color: #0f172a; max-height: 360px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
        .ps-text-truncated { font-size: 0.7rem; color: #94a3b8; font-style: italic; margin-top: 4px; }
        .ps-event-reasoning { margin-top: 6px; }
        .ps-event-reasoning-summary { cursor: pointer; padding: 4px 8px; font-size: 0.72rem; color: #6366f1; list-style: none; }
        .ps-event-reasoning-summary::-webkit-details-marker { display: none; }
        .ps-event-reasoning-text { background: #eef2ff; border-color: rgba(99,102,241,0.18); }
        .ps-binary-note { font-size: 0.72rem; color: #475569; margin-top: 6px; font-style: italic; }
        /* Token chip row — per-event usage */
        .ps-token-row { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
        .ps-token-chip { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 0.7rem; font-weight: 600; font-family: 'JetBrains Mono', monospace; }
        .ps-token-chip-in { background: rgba(220,252,231,0.9); color: #166534; }
        .ps-token-chip-out { background: rgba(254,243,199,0.9); color: #92400e; }
        .ps-token-chip-total { background: rgba(224,231,255,0.95); color: #3730a3; }
        /* Per-turn token summary row */
        .ps-token-summary { display: flex; gap: 8px; align-items: center; padding: 8px 12px; margin-bottom: 12px; background: linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%); border-radius: 8px; border: 1px solid rgba(99,102,241,0.18); flex-wrap: wrap; }
        .ps-token-summary-label { font-size: 0.72rem; color: #475569; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; margin-right: 4px; }
        /* Nested P2P block — sub-list of nested calls inside a path */
        .ps-nested-p2p { margin: 12px 0 14px; padding: 10px 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-left: 3px solid #6366f1; border-radius: 8px; }
        .ps-nested-p2p-header { font-size: 0.78rem; color: #3730a3; font-weight: 600; margin-bottom: 6px; }
        .ps-nested-p2p-list { list-style: decimal; margin: 0; padding-left: 22px; }
        .ps-nested-p2p-item { padding: 4px 0; }
        .ps-nested-p2p-label { font-size: 0.78rem; color: #1e293b; font-family: 'JetBrains Mono', monospace; }
        /* Trace-wide token totals card (header KPI row) - shared across all container HTML reports */
        .trace-token-card { display: inline-flex; align-items: center; gap: 14px; padding: 6px 14px; background: linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%); border: 1px solid rgba(99,102,241,0.32); border-radius: 999px; font-family: 'JetBrains Mono', monospace; font-size: 0.78rem; }
        .trace-token-card-label { color: #4338ca; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; font-size: 0.72rem; }
        /* Input / Output / Total / Events — coded by color so a tired dev can scan at a glance.
           Teal-cyan for input, amber-warm for output, slate for total, muted for events.
           Teal-vs-amber chosen over red-vs-green because it survives the most common
           colorblindness forms (deuteranopia/protanopia) and passes WCAG AA on the
           #f1f5f9/#eef2ff card background. */
        .trace-token-input { color: #0e7490; font-weight: 600; }
        .trace-token-output { color: #b45309; font-weight: 600; }
        .trace-token-sum { color: #1e293b; font-weight: 700; }
        .trace-token-events { color: #64748b; font-size: 0.7rem; }
    """.trimIndent()

    /**
     * Main entry point for the PumpStation HTML report. Composes the header, state ribbon, sparkline,
     * path inventory, turn timeline, and outcome panel into a single HTML document.
     */
    internal fun generatePumpStationHtmlReport(trace: List<TraceEvent>): String
    {
        if (trace.isEmpty()) return "<html><body><h1>PumpStation Trace</h1><p>(empty trace)</p></body></html>"

        val status = derivePumpStationStatus(trace)
        val runId = trace.firstOrNull()?.metadata?.get("runId")?.toString() ?: "unknown"
        val durationMs = pumpStationDurationMs(trace)
        val tokenCard = buildContainerTokenCard(trace)
        val stateRibbon = buildPumpStationStateRibbon(trace)
        val sparkline = buildPumpStationSparkline(trace)
        val pathInventory = buildPumpStationPathInventory(trace)
        val turnTimeline = buildPumpStationTurnTimeline(trace)
        val outcome = buildPumpStationOutcomePanel(trace, status)
        val css = generatePumpStationCSS()

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>PumpStation Trace — $runId</title>
                <style>$css</style>
            </head>
            <body>
                <div class="ps-container">
                    <div class="ps-header">
                        <span class="ps-title">⛽ PumpStation Trace</span>
                        <span class="ps-status ps-status-$status">${status.uppercase()}</span>
                        <span class="ps-run-id">$runId</span>
                        <span class="ps-duration">⏱ ${durationMs}ms</span>
                        $tokenCard
                    </div>
                    $stateRibbon
                    $sparkline
                    $pathInventory
                    $turnTimeline
                    $outcome
                </div>
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
    private fun generateManifoldMermaidGraph(
        nodes: List<TraceNode>,
        trace: List<TraceEvent>,
        nodeNameMapper: (TraceEvent) -> String = ::mapManifoldNodeName
    ): String {
        val graph = StringBuilder()
        graph.append("graph TD\n")

        val nodeMap = nodes.associateBy { it.pipeName }

        nodes.forEachIndexed { index, node ->
            if(index == 0)
            {
                graph.append("    ${node.nodeId}{{\"${escapeHtml(node.pipeName)}\"}}\n")
            } else {
                graph.append("    ${node.nodeId}[\"${escapeHtml(node.pipeName)}\"]\n")
            }
            graph.append("    click ${node.nodeId} scrollToEvent\n")
            val styleClass = when(node.status) {
                NodeStatus.SUCCESS -> "success"
                NodeStatus.FAILURE -> "failure"
                NodeStatus.INFO -> "info"
                NodeStatus.WARNING -> "info"
            }
            graph.append("    ${node.nodeId}:::${styleClass}\n")
        }

        var previousNodeId: String? = null
        trace.forEach { event ->
            val label = nodeNameMapper(event)
            val node = nodeMap[label] ?: return@forEach
            if(previousNodeId != null && previousNodeId != node.nodeId)
            {
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
    private fun generateOrchestrationTable(
        trace: List<TraceEvent>,
        nodeNameMapper: (TraceEvent) -> String = ::mapManifoldNodeName
    ): String {
        val feed = StringBuilder()
        feed.append("<div class=\"event-feed\">")

        val startTime = trace.firstOrNull()?.timestamp ?: 0L
        trace.forEach { event ->
            val elapsed = event.timestamp - startTime
            val pipeName = nodeNameMapper(event)
            val severity = classifyEventSeverity(event)
            val phaseHtml = formatPhase(event.phase)
            val eventBadge = formatEventBadge(event, severity)
            val metadataSection = buildMetadataSection(event)
            val contentSection = buildContentSection(event)
            val errorSection = buildErrorSection(event)
            val elapsedHtml = "<span class=\"event-time\">+${elapsed}ms</span>"

            // SafePrune events get a hover-popover with the full report payload.
            val safePruneAttr = when (event.eventType)
            {
                TraceEventType.PUMP_STATION_SAFE_PRUNE_APPLIED -> "true"
                TraceEventType.PUMP_STATION_SAFE_PRUNE_DRY_RUN_COMPLETED -> "dry-run"
                else -> null
            }
            val safePrunePopup = if (safePruneAttr != null)
            {
                val originalCount = event.metadata["originalCount"] ?: "n/a"
                val finalCount = event.metadata["finalCount"] ?: "n/a"
                val tokensRemoved = event.metadata["tokensRemoved"] ?: "n/a"
                val enabledFlags = event.metadata["enabledFlags"] ?: "n/a"
                val title = if (safePruneAttr == "dry-run") "SafePrune Dry-Run Report" else "SafePrune Applied Report"
                """<div class="safe-prune-popup"><h5>${escapeHtml(title)}</h5>
                    <dl>
                        <dt>Original</dt><dd>${escapeHtml(originalCount.toString())} entries</dd>
                        <dt>Final</dt><dd>${escapeHtml(finalCount.toString())} entries</dd>
                        <dt>Tokens removed</dt><dd>${escapeHtml(tokensRemoved.toString())}</dd>
                        <dt>Strategies</dt><dd>${escapeHtml(enabledFlags.toString())}</dd>
                    </dl></div>"""
            }
            else ""

            feed.append(
                """
                <article id="${event.id}" class="trace-item event-card ${severity.cssClass}" data-pipe="${escapeHtml(pipeName)}"${
                    if (safePruneAttr != null) " data-safe-prune=\"$safePruneAttr\" tabindex=\"0\"" else ""
                }>
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
                    $safePrunePopup
                </article>
                """.trimIndent()
            )
        }
        feed.append("</div>")
        return feed.toString()
    }

    private fun buildJunctionSummary(trace: List<TraceEvent>): String {
        if(trace.isEmpty()) return ""

        val totalEvents = trace.size
        val failureCount = trace.count { classifyEventSeverity(it) == EventSeverity.FAILURE }
        val successCount = trace.count { classifyEventSeverity(it) == EventSeverity.SUCCESS }
        val start = trace.first().timestamp
        val end = trace.last().timestamp
        val durationMs = (end - start).coerceAtLeast(0L)
        val participantNames = trace.filter {
            it.eventType == TraceEventType.JUNCTION_PARTICIPANT_DISPATCH || it.eventType == TraceEventType.JUNCTION_PARTICIPANT_RESPONSE
        }
            .mapNotNull { event ->
                event.metadata["participant"]?.toString()?.takeIf { name -> name.isNotBlank() }
            }
            .distinct()
        val duration = formatDuration(durationMs)
        val participantSummary = if(participantNames.isEmpty()) "No participant interactions" else participantNames.joinToString(", ") { escapeHtml(it) }

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
                    <div class="subtext">Rounds/cycles traced in sequence</div>
                </div>
                <div class="summary-card">
                    <h3>Outcome</h3>
                    <div class="value">${successCount} ✓ / $failureCount ✕</div>
                    <div class="subtext">Success vs failure events</div>
                </div>
                <div class="summary-card">
                    <h3>Participants Touched</h3>
                    <div class="value">${participantNames.size}</div>
                    <div class="subtext">$participantSummary</div>
                </div>
            </div>
        """.trimIndent()
    }

    private fun buildDistributionGridSummary(trace: List<TraceEvent>): String {
        if(trace.isEmpty()) return ""

        val totalEvents = trace.size
        val failureCount = trace.count { classifyEventSeverity(it) == EventSeverity.FAILURE }
        val successCount = trace.count { classifyEventSeverity(it) == EventSeverity.SUCCESS }
        val start = trace.first().timestamp
        val end = trace.last().timestamp
        val durationMs = (end - start).coerceAtLeast(0L)
        val taskIds = trace.mapNotNull { it.metadata["taskId"]?.toString()?.takeIf { value -> value.isNotBlank() } }.distinct()
        val remoteHandoffs = trace.count { it.eventType == TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF }
        val discoveryEvents = trace.count {
            it.eventType in listOf(
                TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE,
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
                TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION
            )
        }
        val listingEvents = trace.count {
            it.eventType == TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING ||
                it.eventType == TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW
        }

        return """
            <div class="summary-grid">
                <div class="summary-card">
                    <h3>Total Events</h3>
                    <div class="value">$totalEvents</div>
                    <div class="subtext">Across ${trace.map { it.phase }.distinct().size} phases</div>
                </div>
                <div class="summary-card">
                    <h3>Execution Time</h3>
                    <div class="value">${formatDuration(durationMs)}</div>
                    <div class="subtext">Remote handoffs: $remoteHandoffs</div>
                </div>
                <div class="summary-card">
                    <h3>Outcome</h3>
                    <div class="value">${successCount} ✓ / $failureCount ✕</div>
                    <div class="subtext">Success vs failure events</div>
                </div>
                <div class="summary-card">
                    <h3>Task Scope</h3>
                    <div class="value">${taskIds.size}</div>
                    <div class="subtext">${if(taskIds.isEmpty()) "No task ids captured" else taskIds.joinToString(", ") { escapeHtml(it) }}</div>
                </div>
                <div class="summary-card">
                    <h3>Discovery Activity</h3>
                    <div class="value">$discoveryEvents</div>
                    <div class="subtext">Bootstrap, registry, && admission activity</div>
                </div>
                <div class="summary-card">
                    <h3>Public Listing Activity</h3>
                    <div class="value">$listingEvents</div>
                    <div class="subtext">Publish, update, renew, remove, && auto-renew</div>
                </div>
            </div>
        """.trimIndent()
    }

    private fun buildDistributionGridStateRibbon(trace: List<TraceEvent>): String {
        if(trace.isEmpty()) return ""

        val latestEvent = trace.last()
        val latestDecision = trace.lastOrNull { it.eventType == TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION }
        val latestHandoff = trace.lastOrNull { it.eventType == TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF }
        val latestResponse = trace.lastOrNull { it.eventType == TraceEventType.DISTRIBUTION_GRID_PEER_RESPONSE }
        val latestCatalogPull = trace.lastOrNull { it.eventType == TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL }
        val latestListing = trace.lastOrNull {
            it.eventType == TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING ||
                it.eventType == TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW
        }

        val stateCards = listOf(
            stateCard(
                title = "Current Event",
                value = latestEvent.eventType.name.lowercase().replace('_', ' '),
                subtext = mapDistributionGridNodeName(latestEvent)
            ),
            stateCard(
                title = "Task",
                value = latestEvent.metadata["taskId"]?.toString().orEmpty().ifBlank { "Unknown" },
                subtext = "Latest task identity"
            ),
            stateCard(
                title = "Node",
                value = latestEvent.metadata["nodeId"]?.toString().orEmpty().ifBlank { latestEvent.pipeName },
                subtext = "Owning grid node"
            ),
            stateCard(
                title = "Last Decision",
                value = latestDecision?.metadata?.get("directiveKind")?.toString().orEmpty().ifBlank { "Unknown" },
                subtext = latestDecision?.metadata?.get("targetNodeId")?.toString().orEmpty().ifBlank { "No target recorded" }
            ),
            stateCard(
                title = "Latest Handoff",
                value = latestHandoff?.metadata?.get("peerKey")?.toString().orEmpty().ifBlank { "None" },
                subtext = latestHandoff?.metadata?.get("targetNodeId")?.toString().orEmpty().ifBlank { "No remote target recorded" }
            ),
            stateCard(
                title = "Latest Response",
                value = latestResponse?.metadata?.get("outcomeStatus")?.toString().orEmpty().ifBlank { "Unknown" },
                subtext = latestResponse?.metadata?.get("peerKey")?.toString().orEmpty().ifBlank { "No peer response yet" }
            ),
            stateCard(
                title = "Latest Catalog Pull",
                value = latestCatalogPull?.metadata?.get("sourceId")?.toString().orEmpty().ifBlank { "None" },
                subtext = latestCatalogPull?.metadata?.get("acceptedCount")?.toString().orEmpty().ifBlank { "No bootstrap pull recorded" }
            ),
            stateCard(
                title = "Latest Listing Activity",
                value = latestListing?.metadata?.get("operation")?.toString().orEmpty().ifBlank { "None" },
                subtext = latestListing?.metadata?.get("listingKind")?.toString().orEmpty().ifBlank { "No listing activity recorded" }
            ),
            stateCard(
                title = "Recent Path",
                value = buildDistributionGridPathPreview(trace),
                subtext = "Last orchestration hops"
            )
        )

        return """
            <div class="state-ribbon">
                <h2>🧭 Grid State</h2>
                <div class="state-grid">
                    ${stateCards.joinToString("")}
                </div>
            </div>
        """.trimIndent()
    }

    private fun buildDistributionGridPathPreview(trace: List<TraceEvent>): String {
        val path = trace
            .map { mapDistributionGridNodeName(it) }
            .fold(mutableListOf<String>()) { acc, label ->
                if(acc.lastOrNull() != label)
                {
                    acc.add(label)
                }
                acc
            }
            .takeLast(7)

        return if(path.isEmpty()) "DistributionGrid" else path.joinToString(" → ")
    }

    private fun buildDistributionGridNodes(trace: List<TraceEvent>): List<TraceNode> {
        val nodeGroups = linkedMapOf<String, MutableList<TraceEvent>>()

        trace.forEach { event ->
            val label = mapDistributionGridNodeName(event)
            val events = nodeGroups.getOrPut(label) { mutableListOf() }
            events.add(event)
        }

        return nodeGroups.entries.mapIndexed { index, (label, events) ->
            TraceNode(
                nodeId = "node-${sanitizeNodeId(label)}-$index",
                pipeName = label,
                eventIds = events.map { it.id },
                status = when {
                    events.any { it.eventType.name.contains("FAILURE") } -> NodeStatus.FAILURE
                    events.any { it.eventType.name.contains("SUCCESS") } -> NodeStatus.SUCCESS
                    else -> NodeStatus.INFO
                }
            )
        }
    }

    private fun mapDistributionGridNodeName(event: TraceEvent): String {
        val taskId = event.metadata["taskId"]?.toString()?.takeIf { it.isNotBlank() }
        return when(event.eventType)
        {
            TraceEventType.DISTRIBUTION_GRID_START,
            TraceEventType.DISTRIBUTION_GRID_END,
            TraceEventType.DISTRIBUTION_GRID_SUCCESS,
            TraceEventType.DISTRIBUTION_GRID_FAILURE,
            TraceEventType.DISTRIBUTION_GRID_INIT,
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_START,
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_SUCCESS,
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_FAILURE,
            TraceEventType.DISTRIBUTION_GRID_PAUSE,
            TraceEventType.DISTRIBUTION_GRID_RESUME,
            TraceEventType.DISTRIBUTION_GRID_RUNTIME_RESET -> DISTRIBUTION_GRID_NODE_NAME

            TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION -> "Router"
            TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_DISPATCH,
            TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_RESPONSE -> "Local Worker"

            TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF,
            TraceEventType.DISTRIBUTION_GRID_PEER_RESPONSE,
            TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE,
            TraceEventType.DISTRIBUTION_GRID_RETURN_ROUTING ->
                "Remote Peer: ${event.metadata["peerKey"] ?: event.metadata["targetNodeId"] ?: taskId ?: "unknown"}"

            TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL ->
                "Bootstrap Catalog: ${event.metadata["sourceId"] ?: "unknown"}"

            TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
            TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION ->
                "Registry: ${event.metadata["registryId"] ?: event.metadata["sourceId"] ?: "unknown"}"

            TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING,
            TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW ->
                "Public Listing: ${event.metadata["listingKind"] ?: event.metadata["listingId"] ?: "unknown"}"

            TraceEventType.DISTRIBUTION_GRID_MEMORY_ENVELOPE -> "Memory & Privacy"
            TraceEventType.DISTRIBUTION_GRID_POLICY_EVALUATION -> "Policy"
            TraceEventType.DISTRIBUTION_GRID_LOOP_GUARD -> "Loop Guard"
            TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT -> "Durability"
            else -> if(event.pipeName.isNotBlank()) event.pipeName else DISTRIBUTION_GRID_NODE_NAME
        }
    }

    /**
     * Produce a short, human-readable label for a PumpStation event. The label includes the turn index
     * (sourced from event metadata) and the most useful event-type-specific detail: the path name, the
     * selected judge verdict, the risk level, the stash ID, the registry ID, and so on.
     */
    private fun mapPumpStationNodeName(event: TraceEvent): String
    {
        val turn = event.metadata["turnIndex"]?.toString()?.takeIf { it.isNotBlank() } ?: "?"
        val phase = phaseShortName(event.eventType)
        val detail = when (event.eventType)
        {
            TraceEventType.PUMP_STATION_JUDGE_COMPLETED ->
                "isComplete=${event.metadata["isComplete"] ?: "?"}"
            TraceEventType.PUMP_STATION_DISPATCH_COMPLETED ->
                "→ ${event.metadata["selectedPathName"] ?: "(none)"}"
            TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED,
            TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED,
            TraceEventType.PUMP_STATION_PATH_STARTED,
            TraceEventType.PUMP_STATION_PATH_COMPLETED,
            TraceEventType.PUMP_STATION_PATH_FAILED,
            TraceEventType.PUMP_STATION_PATH_SELECTED ->
                "${event.metadata["pathName"] ?: "(unknown)"} [${event.metadata["riskLevel"] ?: "?"}]"
            TraceEventType.PUMP_STATION_PATH_HIDDEN ->
                "hidden: ${event.metadata["pathName"] ?: "(unknown)"}"
            TraceEventType.PUMP_STATION_INTERVENTION_STARTED,
            TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED ->
                "${event.metadata["pathName"] ?: "(unknown)"} trigger=${event.metadata["trigger"] ?: "?"}"
            TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED ->
                "${event.metadata["agentName"] ?: "(unknown)"}"
            TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED ->
                "${event.metadata["agentName"] ?: "(unknown)"}"
            TraceEventType.PUMP_STATION_STASH_CREATED ->
                "${event.metadata["stashId"] ?: "(unknown)"} reason=${event.metadata["reason"] ?: "?"}"
            TraceEventType.PUMP_STATION_COMPACTION_STARTED,
            TraceEventType.PUMP_STATION_COMPACTION_COMPLETED ->
                "strategy=${event.metadata["strategy"] ?: "?"} (${event.metadata["memoryMode"] ?: "?"})"
            TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED,
            TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED ->
                "mode=${event.metadata["memoryMode"] ?: "?"} fill=${event.metadata["compactionPercent"] ?: "?"}"
            TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED ->
                "passed=${event.metadata["passed"] ?: "?"}"
            TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED ->
                "status=${event.metadata["status"] ?: "?"} warnings=${event.metadata["warnings"] ?: "0"}"
            TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED ->
                "${event.metadata["pathName"] ?: "(unknown)"}"
            TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED ->
                "guard=${event.metadata["guard"] ?: "?"} path=${event.metadata["pathName"] ?: "(unknown)"}"
            TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED ->
                "fill=${event.metadata["fillRatio"] ?: "?"} threshold=${event.metadata["threshold"] ?: "?"}"
            TraceEventType.PUMP_STATION_FAILED ->
                "${event.metadata["error"] ?: "?"}: ${event.metadata["errorMessage"] ?: ""}"
            else -> event.metadata["selectedPathName"]?.toString().orEmpty()
        }
        return "Turn $turn $phase" + if (detail.isNotEmpty()) " $detail" else ""
    }

    /**
     * Short, friendly label for a PumpStation event type, used in node labels and timeline rows.
     */
    private fun phaseShortName(eventType: TraceEventType): String = when (eventType)
    {
        TraceEventType.PUMP_STATION_STARTED -> "Started"
        TraceEventType.PUMP_STATION_COMPLETED -> "Completed"
        TraceEventType.PUMP_STATION_FAILED -> "Failed"
        TraceEventType.PUMP_STATION_SUSPENDED -> "Suspended"
        TraceEventType.PUMP_STATION_RESUMED -> "Resumed"
        TraceEventType.PUMP_STATION_HEALTH_CHECK_STARTED -> "Health→"
        TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED -> "Health✓"
        TraceEventType.PUMP_STATION_JUDGE_STARTED -> "Judge→"
        TraceEventType.PUMP_STATION_JUDGE_COMPLETED -> "Judge✓"
        TraceEventType.PUMP_STATION_JUDGE_SKIPPED -> "Judge⊘"
        TraceEventType.PUMP_STATION_DISPATCH_STARTED -> "Dispatch→"
        TraceEventType.PUMP_STATION_DISPATCH_COMPLETED -> "Dispatch✓"
        TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED -> "Safety→"
        TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED -> "Safety✓"
        TraceEventType.PUMP_STATION_PATH_VALIDATION_COMPLETED -> "PathValid"
        TraceEventType.PUMP_STATION_INTERVENTION_STARTED -> "Intervene→"
        TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED -> "Intervene✓"
        TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED -> "FG Agent"
        TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED -> "BG Agent"
        TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED -> "Memory→"
        TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED -> "Memory✓"
        TraceEventType.PUMP_STATION_COMPACTION_STARTED -> "Compact→"
        TraceEventType.PUMP_STATION_COMPACTION_COMPLETED -> "Compact✓"
        TraceEventType.PUMP_STATION_GOAL_VALIDATION_STARTED -> "Goal→"
        TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED -> "Goal✓"
        TraceEventType.PUMP_STATION_PATH_SELECTED -> "PathSel"
        TraceEventType.PUMP_STATION_PATH_STARTED -> "Path→"
        TraceEventType.PUMP_STATION_PATH_COMPLETED -> "Path✓"
        TraceEventType.PUMP_STATION_PATH_FAILED -> "Path✗"
        TraceEventType.PUMP_STATION_PATH_HIDDEN -> "PathHidden"
        TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED -> "Reveal"
        TraceEventType.PUMP_STATION_STASH_CREATED -> "Stash"
        TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED -> "Blowout"
        TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED -> "LoopGuard"
        TraceEventType.PUMP_STATION_SAFE_PRUNE_APPLIED -> "SafePrune✂"
        TraceEventType.PUMP_STATION_SAFE_PRUNE_DRY_RUN_COMPLETED -> "SafePrune(dry)🔍"
        else -> eventType.name.removePrefix("PUMP_STATION_")
    }

    private fun generateDistributionGridMermaidGraph(nodes: List<TraceNode>, trace: List<TraceEvent>): String {
        return generateManifoldMermaidGraph(nodes, trace, ::mapDistributionGridNodeName)
    }

    private fun generateDistributionGridActivityTable(trace: List<TraceEvent>): String {
        val categories = linkedMapOf(
            "Bootstrap Catalog Pulls" to trace.filter { it.eventType == TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL },
            "Registry Discovery & Membership" to trace.filter {
                it.eventType in listOf(
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE,
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
                    TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
                    TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION
                )
            },
            "Public Listing Lifecycle" to trace.filter {
                it.eventType == TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING ||
                    it.eventType == TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW
            },
            "Memory / Policy / Durability" to trace.filter {
                it.eventType in listOf(
                    TraceEventType.DISTRIBUTION_GRID_MEMORY_ENVELOPE,
                    TraceEventType.DISTRIBUTION_GRID_POLICY_EVALUATION,
                    TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT
                )
            }
        )

        val table = StringBuilder()
        table.append("<table><tr><th>Area</th><th>Events</th><th>Latest Status</th><th>Latest Detail</th></tr>")
        categories.forEach { (label, events) ->
            val latest = events.lastOrNull()
            table.append(
                "<tr>" +
                    "<td>${escapeHtml(label)}</td>" +
                    "<td>${events.size}</td>" +
                    "<td>${escapeHtml(latest?.eventType?.name?.lowercase()?.replace('_', ' ') ?: "none")}</td>" +
                    "<td>${escapeHtml(buildDistributionGridActivityPreview(latest))}</td>" +
                "</tr>"
            )
        }
        table.append("</table>")
        return table.toString()
    }

    private fun buildDistributionGridActivityPreview(event: TraceEvent?): String {
        event ?: return "No activity recorded"
        return listOf(
            event.metadata["sourceId"]?.toString(),
            event.metadata["registryId"]?.toString(),
            event.metadata["listingId"]?.toString(),
            event.metadata["operation"]?.toString(),
            event.metadata["reason"]?.toString(),
            event.metadata["acceptedCount"]?.toString(),
            event.metadata["targetNodeId"]?.toString()
        )
            .filterNotNull()
            .firstOrNull { it.isNotBlank() }
            ?: event.content?.text?.takeIf { it.isNotBlank() }
            ?: "No detail"
    }

    private fun buildJunctionStateRibbon(trace: List<TraceEvent>): String {
        if(trace.isEmpty()) return ""

        val latestEvent = trace.last()
        val latestDispatch = trace.lastOrNull { it.eventType == TraceEventType.JUNCTION_PARTICIPANT_DISPATCH }
        val latestResponse = trace.lastOrNull { it.eventType == TraceEventType.JUNCTION_PARTICIPANT_RESPONSE }
        val currentPath = buildJunctionPathPreview(trace)
        val currentParticipant = latestEvent.metadata["participant"]?.toString()?.takeIf { it.isNotBlank() }
            ?: latestEvent.metadata["agentName"]?.toString()?.takeIf { it.isNotBlank() }
            ?: "Junction"

        val stateCards = listOf(
            stateCard(
                title = "Current Event",
                value = latestEvent.eventType.name.lowercase().replace('_', ' '),
                subtext = latestEvent.pipeName
            ),
            stateCard(
                title = "Current Phase",
                value = latestEvent.phase.name.lowercase().replaceFirstChar { it.titlecase() },
                subtext = "Harness checkpoint"
            ),
            stateCard(
                title = "Strategy",
                value = latestEvent.metadata["strategy"]?.toString().orEmpty().ifBlank { "Unknown" },
                subtext = "Discussion mode"
            ),
            stateCard(
                title = "Round / Cycle",
                value = buildRoundCycleLabel(latestEvent),
                subtext = "Latest harness step"
            ),
            stateCard(
                title = "Consensus",
                value = latestEvent.metadata["consensusReached"]?.toString().orEmpty().ifBlank { "Unknown" },
                subtext = "Current decision status"
            ),
            stateCard(
                title = "Current Path",
                value = currentPath,
                subtext = "Recent harness flow"
            ),
            stateCard(
                title = "Latest Participant",
                value = currentParticipant,
                subtext = "Most recent dispatch/response"
            ),
            stateCard(
                title = "Latest Dispatch",
                value = formatPreview(latestDispatch?.content?.text),
                subtext = latestDispatch?.metadata?.get("phase")?.toString().orEmpty().ifBlank { "No dispatch yet" }
            ),
            stateCard(
                title = "Latest Response",
                value = formatPreview(latestResponse?.content?.text),
                subtext = latestResponse?.metadata?.get("phase")?.toString().orEmpty().ifBlank { "No response yet" }
            )
        )

        return """
            <div class="state-ribbon">
                <h2>🧭 Harness State</h2>
                <div class="state-grid">
                    ${stateCards.joinToString("")}
                </div>
            </div>
        """.trimIndent()
    }

    private fun stateCard(title: String, value: String, subtext: String): String {
        return """
            <div class="state-card">
                <h3>${escapeHtml(title)}</h3>
                <div class="value">${escapeHtml(value.ifBlank { "—" })}</div>
                <div class="subtext">${escapeHtml(subtext.ifBlank { "—" })}</div>
            </div>
        """.trimIndent()
    }

    private fun buildRoundCycleLabel(event: TraceEvent): String {
        val round = event.metadata["round"]?.toString()?.takeIf { it.isNotBlank() }
        val maxRounds = event.metadata["maxRounds"]?.toString()?.takeIf { it.isNotBlank() }
        val cycle = event.metadata["cycle"]?.toString()?.takeIf { it.isNotBlank() }
        val parts = mutableListOf<String>()
        if(round != null)
        {
            parts.add("Round $round")
        }
        if(cycle != null)
        {
            parts.add("Cycle $cycle")
        }
        if(maxRounds != null)
        {
            parts.add("of $maxRounds")
        }
        return if(parts.isEmpty()) "Unknown" else parts.joinToString(" ")
    }

    private fun buildJunctionPathPreview(trace: List<TraceEvent>): String {
        val path = trace
            .map { mapJunctionNodeName(it) }
            .fold(mutableListOf<String>()) { acc, label ->
                if(acc.lastOrNull() != label)
                {
                    acc.add(label)
                }
                acc
            }
            .takeLast(6)

        return if(path.isEmpty()) "Junction" else path.joinToString(" → ")
    }

    private fun buildJunctionNodes(trace: List<TraceEvent>): List<TraceNode> {
        val nodeGroups = linkedMapOf<String, MutableList<TraceEvent>>()

        trace.forEach { event ->
            val label = mapJunctionNodeName(event)
            val events = nodeGroups.getOrPut(label) { mutableListOf() }
            events.add(event)
        }

        return nodeGroups.entries.mapIndexed { index, (label, events) ->
            TraceNode(
                nodeId = "node-${sanitizeNodeId(label)}-$index",
                pipeName = label,
                eventIds = events.map { it.id },
                status = when {
                    events.any { it.eventType.name.contains("FAILURE") } -> NodeStatus.FAILURE
                    events.any { it.eventType.name.contains("SUCCESS") } -> NodeStatus.SUCCESS
                    else -> NodeStatus.INFO
                }
            )
        }
    }

    private fun mapJunctionNodeName(event: TraceEvent): String {
        return when {
            event.eventType == TraceEventType.JUNCTION_PARTICIPANT_DISPATCH ||
                event.eventType == TraceEventType.JUNCTION_PARTICIPANT_RESPONSE -> {
                val participant = event.metadata["participant"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: event.metadata["agentName"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Unknown"
                "Participant: $participant"
            }
            event.eventType.name.startsWith("JUNCTION_") -> JUNCTION_NODE_NAME
            else -> event.pipeName
        }
    }

    private fun generateJunctionMermaidGraph(nodes: List<TraceNode>, trace: List<TraceEvent>): String {
        return generateManifoldMermaidGraph(nodes, trace, ::mapJunctionNodeName)
    }

    private fun generateParticipantInteractionTable(trace: List<TraceEvent>): String {
        val participantStats = mutableMapOf<String, ParticipantInteractionStats>()

        trace.filter {
            it.eventType in listOf(TraceEventType.JUNCTION_PARTICIPANT_DISPATCH, TraceEventType.JUNCTION_PARTICIPANT_RESPONSE)
        }.forEach { event ->
            val participant = event.metadata["participant"]?.toString()?.takeIf { it.isNotBlank() }
                ?: event.metadata["agentName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "Unknown"
            val stats = participantStats.getOrPut(participant) { ParticipantInteractionStats() }
            when(event.eventType)
            {
                TraceEventType.JUNCTION_PARTICIPANT_DISPATCH ->
                {
                    stats.dispatches++
                    stats.latestDispatch = event.content?.text.orEmpty()
                }
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE ->
                {
                    stats.responses++
                    stats.latestResponse = event.content?.text.orEmpty()
                }
                else -> {}
            }
        }

        val table = StringBuilder()
        table.append("<table><tr><th>🤖 Participant</th><th>📤 Dispatches</th><th>📥 Responses</th><th>📝 Latest Dispatch</th><th>💬 Latest Response</th><th>✅ Success Rate</th></tr>")

        participantStats.forEach { (participant, stats) ->
            val dispatches = stats.dispatches
            val responses = stats.responses
            val successRate = if(dispatches > 0) (responses * 100 / dispatches) else 0
            val participantHtml = escapeHtml(participant)
            table.append(
                "<tr class=\"trace-item agent-row\" data-pipe=\"Participant: $participantHtml\">" +
                    "<td>$participantHtml</td>" +
                    "<td>$dispatches</td>" +
                    "<td>$responses</td>" +
                    "<td>${formatPreview(stats.latestDispatch)}</td>" +
                    "<td>${formatPreview(stats.latestResponse)}</td>" +
                    "<td>$successRate%</td>" +
                "</tr>"
            )
        }

        table.append("</table>")
        return table.toString()
    }

    private data class ParticipantInteractionStats(
        var dispatches: Int = 0,
        var responses: Int = 0,
        var latestDispatch: String = "",
        var latestResponse: String = ""
    )

    private fun formatPreview(text: String?, limit: Int = 120): String {
        val trimmed = text?.trim().orEmpty()
        if(trimmed.isBlank())
        {
            return "—"
        }

        val preview = if(trimmed.length > limit) "${trimmed.take(limit)}…" else trimmed
        return escapeHtml(preview)
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
                when(event.eventType)
                {
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
            val successRate = if(dispatches > 0) (responses * 100 / dispatches) else 0
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
            event.eventType.name.startsWith("JUNCTION_") -> JUNCTION_NODE_NAME
            event.eventType.name.startsWith("MANAGER_") -> MANAGER_NODE_NAME
            event.eventType in listOf(TraceEventType.AGENT_DISPATCH, TraceEventType.AGENT_RESPONSE) ->
                "$AGENT_NODE_PREFIX${event.metadata["agentName"] ?: "Unknown"}"
            else -> if(event.pipeName.isNotBlank()) event.pipeName else MANIFOLD_NODE_NAME
        }
    }

    private fun sanitizeNodeId(name: String): String {
        return name.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')
    }

    /**
     * Creates an expandable section with collapsible content.
     */
    private fun createExpandableSection(label: String, content: String, icon: String, color: String): String {
        if(content.isBlank() || content == "N/A" || content == "null") return ""
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

    private fun formatMetadata(metadata: Map<String, Any>): String {
        if(metadata.isEmpty()) return "<p class=\"empty-state\">No metadata recorded for this event.</p>"
        val items = metadata.entries.joinToString("") { entry ->
            val key = entry.key
            val value = entry.value
            if (key.contains("token", ignoreCase = true)) {
                val color = when {
                    key.contains("input", ignoreCase = true) -> "#28a745" // Green
                    key.contains("output", ignoreCase = true) -> "#17a2b8" // Blue
                    else -> "#6f42c1" // Purple
                }
                "<div class=\"metadata-item\"><strong>${escapeHtml(key)}</strong><span style=\"color: $color; font-weight: bold;\">${escapeHtml(value.toString())}</span></div>"
            } else {
                "<div class=\"metadata-item\"><strong>${escapeHtml(key)}</strong><span>${escapeHtml(value.toString())}</span></div>"
            }
        }
        return "<div class=\"metadata-grid\">$items</div>"
    }

    private fun formatContentSummary(event: TraceEvent, summaryLabel: String): String {
        val sections = mutableListOf<String>()

        // Identify content keys in metadata
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

        // Add inputText
        val inputText = inputKey?.let { event.metadata[it]?.toString() } ?:
            if(event.eventType == TraceEventType.PIPE_START || event.eventType == TraceEventType.CONTEXT_PULL ||
               event.eventType.name.startsWith("MANIFOLD_") || event.eventType.name.startsWith("MANAGER_") ||
               event.eventType.name.startsWith("DISTRIBUTION_GRID_") ||
               event.eventType == TraceEventType.AGENT_DISPATCH ||
               event.eventType == TraceEventType.JUNCTION_PARTICIPANT_DISPATCH)
                event.content?.text
            else null
        if(!inputText.isNullOrBlank() && inputText != "N/A" && inputText != "null") {
            sections.add(createExpandableSection("Input Content", inputText, "📥", "#28a745"))
        }

        // Add outputText
        val outputText = outputKey?.let { event.metadata[it]?.toString() } ?:
            if(event.eventType == TraceEventType.PIPE_SUCCESS || event.eventType == TraceEventType.API_CALL_SUCCESS ||
               event.eventType == TraceEventType.AGENT_RESPONSE || event.eventType.name.startsWith("MANIFOLD_") ||
               event.eventType.name.startsWith("DISTRIBUTION_GRID_") ||
               event.eventType == TraceEventType.JUNCTION_PARTICIPANT_RESPONSE)
                event.content?.text
            else null
        if(!outputText.isNullOrBlank() && outputText != "N/A" && outputText != "null") {
            sections.add(createExpandableSection("Output Content", outputText, "📤", "#17a2b8"))
        }

        // Add requestObject
        val requestObject = requestObjectKey?.let { event.metadata[it]?.toString() }
        if(!requestObject.isNullOrBlank() && requestObject != "N/A" && requestObject != "null") {
            sections.add(createExpandableSection("Request Object", requestObject, "📦", "#6c757d"))
        }

        // Add generatedContent
        val generatedContent = generatedContentKey?.let { event.metadata[it]?.toString() }
        if(!generatedContent.isNullOrBlank() && generatedContent != "N/A" && generatedContent != "null") {
            sections.add(createExpandableSection("Generated Content", generatedContent, "✨", "#fd7e14"))
        }

        // Add fullPrompt
        val fullPrompt = fullPromptKey?.let { event.metadata[it]?.toString() }
        if(!fullPrompt.isNullOrBlank() && fullPrompt != "N/A" && fullPrompt != "null") {
            sections.add(createExpandableSection("Full Prompt", fullPrompt, "📝", "#000000"))
        }

        // Add contentText
        val contentText = contentTextKey?.let { event.metadata[it]?.toString() }
        if(!contentText.isNullOrBlank() && contentText != "N/A" && contentText != "null") {
            sections.add(createExpandableSection("Content Text", contentText, "📄", "#000000"))
        }

        // Add pageKey
        val pageKey = pageKeyKey?.let { event.metadata[it]?.toString() }
        if(!pageKey.isNullOrBlank() && pageKey != "N/A" && pageKey != "null") {
            sections.add(createExpandableSection("Page Key", pageKey, "🔑", "#ffc107"))
        }

        // Add contextWindow
        val contextWindow = contextWindowKey?.let { event.metadata[it]?.toString() }
        if(!contextWindow.isNullOrBlank() && contextWindow != "N/A" && contextWindow != "null") {
            sections.add(createExpandableSection("Context Window", contextWindow, "🪟", "#6f42c1"))
        }

        // Add miniBank
        val miniBank = miniBankKey?.let { event.metadata[it]?.toString() }
        if(!miniBank.isNullOrBlank() && miniBank != "N/A" && miniBank != "null") {
            sections.add(createExpandableSection("Mini Bank", miniBank, "🏦", "#e83e8c"))
        }

        // Add reasoningContent
        if(reasoningKey != null)
        {
            val reasoningContent = event.metadata[reasoningKey].toString()
            if(reasoningContent.isNotBlank() && reasoningContent != "N/A" && reasoningContent != "null")
            {
                sections.add(createExpandableSection("reasoningContent", reasoningContent, "🧠", "#007bff"))
            }
        }

        // Add context snapshot if present
        event.contextSnapshot?.let { snapshot ->
            sections.add("<span class=\"context-chip\">Context: ${escapeHtml(snapshot.toString())}</span>")
        }

        if(sections.isEmpty()) return "<p class=\"empty-state\">No content captured for this event.</p>"
        val inner = sections.joinToString("")
        return "<details class=\"event-details\"><summary>${escapeHtml(summaryLabel)}</summary>$inner</details>"
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
        val body = formatContentSummary(event, contentSummaryLabel(event))
        return """
            <section class="event-section">
                <h4>${escapeHtml(contentSectionHeading(event))}</h4>
                $body
            </section>
        """.trimIndent()
    }

    private fun contentSummaryLabel(event: TraceEvent): String {
        return when(event.eventType)
        {
            TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
            TraceEventType.AGENT_RESPONSE -> "Response & Context"
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TraceEventType.AGENT_DISPATCH -> "Prompt & Context"
            else -> "Content & Context"
        }
    }

    private fun contentSectionHeading(event: TraceEvent): String {
        return when(event.eventType)
        {
            TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
            TraceEventType.AGENT_RESPONSE -> "Response & Context"
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TraceEventType.AGENT_DISPATCH -> "Prompt & Context"
            else -> "Content & Context"
        }
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
        if(trace.isEmpty()) return ""
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
        val agentSummary = if(agentNames.isEmpty()) "No agent interactions" else agentNames.joinToString(", ") { escapeHtml(it) }

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
        if(durationMs <= 0) return "0 ms"
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
            if(index == -1) return key
            val prefix = key.substring(0, index)
            val suffix = key.substring(index + marker.length)
            return "$prefix\n${suffix.replace('_', ' ')}"
        }

        return when {
            nodeKey.contains("-SPLITTER_") -> splitLabel("-SPLITTER_", nodeKey)
            nodeKey.contains("-MANIFOLD_") -> splitLabel("-MANIFOLD_", nodeKey)
            nodeKey.contains("-DISTRIBUTION_GRID_") -> splitLabel("-DISTRIBUTION_GRID_", nodeKey)
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
            if(prevNode != null && currentNode != null && prevNode != currentNode)
            {
                graph.append("    $prevNode --> $currentNode\n")
            }
            if(currentNode != null)
            {
                prevNode = currentNode
            }
        }
    }
    
    private fun addNodeStyling(graph: StringBuilder, nodes: List<TraceNode>) 
    {
        nodes.forEach { node ->
            val cssClass = when(node.status) {
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
        private const val DISTRIBUTION_GRID_NODE_NAME = "DistributionGrid"
        private const val MANIFOLD_NODE_NAME = "Manifold"
        private const val JUNCTION_NODE_NAME = "Junction"
        private const val MANAGER_NODE_NAME = "Manager"
        private const val AGENT_NODE_PREFIX = "Agent: "
    }
}
