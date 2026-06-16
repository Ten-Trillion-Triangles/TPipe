package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class GetPathsFixTest
{
    @Test
    fun testGetPathsReturnsSerializedVisibleDescriptors()
    {
        runBlocking {
            val station = PumpStation().setDispatchAgent(Pipeline())
            val path = PathObject().apply {
                pathName = "test_path"
                pathDescription = "A test path"
                setExecutionFunction { content, _, _, _ -> content }
            }
            station.addPath(path)
            val pathsString = station.getPaths()
            // Should contain the visible path descriptor data
            assertTrue(pathsString.contains("test_path"))
        }
    }
}
