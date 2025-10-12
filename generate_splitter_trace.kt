#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

import kotlinx.coroutines.*

// Simple HTML generator for Splitter trace visualization
fun generateSplitterTraceHtml(): String {
    val events = listOf(
        TraceEventData("SPLITTER_START", "INITIALIZATION", "Container lifecycle started", "activatorKeyCount: 2, totalPipelines: 3"),
        TraceEventData("SPLITTER_CONTENT_DISTRIBUTION", "INITIALIZATION", "Content distributed to pipelines", "activatorKey: analysis, pipelineCount: 2"),
        TraceEventData("SPLITTER_PARALLEL_START", "EXECUTION", "Parallel execution initiated", "totalJobs: 3"),
        TraceEventData("SPLITTER_PIPELINE_DISPATCH", "EXECUTION", "Pipeline 1 dispatched", "pipelineName: TestPipeline1"),
        TraceEventData("SPLITTER_PIPELINE_DISPATCH", "EXECUTION", "Pipeline 2 dispatched", "pipelineName: TestPipeline2"),
        TraceEventData("SPLITTER_PIPELINE_DISPATCH", "EXECUTION", "Pipeline 3 dispatched", "pipelineName: TestPipeline3"),
        TraceEventData("SPLITTER_PIPELINE_COMPLETION", "EXECUTION", "Pipeline 1 completed", "success: true, resultSize: 25"),
        TraceEventData("SPLITTER_PIPELINE_CALLBACK", "POST_PROCESSING", "Pipeline 1 callback executed", "pipelineName: TestPipeline1"),
        TraceEventData("SPLITTER_PIPELINE_COMPLETION", "EXECUTION", "Pipeline 2 completed", "success: true, resultSize: 28"),
        TraceEventData("SPLITTER_PIPELINE_CALLBACK", "POST_PROCESSING", "Pipeline 2 callback executed", "pipelineName: TestPipeline2"),
        TraceEventData("SPLITTER_PIPELINE_COMPLETION", "EXECUTION", "Pipeline 3 completed", "success: true, resultSize: 30"),
        TraceEventData("SPLITTER_PIPELINE_CALLBACK", "POST_PROCESSING", "Pipeline 3 callback executed", "pipelineName: TestPipeline3"),
        TraceEventData("SPLITTER_PARALLEL_AWAIT", "POST_PROCESSING", "Awaiting parallel completion", "jobCount: 3"),
        TraceEventData("SPLITTER_RESULT_COLLECTION", "POST_PROCESSING", "Results collected", "resultCount: 3, totalJobs: 3"),
        TraceEventData("SPLITTER_COMPLETION_CALLBACK", "CLEANUP", "Splitter completion callback", "resultCount: 3"),
        TraceEventData("SPLITTER_SUCCESS", "CLEANUP", "Splitter execution successful", "totalResults: 3, successfulPipelines: 3"),
        TraceEventData("SPLITTER_END", "CLEANUP", "Container lifecycle ended", "")
    )
    
    return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TPipe Splitter Trace Visualization</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            margin: 0;
            padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
        }
        
        .container {
            max-width: 1200px;
            margin: 0 auto;
            background: white;
            border-radius: 10px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.3);
            overflow: hidden;
        }
        
        .header {
            background: linear-gradient(135deg, #2c3e50 0%, #34495e 100%);
            color: white;
            padding: 30px;
            text-align: center;
        }
        
        .header h1 {
            margin: 0;
            font-size: 2.5em;
            font-weight: 300;
        }
        
        .header p {
            margin: 10px 0 0 0;
            opacity: 0.8;
            font-size: 1.1em;
        }
        
        .timeline {
            padding: 40px;
        }
        
        .event {
            display: flex;
            margin-bottom: 20px;
            padding: 20px;
            border-radius: 8px;
            border-left: 5px solid;
            background: #f8f9fa;
            transition: all 0.3s ease;
            position: relative;
        }
        
        .event:hover {
            transform: translateX(5px);
            box-shadow: 0 5px 15px rgba(0,0,0,0.1);
        }
        
        .event.critical { border-left-color: #e74c3c; background: #fdf2f2; }
        .event.standard { border-left-color: #3498db; background: #f0f8ff; }
        .event.detailed { border-left-color: #f39c12; background: #fef9e7; }
        .event.internal { border-left-color: #95a5a6; background: #f8f9fa; }
        
        .event-type {
            font-weight: bold;
            font-size: 1.1em;
            margin-bottom: 5px;
            color: #2c3e50;
        }
        
        .event-phase {
            display: inline-block;
            background: #ecf0f1;
            color: #7f8c8d;
            padding: 4px 8px;
            border-radius: 4px;
            font-size: 0.8em;
            font-weight: 500;
            margin-bottom: 10px;
        }
        
        .event-description {
            color: #555;
            margin-bottom: 8px;
            line-height: 1.4;
        }
        
        .event-metadata {
            font-size: 0.9em;
            color: #7f8c8d;
            font-family: 'Courier New', monospace;
            background: #ecf0f1;
            padding: 8px;
            border-radius: 4px;
        }
        
        .stats {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 20px;
            margin-bottom: 30px;
        }
        
        .stat-card {
            background: linear-gradient(135deg, #74b9ff 0%, #0984e3 100%);
            color: white;
            padding: 20px;
            border-radius: 8px;
            text-align: center;
        }
        
        .stat-number {
            font-size: 2em;
            font-weight: bold;
            margin-bottom: 5px;
        }
        
        .stat-label {
            opacity: 0.9;
            font-size: 0.9em;
        }
        
        .legend {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
            gap: 15px;
            margin-bottom: 30px;
        }
        
        .legend-item {
            display: flex;
            align-items: center;
            padding: 10px;
            background: #f8f9fa;
            border-radius: 6px;
        }
        
        .legend-color {
            width: 20px;
            height: 20px;
            border-radius: 3px;
            margin-right: 10px;
        }
        
        .footer {
            background: #2c3e50;
            color: white;
            padding: 20px;
            text-align: center;
            font-size: 0.9em;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🔄 TPipe Splitter Trace</h1>
            <p>Comprehensive execution trace showing parallel pipeline orchestration</p>
        </div>
        
        <div class="timeline">
            <div class="stats">
                <div class="stat-card">
                    <div class="stat-number">${events.size}</div>
                    <div class="stat-label">Total Events</div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">3</div>
                    <div class="stat-label">Pipelines Executed</div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">2</div>
                    <div class="stat-label">Activator Keys</div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">100%</div>
                    <div class="stat-label">Success Rate</div>
                </div>
            </div>
            
            <div class="legend">
                <div class="legend-item">
                    <div class="legend-color" style="background: #e74c3c;"></div>
                    <span>Critical Events</span>
                </div>
                <div class="legend-item">
                    <div class="legend-color" style="background: #3498db;"></div>
                    <span>Standard Events</span>
                </div>
                <div class="legend-item">
                    <div class="legend-color" style="background: #f39c12;"></div>
                    <span>Detailed Events</span>
                </div>
                <div class="legend-item">
                    <div class="legend-color" style="background: #95a5a6;"></div>
                    <span>Internal Events</span>
                </div>
            </div>
            
            ${events.mapIndexed { index, event ->
                val priority = getEventPriority(event.type)
                """
                <div class="event $priority">
                    <div style="flex: 1;">
                        <div class="event-type">${event.type}</div>
                        <div class="event-phase">${event.phase}</div>
                        <div class="event-description">${event.description}</div>
                        ${if (event.metadata.isNotEmpty()) """<div class="event-metadata">${event.metadata}</div>""" else ""}
                    </div>
                </div>
                """
            }.joinToString("")}
        </div>
        
        <div class="footer">
            Generated by TPipe Tracing System • ${java.time.LocalDateTime.now()}
        </div>
    </div>
</body>
</html>
    """.trimIndent()
}

data class TraceEventData(
    val type: String,
    val phase: String,
    val description: String,
    val metadata: String
)

fun getEventPriority(eventType: String): String {
    return when {
        eventType.contains("FAILURE") -> "critical"
        eventType.contains("START") || eventType.contains("END") || eventType.contains("SUCCESS") || eventType.contains("COMPLETION") -> "standard"
        eventType.contains("DISTRIBUTION") || eventType.contains("DISPATCH") || eventType.contains("CALLBACK") -> "detailed"
        eventType.contains("PARALLEL") || eventType.contains("AWAIT") || eventType.contains("COLLECTION") -> "internal"
        else -> "standard"
    }
}

// Generate and save the HTML
fun main() {
    val html = generateSplitterTraceHtml()
    val file = java.io.File("splitter_trace_visualization.html")
    file.writeText(html)
    
    println("✅ Splitter trace HTML generated: ${file.absolutePath}")
    println("📊 Generated comprehensive trace visualization")
    println("🔍 Open the HTML file in a browser to view the interactive trace")
    println("🎯 Shows complete Splitter orchestration lifecycle with:")
    println("   • Container initialization and cleanup")
    println("   • Content distribution to pipelines") 
    println("   • Parallel execution coordination")
    println("   • Pipeline dispatch and completion tracking")
    println("   • Callback execution monitoring")
    println("   • Result collection and aggregation")
}

if (args.isEmpty()) {
    main()
}
