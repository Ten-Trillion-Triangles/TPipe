package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndependentTracingTest {

    class MockPipe(name: String) : Pipe() {
        init {
            this.pipeName = name
        }
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String {
            // Manually trigger events
            trace(TraceEventType.PIPE_START, TracePhase.EXECUTION, null)
            delay(10)
            trace(TraceEventType.PIPE_SUCCESS, TracePhase.EXECUTION, null)
            return "MockResult"
        }
    }

    @Test
    fun `test independent tracing mode - events appear ONLY in pipeline trace`() {
        runBlocking {
            // Arrange
            // 1. Config with mergeSplitterTraces = false
            val traceConfig = TraceConfig(enabled = true, mergeSplitterTraces = false)
            
            val pipeline = Pipeline()
            pipeline.pipelineName = "IndiePipeline"
            pipeline.enableTracing(traceConfig)
            val pipelineTraceId = pipeline.getTraceId()
            
            val mockPipe = MockPipe("TestPipe")
            pipeline.add(mockPipe)
            
            val splitter = Splitter()
            splitter.enableTracing(traceConfig)
            val splitterTraceId = splitter.getTraceId()
            
            splitter.addPipeline("key", pipeline)
            splitter.init(traceConfig)
            
            // Act
            splitter.executePipelines().awaitAll()
            
            // Assert
            val pipelineTrace = PipeTracer.getTrace(pipelineTraceId)
            val splitterTrace = PipeTracer.getTrace(splitterTraceId)
            
            // 1. Verify Pipeline Trace has events
            val pipelineEvent = pipelineTrace.find { it.pipeName.contains("TestPipe") }
            assertNotNull(pipelineEvent, "Pipeline should have its own events")
            
            // 2. Verify Splitter Trace DOES NOT have pipe events
            // Note: Splitter will have its own start/end events, but shouldn't have the child pipe's events
            val splitterEvent = splitterTrace.find { it.pipeName.contains("TestPipe") }
            assertNull(splitterEvent, "Splitter should NOT have child pipe events in independent mode")
            
            // 3. Verify Helpers
            val childPipelines = splitter.getAllChildPipelines()
            assertEquals(1, childPipelines.size, "Should have 1 child pipeline")
            assertEquals(pipeline, childPipelines[0])
            
            val childTraceIds = splitter.getChildTraceIds()
            assertEquals(pipelineTraceId, childTraceIds["IndiePipeline"], "Helper should return correct trace ID")
        }
    }

    @Test
    fun `test merged tracing mode - events appear in BOTH traces`() {
        runBlocking {
             // Arrange
            // 1. Config with mergeSplitterTraces = true (Default)
            val traceConfig = TraceConfig(enabled = true, mergeSplitterTraces = true)
            
            val pipeline = Pipeline()
            pipeline.pipelineName = "MergedPipeline"
            pipeline.enableTracing(traceConfig)
            val pipelineTraceId = pipeline.getTraceId()
            
            val mockPipe = MockPipe("TestPipe")
            pipeline.add(mockPipe)
            
            val splitter = Splitter()
            splitter.enableTracing(traceConfig)
            val splitterTraceId = splitter.getTraceId()
            
            splitter.addPipeline("key", pipeline)
            splitter.init(traceConfig)
            
            // Act
            splitter.executePipelines().awaitAll()
            
            // Assert
            val pipelineTrace = PipeTracer.getTrace(pipelineTraceId)
            val splitterTrace = PipeTracer.getTrace(splitterTraceId)
            
            // 1. Verify Pipeline Trace has events
            val pipelineEvent = pipelineTrace.find { it.pipeName.contains("TestPipe") }
            assertNotNull(pipelineEvent, "Pipeline should have its own events")
            
            // 2. Verify Splitter Trace ALSO has child pipe events
            val splitterEvent = splitterTrace.find { it.pipeName.contains("TestPipe") }
            assertNotNull(splitterEvent, "Splitter SHOULD have child pipe events in merged mode")
        }
    }
}
