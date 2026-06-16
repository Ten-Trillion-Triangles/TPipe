package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertNull

class PathObjectStashContentTest
{
    @Test
    fun testGetStashContentReturnsNullWhenStationNull()
    {
        val path = PathObject()
        val result = path.getStashContent("any-id", null)
        assertNull(result)
    }

    @Test
    fun testGetStashContentReturnsNullWhenNotFound()
    {
        runBlocking {
            val station = PumpStation().setDispatchAgent(Pipeline())
            val path = PathObject().apply { pathName = "p" }
            val result = path.getStashContent("missing-id", station)
            assertNull(result)
        }
    }
}
