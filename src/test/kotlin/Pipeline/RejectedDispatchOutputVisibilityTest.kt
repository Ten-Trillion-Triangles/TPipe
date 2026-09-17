package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RejectedDispatchOutputVisibilityTest
{
    @Test
    fun failurePolicyDefaultsPreserveLegacyVisibilityAndSettersCopyValues()
    {
        val station = PumpStation()

        assertTrue(station.failurePolicy.retainRejectedDispatchOutputInTurnHistory)
        assertTrue(station.failurePolicy.includeRejectedDispatchOutputInRepairPrompt)

        station
            .setRetainRejectedDispatchOutputInTurnHistory(false)
            .setIncludeRejectedDispatchOutputInRepairPrompt(false)

        assertFalse(station.getRetainRejectedDispatchOutputInTurnHistory())
        assertFalse(station.getIncludeRejectedDispatchOutputInRepairPrompt())

        station.setFailurePolicy(
            PumpStationFailurePolicy(
                retainRejectedDispatchOutputInTurnHistory = true,
                includeRejectedDispatchOutputInRepairPrompt = false
            )
        )

        assertTrue(station.getRetainRejectedDispatchOutputInTurnHistory())
        assertFalse(station.getIncludeRejectedDispatchOutputInRepairPrompt())
    }

    @Test
    fun dslExposesRejectedOutputVisibilitySettings()
    {
        val station = pumpStation("rejected-output-dsl") {
            dispatchAgent = Pipeline()
            retainRejectedDispatchOutputInTurnHistory = false
            includeRejectedDispatchOutputInRepairPrompt = false
            path("noop") {
                setExecutionFunction { _, _, _, _ -> MultimodalContent(text = "ok") }
            }
        }

        assertFalse(station.getRetainRejectedDispatchOutputInTurnHistory())
        assertFalse(station.getIncludeRejectedDispatchOutputInRepairPrompt())
    }

    @Test
    fun repairPromptsRespectIndependentRepairVisibilitySetting()
    {
        val secret = "MALFORMED_DISPATCH_SECRET"
        val station = PumpStation()
        val badOutput = MultimodalContent(text = secret)

        assertTrue(station.buildRepairPrompt(badOutput).text.contains(secret))
        assertTrue(station.buildMultiPathRepairPrompt(badOutput).text.contains(secret))

        station.setIncludeRejectedDispatchOutputInRepairPrompt(false)

        val singlePrompt = station.buildRepairPrompt(badOutput).text
        val multiPrompt = station.buildMultiPathRepairPrompt(badOutput).text

        assertFalse(singlePrompt.contains(secret))
        assertFalse(multiPrompt.contains(secret))
        assertTrue(singlePrompt.contains("not parseable as a PathRequest JSON"))
        assertTrue(multiPrompt.contains("not parseable as a PathRequestList JSON"))
        assertTrue(singlePrompt.contains("pathName"))
        assertTrue(multiPrompt.contains("paths"))
    }

    @Test
    fun exhaustedMalformedDispatchOutputStaysInEventsButNotTurnHistory()
    {
        val secret = "MALFORMED_DISPATCH_SECRET"
        val station = buildTestStation()
            .setRetainRejectedDispatchOutputInTurnHistory(false)
        val dispatchPipe = ScriptedTestPipe(response = secret)
        station.setDispatchAgent(Pipeline().apply { add(dispatchPipe) })
        station.addPath(testPath("foo"))
        station.failurePolicy.repairInvalidDispatchJson = false

        var completed: DispatchCompleted? = null
        station.setEventObserver { event ->
            if (event is DispatchCompleted) completed = event
        }

        runBlocking {
            assertEquals(null, station.runDispatchPhase())
        }

        assertFalse(station.turnHistory.history.any { it.content.text.contains(secret) })
        val completedEvent = assertNotNull(completed)
        assertEquals(secret, completedEvent.result!!.text)
    }

    @Test
    fun protectedAgentFacingDispatchErrorMessagesOmitRawOutput()
    {
        val secret = "MALFORMED_DISPATCH_SECRET"
        val station = PumpStation()
            .setRetainRejectedDispatchOutputInTurnHistory(false)

        val invalidRequestMessage = station.buildInvalidPathRequestMessage(
            mapOf("output" to secret, "availablePaths" to listOf("foo"))
        )

        assertFalse(invalidRequestMessage.contains(secret))
        assertTrue(invalidRequestMessage.contains("omitted from this agent-facing notice"))
    }
}
