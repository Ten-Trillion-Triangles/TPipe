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
