package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class MultiStreamTracingTest {

    @Test
    fun `test trace events appear in both pipeline and splitter trace streams`() {
        runBlocking {
        // Arrange
        val traceConfig = TraceConfig(enabled = true)
        
        // 1. Setup individual pipeline with tracing enabled
        val pipeline = Pipeline()
        pipeline.pipelineName = "IndividualPipeline"
        pipeline.enableTracing(traceConfig)
        val pipelineTraceId = pipeline.getTraceId()
        
        // Add a mock pipe that emits events
        val mockPipe = MockPipe("TestPipe")
        pipeline.add(mockPipe)
        
        // 2. Setup Splitter with tracing enabled
        val splitter = Splitter()
        splitter.enableTracing(traceConfig)
        val splitterTraceId = splitter.getTraceId()
        
        // Add the ALREADY TRACED pipeline to the splitter
        splitter.addPipeline("key", pipeline)
        splitter.init(traceConfig) // This should ADD the splitter ID to the pipes
        
        // Act
        splitter.executePipelines().awaitAll()
        
        // Assert

        val pipelineTrace = PipeTracer.getTrace(pipelineTraceId)
        val splitterTrace = PipeTracer.getTrace(splitterTraceId)
        


        // Verify individual pipeline trace contains the event
        assertNotNull(pipelineTrace, "Pipeline trace should exist")
        val pipelineEvent = pipelineTrace.find { it.pipeName.contains("TestPipe") }
        
        // Verify splitter trace ALSO contains the event
        assertNotNull(splitterTrace, "Splitter trace should exist")
        val splitterEvent = splitterTrace.find { it.pipeName.contains("TestPipe") }
        
        assertNotNull(pipelineEvent, "Pipeline trace should contain pipe event (Success or Failure)")
        assertNotNull(splitterEvent, "Splitter trace should contain pipe event (Success or Failure)")
        
        // Verify it is indeed the SAME event (correlated by ID or timestamp) - optional but good check
        // Note: Event objects might be different instances if defensive copies are made, but content should match.
        // We can check if eventType matches.
        assertEquals(pipelineEvent?.eventType, splitterEvent?.eventType, "Events in both streams should be of same type")
        }
    }

    class MockPipe(name: String) : Pipe() {
        init {
            this.pipeName = name
        }
        
        override fun truncateModuleContext(): Pipe = this
        


        // We need to override execute(MultimodalContent) because that's what Pipeline calls
        // But we want to rely on the base class's executeMultimodal wrapper for tracing if possible.
        // However, Pipe.execute(content) calls executeMultimodal, which is private.
        // Wait, Pipe.execute(content) IS open and calls executeMultimodal.
        // Actually, looking at Pipe.kt, trace() is called inside executeMultimodal() or by specific implementations.
        // Since executeMultimodal is private, we can't call it directly.
        // BUT, we can call trace() ourselves if we want, OR trust that the standard flow works.
        // Let's manually trigger a trace to be sure we are testing the routing logic, 
        // mimicking what a real model pipe would do.
        
        // Mock execution by implementing the abstract core logic
        // We cannot override execute() as it is final, but we can override generateText or just rely on internal behavior.
        // The goal is to ensure trace() is called.
        // generateText is called by the default execute() flow.
        override suspend fun generateText(promptInjector: String): String {
            // Manually trigger trace events to simulate pipe execution
            // We use reflection or just assume we are in the flow to verify TRACER logic, not execution logic.
            // But wait, trace() is protected. We can call it from here!
            trace(TraceEventType.PIPE_START, TracePhase.EXECUTION, null)
            delay(10)
            trace(TraceEventType.PIPE_SUCCESS, TracePhase.EXECUTION, null) // Use null content, good enough for signal check
            return "MockResult"
        }
    }
}
