package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RunPathFlowTest
{
    @Test
    fun testPathFlowCallsInvokePathForKnownPath()
    {
        val station = buildTestStation()
        val path = testPath("foo", returnText = "the result")
        station.addPath(path)

        runBlocking {
            station.runPathFlow(PathRequest(pathName = "foo"))
        }

        assertEquals("the result", station.getTaskState().latestContent?.text)
    }

    @Test
    fun testPathFlowEmitsPathFailedForUnknownPath()
    {
        val station = buildTestStation()
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking {
            station.runPathFlow(PathRequest(pathName = "nope"))
        }

        assertNotNull(events.find { it is PathFailed && it.error == PumpStationError.UnknownPath })
    }
}
