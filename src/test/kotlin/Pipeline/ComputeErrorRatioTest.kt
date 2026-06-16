package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals

class ComputeErrorRatioTest
{
    @Test
    fun testZeroWhenNoPathCalls()
    {
        val station = PumpStation()
        val ratio = station.computeErrorRatio()
        assertEquals(0.0, ratio, 0.001)
    }
}
