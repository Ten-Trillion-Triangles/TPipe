package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertTrue

class LlmErrorMessageTest
{
    @Test
    fun testBuildInvalidPathRequestMessageMentionsSchema()
    {
        val station = PumpStation()
        val msg = station.buildLlmErrorMessage(
            PumpStationError.InvalidPathRequest,
            mapOf("output" to "garbage", "availablePaths" to listOf("foo", "bar"))
        )
        assertTrue(msg.contains("PathRequest", ignoreCase = true))
        assertTrue(msg.contains("foo"))
    }

    @Test
    fun testBuildUnknownPathMessageListsAvailablePaths()
    {
        val station = PumpStation()
        val msg = station.buildLlmErrorMessage(
            PumpStationError.UnknownPath,
            mapOf("pathName" to "missing", "availablePaths" to listOf("alpha", "beta"))
        )
        assertTrue(msg.contains("missing"))
        assertTrue(msg.contains("alpha"))
        assertTrue(msg.contains("beta"))
    }

    @Test
    fun testBuildPathExecutionExceptionMessageIncludesDetails()
    {
        val station = PumpStation()
        val msg = station.buildLlmErrorMessage(
            PumpStationError.PathExecutionException,
            mapOf("pathName" to "test", "exceptionMessage" to "NullPointerException")
        )
        assertTrue(msg.contains("test"))
        assertTrue(msg.contains("NullPointerException"))
    }
}
