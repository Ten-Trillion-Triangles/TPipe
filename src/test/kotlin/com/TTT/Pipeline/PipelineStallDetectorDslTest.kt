package com.TTT.Pipeline

import com.TTT.Pipe.DummyPipe
import com.TTT.Pipe.StallCallback
import com.TTT.Pipe.StreamingStallConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Tests for Pipeline-level enableStallDetector() DSL method and child propagation.
 */
class PipelineStallDetectorDslTest {

    @Test
    fun `enableStallDetector sets pipeline-level flag and config`() {
        val pipeline = Pipeline()
        pipeline.enableStallDetector(
            config = StreamingStallConfig(windowSize = 25, stallMinSilenceMs = 5_000L),
            callback = { }
        )
        // Verify the pipeline-level fields are set (internal access via reflection not needed;
        // we verify behavior by checking that init() propagates to children).
        val pipe1 = DummyPipe()
        val pipe2 = DummyPipe()
        pipeline.add(pipe1).add(pipe2)

        runBlocking { pipeline.init() }

        assertTrue(pipe1.enableStallDetector)
        assertTrue(pipe2.enableStallDetector)
        assertEquals(25, pipe1.stallDetectorConfig.windowSize)
        assertEquals(5_000L, pipe2.stallDetectorConfig.stallMinSilenceMs)
    }

    @Test
    fun `enableStallDetector propagates callback to children`() {
        val pipeline = Pipeline()
        var firedCount = 0
        pipeline.enableStallDetector(callback = { firedCount++ })

        val pipe = DummyPipe()
        pipeline.add(pipe)

        runBlocking { pipeline.init() }

        assertNotNull(pipe.stallCallback)
        runBlocking {
            pipe.stallCallback!!.invoke(
                com.TTT.Pipe.StallEvent(
                    pipeName = "x", elapsedMs = 0L, tokensSeen = 0, lastTokenTimestamp = 0L,
                    silenceMs = 0L, expectedIntervalMs = 0.0, actualIntervalMs = 0L,
                    stddevMultiplier = 3.0, retryAttempt = 0
                )
            )
        }
        assertEquals(1, firedCount)
    }

    @Test
    fun `init does not propagate stall config when enableStallDetector not called`() {
        val pipeline = Pipeline()
        val pipe = DummyPipe()
        pipeline.add(pipe)
        runBlocking { pipeline.init() }
        // enableStallDetector is false by default; pipe's flag stays false
        assertEquals(false, pipe.enableStallDetector)
    }
}