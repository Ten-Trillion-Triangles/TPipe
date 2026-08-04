package com.TTT

import com.TTT.Debug.PipeTracer
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NestedTracingTest {

    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }

    @AfterEach
    fun cleanup() {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    /**
     * Pins the bug at Pipe.kt:5052-5062: propagateTracingRecursively sets
     * childPipe.tracingEnabled = true but does NOT propagate the trace ID via
     * childPipe.addTraceId(...). Without the trace ID, child pipes' `trace()`
     * calls iterate an empty `activeTraceIds` set and silently drop every
     * event they emit. This test asserts that a child reasoning pipe's events
     * are visible in PipeTracer under the pipeline's trace ID.
     */
    @Test
    fun childReasoningPipeEventsAreRecordedUnderPipelineTraceId() = runBlocking {
        val childReasoning = DummyPipe("Visible-Child-Reasoning")
        val rootPipe = DummyPipe("Visible-Root").apply {
            setReasoningPipe(childReasoning)
        }

        val pipeline = Pipeline()
            .enableTracing()
            .add(rootPipe)

        pipeline.execute(MultimodalContent("trigger"))

        val traceId = pipeline.getTraceId()
        val trace = PipeTracer.getTrace(traceId)
        val pipeNames = trace.map { it.pipeName }.toSet()

        // Bug indicator: if propagateTracingRecursively failed to add the trace
        // ID to the child reasoning pipe, its events are recorded against an
        // empty activeTraceIds set and never appear under the pipeline's trace.
        assertTrue(
            pipeNames.contains("Visible-Child-Reasoning"),
            "Child reasoning pipe events must be recorded under the pipeline's trace ID; got pipeNames=$pipeNames"
        )
        assertTrue(
            pipeNames.contains("Visible-Root"),
            "Root pipe events must be recorded under the pipeline's trace ID; got pipeNames=$pipeNames"
        )
    }

    @Test
    fun testNestedReasoningPipeTracing() = runBlocking {
        val nestedReasoning = DummyPipe("Reasoning-Level-2")
        val primaryReasoning = DummyPipe("Reasoning-Level-1").apply {
            setReasoningPipe(nestedReasoning)
        }
        val rootPipe = DummyPipe("RootPipe").apply {
            setReasoningPipe(primaryReasoning)
        }

        val pipeline = Pipeline()
            .enableTracing()
            .add(rootPipe)

        val result = pipeline.execute(MultimodalContent("trigger"))
        assertFalse(result.isEmpty(), "Pipeline should still produce output")

        val trace = PipeTracer.getTrace(pipeline.getTraceId())
        val pipeNames = trace.map { it.pipeName }.toSet()

        assertTrue(pipeNames.contains("RootPipe"), "Root pipe events should be present")
        assertTrue(pipeNames.contains("Reasoning-Level-1"), "First reasoning level should add trace events")
        assertTrue(pipeNames.contains("Reasoning-Level-2"), "Nested reasoning level should add trace events")

        val timestampsInOrder = trace.zipWithNext { prev, next -> prev.timestamp <= next.timestamp }
        assertTrue(timestampsInOrder.all { it }, "Trace events must remain chronological")
    }

    @Test
    fun testCycleDetection() = runBlocking {
        val rootPipe = DummyPipe("CycleRoot")
        val cycleChild = DummyPipe("CycleChild")

        rootPipe.setReasoningPipe(cycleChild)
        cycleChild.setReasoningPipe(rootPipe)

        val pipeline = Pipeline()
            .enableTracing()
            .add(rootPipe)

        val result = pipeline.execute(MultimodalContent("cycle"))
        assertFalse(result.isEmpty(), "Pipeline should terminate even with circular reasoning pipes")

        val trace = PipeTracer.getTrace(pipeline.getTraceId())
        val pipeNames = trace.map { it.pipeName }.toSet()
        assertTrue(pipeNames.contains("CycleRoot"))
        assertTrue(pipeNames.contains("CycleChild"))
    }

    private class DummyPipe(private val displayName: String) : Pipe() {

        init {
            pipeName = displayName
        }

        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String {
            return "$displayName generated text: $promptInjector"
        }

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            return MultimodalContent(text = "${content.text} -> handled by $displayName")
        }
    }
}
