package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PumpStationInjectInterruptTest
{
    @Test
    fun `injectInterruptForPhase throws PumpStationInterruptException when service has pending entry`() = runTest {
        val station = pumpStation("inject-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        val content = MultimodalContent(text = "halt and switch")
        station.interruptService.enqueue(PumpStationPausePhase.BeforeJudge, content)
        val snapshot = station.takeInterruptSnapshot()
        val ex = assertThrows(PumpStationInterruptException::class.java) {
            kotlinx.coroutines.runBlocking {
                station.injectInterruptForPhase(PumpStationPausePhase.BeforeJudge, snapshot)
            }
        }
        assertEquals("halt and switch", ex.content.text)
    }

    @Test
    fun `injected interrupt message carries canonical metadata interrupt envelope`() = runTest {
        val station = pumpStation("envelope-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        val content = MultimodalContent(text = "halt")
        station.interruptService.enqueue(PumpStationPausePhase.BeforeJudge, content)
        val snapshot = station.takeInterruptSnapshot()
        try
        {
            station.injectInterruptForPhase(PumpStationPausePhase.BeforeJudge, snapshot)
        }
        catch (e: PumpStationInterruptException)
        {
            val envelope = e.content.metadata["interrupt"]
            assertNotNull(envelope, "envelope must be present")
            @Suppress("UNCHECKED_CAST")
            val map = envelope as Map<String, Any>
            assertEquals(PumpStationPausePhase.BeforeJudge.name, map["phase"])
            assertEquals(true, map["wasRewound"])
            assertNotNull(map["injectionId"])
            assertNotNull(map["timestamp"])
        }
    }

    @Test
    fun `injectInterruptForPhase is no-op when service is empty`() = runTest {
        val station = pumpStation("noop-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        val snapshot = station.takeInterruptSnapshot()
        // Should not throw.
        kotlinx.coroutines.runBlocking {
            station.injectInterruptForPhase(PumpStationPausePhase.AfterDispatch, snapshot)
        }
    }
}
