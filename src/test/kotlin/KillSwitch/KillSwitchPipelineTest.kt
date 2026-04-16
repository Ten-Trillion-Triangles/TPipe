package com.TTT.KillSwitch

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
import com.TTT.P2P.KillSwitchException
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests KillSwitch enforcement at the Pipeline level.
 *
 * Pipeline.checkKillSwitch() is called after each pipe execution (line 1352 in Pipeline.kt).
 * These tests verify that KillSwitch can be triggered during pipeline execution
 * and that the exception properly propagates to the caller.
 */
class KillSwitchPipelineTest
{
    // Must NOT be private so JUnit can instantiate via reflection
    class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "output"
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            val result = MultimodalContent()
            result.text = "output"
            return result
        }
    }

    class CallbackException(msg: String) : RuntimeException(msg)

    // Test 1: checkKillSwitch throws when input exceeds limit
    @Test
    fun checkKillSwitchThrowsOnInputExceeded() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            pipeline.checkKillSwitch(150, 50, 100)
        }
        assertEquals("input_exceeded", ex.context.reason)
        assertEquals(150, ex.context.inputTokensSpent)
        assertEquals(50, ex.context.outputTokensSpent)
    }

    // Test 2: checkKillSwitch throws when output exceeds limit
    @Test
    fun checkKillSwitchThrowsOnOutputExceeded() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            pipeline.checkKillSwitch(50, 150, 100)
        }
        assertEquals("output_exceeded", ex.context.reason)
    }

    // Test 3: checkKillSwitch does not throw when under limit
    @Test
    fun checkKillSwitchNoThrowWhenUnderLimit() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(inputTokenLimit = 100, outputTokenLimit = 100)

        // Should not throw
        pipeline.checkKillSwitch(50, 50, 100)
    }

    // Test 4: checkKillSwitch does not throw when at exact boundary (strictly greater than)
    @Test
    fun checkKillSwitchNoThrowAtExactBoundary() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(inputTokenLimit = 100)

        // 100 > 100 is false, so should not throw
        pipeline.checkKillSwitch(100, 50, 100)
    }

    // Test 5: checkKillSwitch does not throw when killSwitch is null
    @Test
    fun checkKillSwitchNoThrowWhenNull() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = null

        // Should not throw even with high values
        pipeline.checkKillSwitch(1000, 1000, 100)
    }

    // Test 6: Custom callback is invoked when checkKillSwitch triggers
    @Test
    fun customCallbackIsInvoked() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))

        var callbackCalled = false
        var capturedReason: String? = null

        pipeline.killSwitch = KillSwitch(
            inputTokenLimit = 100,
            onTripped = { ctx ->
                callbackCalled = true
                capturedReason = ctx.reason
                throw CallbackException("callback invoked")
            }
        )

        val ex = assertFailsWith<CallbackException> {
            pipeline.checkKillSwitch(150, 50, 100)
        }
        assertTrue(callbackCalled)
        assertEquals("input_exceeded", capturedReason)
    }

    // Test 7: context contains elapsed time
    @Test
    fun contextContainsElapsedTime() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            pipeline.checkKillSwitch(150, 50, 5000)
        }
        assertTrue(ex.context.elapsedMs >= 0)
    }

    // Test 8: context p2pInterface is the pipeline
    @Test
    fun contextP2pInterfaceIsPipeline() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            pipeline.checkKillSwitch(150, 50, 100)
        }
        assertSame(pipeline, ex.context.p2pInterface)
    }

    // Test 9: Both input and output exceeded - combined reason is used
    @Test
    fun bothExceededUsesCombinedReason() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = KillSwitch(inputTokenLimit = 100, outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            pipeline.checkKillSwitch(200, 300, 100)
        }
        assertEquals("input_and_output_exceeded", ex.context.reason)
    }

    // Test 10: Execute with no killSwitch does not throw
    @Test
    fun executeWithoutKillSwitchDoesNotThrow() = runBlocking<Unit> {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe().setPipeName("pipe-1"))
        pipeline.killSwitch = null

        val result = pipeline.execute(MultimodalContent(text = "test"))
        assertNotNull(result)
    }
}