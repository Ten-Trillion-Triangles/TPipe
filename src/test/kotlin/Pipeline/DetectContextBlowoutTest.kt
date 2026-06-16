package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DetectContextBlowoutTest
{
    @Test
    fun testBlowoutStashesOversizedContent()
    {
        val station = PumpStation()
        station.setBlowoutThreshold(0.0)  // Always trigger
        station.failurePolicy.stashOversizedOutputs = true
        station.getTaskState().latestContent = MultimodalContent(text = "x".repeat(100_000))

        runBlocking {
            val triggered = station.detectAndHandleContextBlowout(PumpStationPhase.Judge)
            assertTrue(triggered)
        }

        val manifest = station.getStashManifest()
        assertEquals(1, manifest.size)
        assertEquals(StashReason.TokenOverflow, manifest[0].reason)
    }

    @Test
    fun testBlowoutDoesNotTriggerBelowThreshold()
    {
        val station = PumpStation()
        station.setBlowoutThreshold(0.99)  // Very high; never triggered
        station.getTaskState().latestContent = MultimodalContent(text = "small")

        runBlocking {
            val triggered = station.detectAndHandleContextBlowout(PumpStationPhase.Judge)
            assertEquals(false, triggered)
        }
    }
}
