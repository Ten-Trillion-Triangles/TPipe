package com.TTT.Debug

import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.io.File

class SimpleSplitterTracingTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }
    
    @AfterEach
    fun cleanup() {
        PipeTracer.disable()
    }
    
    @Test
    fun generateSplitterTraceHtml() = runBlocking {
        // Create trace config
        val traceConfig = TraceConfig(
            enabled = true,
            detailLevel = TraceDetailLevel.DEBUG,
            outputFormat = TraceFormat.HTML,
            includeContext = true,
            includeMetadata = true
        )
        
        // Create splitter with tracing
        val splitter = Splitter().enableTracing(traceConfig)
        
        // Manually generate trace events to simulate splitter execution
        val splitterId = splitter.getTraceId()
        PipeTracer.startTrace(splitterId)
        
        // Simulate SPLITTER_START
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_START,
            phase = TracePhase.INITIALIZATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf(
                "activatorKeyCount" to 2,
                "totalPipelines" to 3,
                "splitterClass" to "com.TTT.Pipeline.Splitter"
            )
        ))
        
        delay(10)
        
        // Simulate SPLITTER_CONTENT_DISTRIBUTION
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_CONTENT_DISTRIBUTION,
            phase = TracePhase.INITIALIZATION,
            content = MultimodalContent("Test content for analysis task"),
            contextSnapshot = null,
            metadata = mapOf(
                "activatorKey" to "analysis",
                "pipelineCount" to 2,
                "contentSize" to 32
            )
        ))
        
        delay(10)
        
        // Simulate SPLITTER_PARALLEL_START
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_PARALLEL_START,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("totalJobs" to 3)
        ))
        
        delay(10)
        
        // Simulate SPLITTER_PIPELINE_DISPATCH events
        for (i in 1..3) {
            PipeTracer.addEvent(splitterId, TraceEvent(
                timestamp = System.currentTimeMillis(),
                pipeId = splitterId,
                pipeName = "Splitter-2keys",
                eventType = TraceEventType.SPLITTER_PIPELINE_DISPATCH,
                phase = TracePhase.EXECUTION,
                content = MultimodalContent("Test content for pipeline $i"),
                contextSnapshot = null,
                metadata = mapOf(
                    "activatorKey" to if (i <= 2) "analysis" else "summary",
                    "pipelineName" to "TestPipeline$i"
                )
            ))
            delay(5)
        }
        
        // Simulate pipeline completions
        for (i in 1..3) {
            delay(50) // Simulate processing time
            
            PipeTracer.addEvent(splitterId, TraceEvent(
                timestamp = System.currentTimeMillis(),
                pipeId = splitterId,
                pipeName = "Splitter-2keys",
                eventType = TraceEventType.SPLITTER_PIPELINE_COMPLETION,
                phase = TracePhase.EXECUTION,
                content = MultimodalContent("Result from pipeline $i"),
                contextSnapshot = null,
                metadata = mapOf(
                    "pipelineName" to "TestPipeline$i",
                    "success" to true,
                    "resultSize" to 25
                )
            ))
            
            // Simulate callback
            PipeTracer.addEvent(splitterId, TraceEvent(
                timestamp = System.currentTimeMillis(),
                pipeId = splitterId,
                pipeName = "Splitter-2keys",
                eventType = TraceEventType.SPLITTER_PIPELINE_CALLBACK,
                phase = TracePhase.POST_PROCESSING,
                content = MultimodalContent("Result from pipeline $i"),
                contextSnapshot = null,
                metadata = mapOf("pipelineName" to "TestPipeline$i")
            ))
        }
        
        delay(10)
        
        // Simulate SPLITTER_PARALLEL_AWAIT
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_PARALLEL_AWAIT,
            phase = TracePhase.POST_PROCESSING,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("jobCount" to 3)
        ))
        
        delay(10)
        
        // Simulate SPLITTER_RESULT_COLLECTION
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_RESULT_COLLECTION,
            phase = TracePhase.POST_PROCESSING,
            content = null,
            contextSnapshot = null,
            metadata = mapOf(
                "resultCount" to 3,
                "totalJobs" to 3
            )
        ))
        
        delay(10)
        
        // Simulate SPLITTER_COMPLETION_CALLBACK
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_COMPLETION_CALLBACK,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("resultCount" to 3)
        ))
        
        delay(10)
        
        // Simulate SPLITTER_SUCCESS
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null,
            metadata = mapOf(
                "totalResults" to 3,
                "successfulPipelines" to 3,
                "totalPipelines" to 3
            )
        ))
        
        delay(10)
        
        // Simulate SPLITTER_END
        PipeTracer.addEvent(splitterId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-2keys",
            eventType = TraceEventType.SPLITTER_END,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null,
            metadata = emptyMap()
        ))
        
        // Generate HTML report
        val traceReport = PipeTracer.exportTrace(splitterId, TraceFormat.HTML)
        
        // Write to file
        val outputFile = File("splitter_trace_demo.html")
        outputFile.writeText(traceReport)
        
        println("✅ Splitter trace HTML generated: ${outputFile.absolutePath}")
        println("📊 Generated ${PipeTracer.getTrace(splitterId).size} trace events")
        println("🔍 Open the HTML file in a browser to view the trace visualization")
    }
}
