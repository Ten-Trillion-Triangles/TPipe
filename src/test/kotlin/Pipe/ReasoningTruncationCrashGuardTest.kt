package com.TTT.Pipe

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the `NoSuchElementException("List is empty")` crash thrown when an oversized reasoning
 * payload was discarded from the context window at the copy-back in `executeReasoningPipe`.
 *
 * A reasoning element that exceeds even the FULL reasoning budget is discarded by element-level truncation
 * (whole-element removal semantics). The copy-back must degrade to an empty reasoning stream and trace the
 * discard via CONTEXT_TRUNCATE instead of throwing, keeping the pipe running. The downstream empty-prompt
 * failure is the clean, expected terminal state rather than a framework crash.
 *
 * CONTEXT_TRUNCATE is a DETAILED-priority event, so the test captures it at VERBOSE detail level.
 */
class ReasoningTruncationCrashGuardTest {

    @BeforeEach
    fun setup()
    {
        PipeTracer.enable()
    }

    @AfterEach
    fun cleanup()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    /** 12000 tokens: far over the 200-token full reasoning budget, guaranteeing element discard. */
    companion object {
        val oversizedReasoning = (1..6000).joinToString(" ") { "word${it % 997}" }
    }

    private class OversizedReasoningPipe : Pipe()
    {
        init { pipeName = "Oversized-Reasoning" }

        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = ReasoningTruncationCrashGuardTest.oversizedReasoning
    }

    private class RootPipe : Pipe()
    {
        init { pipeName = "Root" }

        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "root output"
    }

    @Test
    fun `oversized reasoning is discarded without crashing and traced`() = runBlocking {
        val root = RootPipe().apply {
            setReasoningPipe(OversizedReasoningPipe())
            setTokenBudget(TokenBudgetSettings(contextWindowSize = 4000, maxTokens = 1000, reasoningBudget = 200))
            enableTracing(TraceConfig(detailLevel = TraceDetailLevel.VERBOSE))
        }

        val pipeline = Pipeline()
            .enableTracing(TraceConfig(detailLevel = TraceDetailLevel.VERBOSE))
            .add(root)

        val result = pipeline.execute(MultimodalContent("test prompt"))

        // Pre-fix, this path threw NoSuchElementException("List is empty") from the copy-back in
        // executeReasoningPipe. The discarded element now degrades to an empty reasoning stream and the
        // pipe completes.
        assertEquals("root output", result.text, "pipe must complete even when the reasoning element is discarded")

        val trace = PipeTracer.getTrace(pipeline.getTraceId())
        val truncateEvents = trace.filter { it.eventType == TraceEventType.CONTEXT_TRUNCATE }
        assertTrue(
            truncateEvents.any { it.metadata["reasoningElementDiscarded"] == true },
            "discarded reasoning element must be traced via CONTEXT_TRUNCATE; events=${truncateEvents.map { it.metadata }}"
        )
    }
}
