package com.TTT.Pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class PumpStationMultiPathDispatchTest
{
    @Test
    fun pathExecutionShapeEnumHasExpectedValues()
    {
        assertEquals(2, PathExecutionShape.entries.size)
        assertNotEquals(PathExecutionShape.SinglePath, PathExecutionShape.MultiPath)
    }

    @Test
    fun pathExecutionShapeDefaultIsSinglePath()
    {
        val station = pumpStation("default-shape") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            path("noop") {
                description = "no-op test path"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        assertEquals(PathExecutionShape.SinglePath, station.getPathExecutionShape())
        assertNotNull(station)
    }
}