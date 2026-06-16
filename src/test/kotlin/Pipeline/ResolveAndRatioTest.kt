package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResolveAndRatioTest
{
    @Test
    fun testResolvePathFromNormalList()
    {
        runBlocking {
            val station = PumpStation().setDispatchAgent(Pipeline())
            val path = testPath("foo")
            station.addPath(path)
            val resolved = station.resolvePath("foo")
            assertNotNull(resolved)
            assertEquals("foo", resolved!!.pathName)
        }
    }

    @Test
    fun testResolvePathReturnsNullForUnknown()
    {
        runBlocking {
            val station = PumpStation().setDispatchAgent(Pipeline())
            val resolved = station.resolvePath("nope")
            assertNull(resolved)
        }
    }

    @Test
    fun testContextFillRatioZeroWhenEmpty()
    {
        val station = PumpStation()
        val ratio = station.contextFillRatio()
        assertEquals(0.0, ratio, 0.001)
    }
}
